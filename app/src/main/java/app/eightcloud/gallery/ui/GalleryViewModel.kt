package app.eightcloud.gallery.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.eightcloud.gallery.data.AppUpdate
import app.eightcloud.gallery.data.UpdateState
import app.eightcloud.gallery.data.MediaDetails
import app.eightcloud.gallery.data.DetailsLoader
import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.MediaSort
import app.eightcloud.gallery.data.MediaStoreSource
import app.eightcloud.gallery.data.SortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Screen { GRID, VIEWER, SETTINGS }

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var allItems: List<MediaItem> = emptyList()

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    private val _columns = MutableStateFlow(MediaSort.clampColumns(prefs.getInt("columns", 3)))
    val columns: StateFlow<Int> = _columns

    private val _sortField = MutableStateFlow(
        runCatching { SortField.valueOf(prefs.getString("sortField", "DATE")!!) }.getOrDefault(SortField.DATE)
    )
    val sortField: StateFlow<SortField> = _sortField

    private val _sortAscending = MutableStateFlow(prefs.getBoolean("sortAscending", false))
    val sortAscending: StateFlow<Boolean> = _sortAscending

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<Set<Long>> = _favorites

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _screen = MutableStateFlow(Screen.GRID)
    val screen: StateFlow<Screen> = _screen

    private val _viewerStartIndex = MutableStateFlow(0)
    val viewerStartIndex: StateFlow<Int> = _viewerStartIndex

    fun setPermissionGranted(granted: Boolean) {
        _permissionGranted.value = granted
        if (granted) loadMedia()
    }

    fun loadMedia() {
        viewModelScope.launch {
            _loading.value = true
            val result = withContext(Dispatchers.IO) {
                MediaStoreSource.query(getApplication())
            }
            allItems = result
            applyView()
            _loading.value = false
        }
    }

    fun setSort(field: SortField, ascending: Boolean) {
        _sortField.value = field
        _sortAscending.value = ascending
        prefs.edit().putString("sortField", field.name).putBoolean("sortAscending", ascending).apply()
        applyView()
    }

    fun setColumns(count: Int) {
        val clamped = MediaSort.clampColumns(count)
        if (clamped != _columns.value) {
            _columns.value = clamped
            prefs.edit().putInt("columns", clamped).apply()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("themeMode", mode.name).apply()
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
        applyView()
    }

    fun isFavorite(id: Long): Boolean = _favorites.value.contains(id)

    fun toggleFavorite(id: Long) {
        val next = _favorites.value.toMutableSet()
        if (!next.remove(id)) next.add(id)
        _favorites.value = next
        prefs.edit().putStringSet("favorites", next.map { it.toString() }.toSet()).apply()
        if (_favoritesOnly.value) applyView()
    }

    fun openViewer(index: Int) {
        _viewerStartIndex.value = index.coerceAtLeast(0)
        _screen.value = Screen.VIEWER
    }

    fun openSettings() { _screen.value = Screen.SETTINGS }

    fun backToGrid() { _screen.value = Screen.GRID }

    suspend fun loadDetails(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        DetailsLoader.load(getApplication(), item)
    }

    private fun applyView() {
        var view = allItems
        if (_favoritesOnly.value) view = view.filter { _favorites.value.contains(it.id) }
        _items.value = view.sortedWith(MediaSort.comparator(_sortField.value, _sortAscending.value))
    }

    private fun loadFavorites(): Set<Long> =
        prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    // --- In-app update flow (mirrors File Explorer CL: user-tap check/download/install) ---
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    val installedVersionCode: Long = AppUpdate.currentVersionCode(application)
    val installedVersionName: String = AppUpdate.currentVersionName(application)

    private var pendingGrantReturn = false
    private var updateCheckedThisProcess = false

    fun checkForUpdate(silent: Boolean) {
        AppUpdate.trace("check entry silent=$silent vm=${hashCode()} state=${_updateState.value} onMain=${android.os.Looper.getMainLooper().isCurrentThread}")
        if (_updateState.value == UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            AppUpdate.trace("check guard-return")
            return
        }
        AppUpdate.debugCheckCount++
        AppUpdate.debugLastCheckVm = hashCode()
        _updateState.value = UpdateState.Checking
        AppUpdate.trace("check pre-launch")
        viewModelScope.launch {
            AppUpdate.trace("coroutine started vm=${hashCode()}")
            val latest = AppUpdate.fetchLatest()
            val next = when {
                latest == null -> if (silent) UpdateState.Idle else UpdateState.Failed(null)
                AppUpdate.isNewer(latest, installedVersionCode) -> UpdateState.Available(latest)
                else -> if (silent) UpdateState.Idle else UpdateState.UpToDate
            }
            AppUpdate.trace("state <- $next vm=${hashCode()}")
            _updateState.value = next
        }
    }

    override fun onCleared() {
        AppUpdate.trace("vm-cleared ${hashCode()}")
        super.onCleared()
    }

    fun checkForUpdateOnce() {
        if (!updateCheckedThisProcess) {
            updateCheckedThisProcess = true
            checkForUpdate(silent = true)
        }
    }

    fun downloadUpdate() {
        val info = when (val s = _updateState.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        } ?: return
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(info, 0)
            _updateState.value = runCatching {
                AppUpdate.download(getApplication(), info) { percent ->
                    _updateState.value = UpdateState.Downloading(info, percent)
                }
            }.fold(
                onSuccess = { apk ->
                    if (AppUpdate.verifyApk(getApplication(), apk, installedVersionCode)) UpdateState.ReadyToInstall(info, apk)
                    else UpdateState.Failed(info)
                },
                onFailure = { UpdateState.Failed(info) },
            )
        }
    }

    fun installUpdate() {
        val ready = _updateState.value as? UpdateState.ReadyToInstall ?: return
        val app = getApplication<Application>()
        if (AppUpdate.canInstall(app)) {
            AppUpdate.promptInstall(app, ready.apk)
        } else {
            // First update needs the one-time unknown-sources grant; onResumeAfterGrant
            // offers the install sheet straight away when the user returns.
            pendingGrantReturn = true
            AppUpdate.openUnknownSourcesSettings(app)
        }
    }

    fun onResumeAfterGrant() {
        if (!pendingGrantReturn) return
        pendingGrantReturn = false
        val state = _updateState.value
        val app = getApplication<Application>()
        if (AppUpdate.shouldAutoPromptOnResume(true, state, AppUpdate.canInstall(app))) {
            AppUpdate.promptInstall(app, (state as UpdateState.ReadyToInstall).apk)
        }
    }

}
