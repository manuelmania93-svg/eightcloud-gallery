package app.eightcloud.gallery.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.eightcloud.gallery.BuildConfig
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.BackupEngine
import app.eightcloud.gallery.backup.BackupWorker
import app.eightcloud.gallery.backup.BackupEntry
import app.eightcloud.gallery.backup.BackupProgress
import app.eightcloud.gallery.data.MediaStoreSource
import app.eightcloud.gallery.telegram.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val td = TdClient.get(application)
    private val engine = BackupEngine(application)

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _accountName = MutableStateFlow<String?>(null)
    val accountName: StateFlow<String?> = _accountName

    private val _backedUp = MutableStateFlow(0)
    val backedUp: StateFlow<Int> = _backedUp

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    private val _backupEnabled = MutableStateFlow(prefs.getBoolean("backupEnabled", false))
    val backupEnabled: StateFlow<Boolean> = _backupEnabled

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifiOnly", true))

    private val _backupScope = MutableStateFlow(prefs.getString("backupScope", "ALL") ?: "ALL")
    val backupScope: StateFlow<String> = _backupScope

    private val _backupBuckets = MutableStateFlow(prefs.getStringSet("backupBuckets", emptySet()) ?: emptySet())
    val backupBuckets: StateFlow<Set<String>> = _backupBuckets

    private val _availableBuckets = MutableStateFlow<List<String>>(emptyList())
    val availableBuckets: StateFlow<List<String>> = _availableBuckets

    val progress: StateFlow<BackupProgress.State> = BackupProgress.state

    private val _failed = MutableStateFlow<List<BackupEntry>>(emptyList())
    val failed: StateFlow<List<BackupEntry>> = _failed

    private val _deletableBytes = MutableStateFlow(0L to 0)
    val deletableBytes: StateFlow<Pair<Long, Int>> = _deletableBytes
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    val credentialsMissing: Boolean
        get() = BuildConfig.TELEGRAM_API_ID.isBlank() || BuildConfig.TELEGRAM_API_HASH.isBlank()

    private val watcher: (TdApi.AuthorizationState) -> Unit = { state ->
        _authState.value = state
        if (state is TdApi.AuthorizationStateReady) refreshAccount()
    }

    init {
        td.addAuthWatcher(watcher)
        refreshCounts()
        loadBuckets()
    }

    override fun onCleared() {
        td.removeAuthWatcher(watcher)
    }

    fun startLogin() {
        td.start()
    }

    fun sendPhone(phone: String) = guard {
        td.start()
        td.setPhone(phone.trim())
    }

    fun sendCode(code: String) = guard { td.checkCode(code.trim()) }

    fun sendPassword(password: String) = guard { td.checkPassword(password) }

    fun setBackupEnabled(enabled: Boolean) {
        _backupEnabled.value = enabled
        prefs.edit().putBoolean("backupEnabled", enabled).apply()
        val app = getApplication<Application>()
        if (enabled) BackupWorker.schedule(app, _wifiOnly.value) else BackupWorker.cancel(app)
    }

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        prefs.edit().putBoolean("wifiOnly", enabled).apply()
        if (_backupEnabled.value) BackupWorker.schedule(getApplication(), enabled)
    }

    fun setBackupScope(scope: String) {
        _backupScope.value = scope
        prefs.edit().putString("backupScope", scope).apply()
    }

    fun setBucketIncluded(bucket: String, included: Boolean) {
        val next = if (included) _backupBuckets.value + bucket else _backupBuckets.value - bucket
        _backupBuckets.value = next
        prefs.edit().putStringSet("backupBuckets", next).apply()
    }

    private fun loadBuckets() {
        viewModelScope.launch(Dispatchers.IO) {
            val buckets = MediaStoreSource.query(getApplication())
                .map { it.bucket }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            _availableBuckets.value = buckets
        }
    }

    fun backUpNow() {
        BackupWorker.runNow(getApplication(), _wifiOnly.value)
    }

    fun verify() = guard {
        val missing = engine.verify()
        _error.value = if (missing == 0) "All cloud copies verified" else "$missing items missing in cloud"
        refreshCounts()
    }

    fun importIndex() = guard {
        val imported = engine.importIndex()
        _error.value = "Imported $imported cloud items"
        refreshCounts()
    }

    fun refreshCounts() {
        viewModelScope.launch {
            val (backed, pending) = withContext(Dispatchers.IO) { engine.counts() }
            _backedUp.value = backed
            _pending.value = pending
            _failed.value = engine.failed()
            val deletable = engine.backedUpLocal()
            _deletableBytes.value = deletable.sumOf { it.second } to deletable.size
        }
    }

    fun retryFailed() = guard {
        val n = engine.retryFailed()
        if (n > 0) backUpNow()
        refreshCounts()
    }

    /** Content uris of all verified-backed-up local copies (for system delete). */
    suspend fun backedUpLocalUris(): List<android.net.Uri> = withContext(Dispatchers.IO) {
        engine.backedUpLocal().map { it.first }
    }

    private fun refreshAccount() {
        viewModelScope.launch(Dispatchers.IO) {
            val user = runCatching { td.savedMessagesChatId(); td.me }.getOrNull()
            _accountName.value = user?.let { listOf(it.firstName, it.lastName).filter { n -> n.isNotBlank() }.joinToString(" ") }
        }
    }

    private fun guard(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                block()
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = false
            }
            refreshCounts()
        }
    }
}
