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
import app.eightcloud.gallery.backup.BackupControl
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

    private val _chargingOnly = MutableStateFlow(prefs.getBoolean("chargingOnly", false))
    val chargingOnly: StateFlow<Boolean> = _chargingOnly

    private val _backupScope = MutableStateFlow(prefs.getString("backupScope", "ALL") ?: "ALL")
    val backupScope: StateFlow<String> = _backupScope

    private val _backupBuckets = MutableStateFlow(prefs.getStringSet("backupBuckets", emptySet()) ?: emptySet())
    val backupBuckets: StateFlow<Set<String>> = _backupBuckets

    private val _availableBuckets = MutableStateFlow<List<String>>(emptyList())
    val availableBuckets: StateFlow<List<String>> = _availableBuckets

    val progress: StateFlow<BackupProgress.State> = BackupProgress.state

    fun toggleUploadPause() {
        BackupControl.setPaused(getApplication(), !BackupControl.isPaused(getApplication()))
    }

    private val _failed = MutableStateFlow<List<BackupEntry>>(emptyList())
    val failed: StateFlow<List<BackupEntry>> = _failed

    private val _deletableBytes = MutableStateFlow(0L to 0)
    val deletableBytes: StateFlow<Pair<Long, Int>> = _deletableBytes

    private val _destTitle = MutableStateFlow(prefs.getString("destTitle", null) ?: "Saved Messages")
    val destTitle: StateFlow<String> = _destTitle

    private val _groups = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val groups: StateFlow<List<Pair<Long, String>>> = _groups
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    val credentialsMissing: Boolean
        get() = BuildConfig.TELEGRAM_API_ID.isBlank() || BuildConfig.TELEGRAM_API_HASH.isBlank()

    private val watcher: (TdApi.AuthorizationState) -> Unit = { state ->
        _authState.value = state
        if (state is TdApi.AuthorizationStateReady) {
            refreshAccount()
            autoDetectDestination()
        }
    }

    init {
        td.addAuthWatcher(watcher)
        refreshCounts()
        loadBuckets()
        // Bootstrap the weekly health check for already-enabled backups.
        if (_backupEnabled.value) {
            app.eightcloud.gallery.backup.HealthCheckWorker.keepScheduled(getApplication())
        }
    }

    private val _cloudOnly = MutableStateFlow<List<BackupEntry>>(emptyList())
    val cloudOnly: StateFlow<List<BackupEntry>> = _cloudOnly

    private val _restoreProgress = MutableStateFlow<Triple<Int, Int, String>?>(null)
    val restoreProgress: StateFlow<Triple<Int, Int, String>?> = _restoreProgress

    fun loadCloudOnly() {
        viewModelScope.launch {
            runCatching { _cloudOnly.value = engine.cloudOnlyEntries() }
        }
    }

    fun restoreSelected(hashes: Set<String>) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = engine.restore(hashes) { done, total, name ->
                    _restoreProgress.value = Triple(done, total, name)
                }
                _restoreProgress.value = null
                if (result.failed > 0) {
                    _error.value = "${result.failed} of ${result.restored + result.failed} restores failed"
                }
            } catch (e: Exception) {
                _restoreProgress.value = null
                _error.value = e.message ?: "Restore failed"
            } finally {
                _busy.value = false
                loadCloudOnly()
            }
        }
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
        if (enabled) BackupWorker.schedule(app, _wifiOnly.value, _chargingOnly.value) else BackupWorker.cancel(app)
    }

    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        prefs.edit().putBoolean("wifiOnly", enabled).apply()
        if (_backupEnabled.value) BackupWorker.schedule(getApplication(), enabled, _chargingOnly.value)
    }

    fun setChargingOnly(enabled: Boolean) {
        _chargingOnly.value = enabled
        prefs.edit().putBoolean("chargingOnly", enabled).apply()
        if (_backupEnabled.value) BackupWorker.schedule(getApplication(), _wifiOnly.value, enabled)
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

    fun loadGroups() = guard {
        _groups.value = td.myGroups()
    }

    /** Picks a group as destination; auto-detects forum topics named Pics/Vids/General. */
    fun setDestination(chatId: Long, title: String) = guard {
        val topics = runCatching { td.forumTopics(chatId) }.getOrDefault(emptyList())
        fun topic(name: String) = topics.firstOrNull { it.second.equals(name, ignoreCase = true) }?.first ?: 0L
        prefs.edit()
            .putLong("destChatId", chatId)
            .putString("destTitle", title)
            .putLong("topicPics", topic("Pics"))
            .putLong("topicVids", topic("Vids"))
            .putLong("topicGeneral", topic("General"))
            .apply()
        _destTitle.value = title
        _error.value = if (topic("Pics") != 0L && topic("Vids") != 0L)
            "Destination: $title (photos to Pics, videos to Vids)"
        else
            "Destination: $title (no Pics/Vids topics found - files go to the group directly)"
    }

    fun setDestinationSavedMessages() {
        prefs.edit()
            .putLong("destChatId", 0L)
            .putString("destTitle", "Saved Messages")
            .putLong("topicPics", 0L).putLong("topicVids", 0L).putLong("topicGeneral", 0L)
            .apply()
        _destTitle.value = "Saved Messages"
    }

    /** Manuel's manual-ID entry (01:29): store chat/topic ids directly. */
    fun setDestinationManual(chatId: Long, pics: Long, vids: Long, general: Long) {
        prefs.edit()
            .putLong("destChatId", chatId)
            .putString("destTitle", "Custom chat $chatId")
            .putLong("topicPics", pics)
            .putLong("topicVids", vids)
            .putLong("topicGeneral", general)
            .apply()
        _destTitle.value = "Custom chat $chatId"
    }

    /** Auto-defaults to his backup group when nothing was chosen yet. */
    private fun autoDetectDestination() {
        if (prefs.getLong("destChatId", 0L) != 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val groups = runCatching { td.myGroups() }.getOrDefault(emptyList())
            val match = groups.firstOrNull { it.second.startsWith("Backup Pics Vids", ignoreCase = true) }
            if (match != null) {
                val topics = runCatching { td.forumTopics(match.first) }.getOrDefault(emptyList())
                fun topic(name: String) = topics.firstOrNull { t -> t.second.equals(name, ignoreCase = true) }?.first ?: 0L
                prefs.edit()
                    .putLong("destChatId", match.first)
                    .putString("destTitle", match.second)
                    .putLong("topicPics", topic("Pics"))
                    .putLong("topicVids", topic("Vids"))
                    .putLong("topicGeneral", topic("General"))
                    .apply()
                _destTitle.value = match.second
            }
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

    fun rebuildFromCloud() = guard {
        val imported = engine.rebuildIndexFromCloud()
        _error.value = if (imported == 0) "No cloud backups found to rebuild from"
        else "Rebuilt $imported cloud entries - they won't upload again"
        refreshCounts()
    }

    fun exportToFile(uri: android.net.Uri) = guard {
        val n = engine.exportIndexToFile(uri)
        _error.value = "Exported $n index entries + settings to file"
    }

    fun importFromFile(uri: android.net.Uri) = guard {
        val n = engine.importIndexFromFile(uri)
        _error.value = "Imported $n index entries + settings from file"
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
