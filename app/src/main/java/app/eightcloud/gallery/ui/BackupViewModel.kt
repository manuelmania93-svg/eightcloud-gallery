package app.eightcloud.gallery.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.eightcloud.gallery.BuildConfig
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.BackupEngine
import app.eightcloud.gallery.backup.BackupWorker
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
        }
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
