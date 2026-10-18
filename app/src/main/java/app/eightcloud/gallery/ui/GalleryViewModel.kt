package app.eightcloud.gallery.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.eightcloud.gallery.data.MediaDetails
import app.eightcloud.gallery.data.DetailsLoader
import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.MediaSort
import app.eightcloud.gallery.data.MediaStoreSource
import app.eightcloud.gallery.backup.BackupDb
import app.eightcloud.gallery.backup.BackupEntry
import app.eightcloud.gallery.backup.BackupWorker
import app.eightcloud.gallery.data.SortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Screen { GRID, VIEWER, SETTINGS, BACKUP }
enum class StatusFilter { ALL, BACKED_UP, PENDING, CLOUD_ONLY }

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

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _sectionCounts = MutableStateFlow<Map<StatusFilter, Int>>(emptyMap())
    val sectionCounts: StateFlow<Map<StatusFilter, Int>> = _sectionCounts

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _videosOnly = MutableStateFlow(false)
    val videosOnly: StateFlow<Boolean> = _videosOnly

    private val _statusFilter = MutableStateFlow(StatusFilter.ALL)
    val statusFilter: StateFlow<StatusFilter> = _statusFilter

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

    private val _statusById = MutableStateFlow<Map<Long, String>>(emptyMap())
    val statusById: StateFlow<Map<Long, String>> = _statusById

    fun refreshBackupStatus() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) {
                BackupDb.get(getApplication()).dao().all()
                    .associate { it.mediaStoreId to it.status }
            }
            _statusById.value = map
            applyView()
        }
    }

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
            refreshBackupStatus()
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

    fun openBackup() { _screen.value = Screen.BACKUP }

    fun backToGrid() { _screen.value = Screen.GRID }

    suspend fun loadDetails(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        DetailsLoader.load(getApplication(), item)
    }

    fun toggleSelect(id: Long) {
        val next = _selection.value.toMutableSet()
        if (!next.remove(id)) next.add(id)
        _selection.value = next
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Starts a manual backup run for the selected items only. */
    fun backUpSelection() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val app = getApplication<Application>()
        val wifiOnly = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("wifiOnly", true)
        BackupWorker.runNow(app, wifiOnly, ids)
        clearSelection()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyView()
    }

    fun toggleVideosOnly() {
        _videosOnly.value = !_videosOnly.value
        applyView()
    }

    fun setStatusFilter(filter: StatusFilter) {
        _statusFilter.value = filter
        applyView()
    }

    private fun applyView() {
        var view = allItems
        if (_favoritesOnly.value) view = view.filter { _favorites.value.contains(it.id) }
        if (_videosOnly.value) view = view.filter { it.isVideo }
        val query = _searchQuery.value.trim()
        if (query.isNotEmpty()) {
            view = view.filter { item ->
                item.name.contains(query, ignoreCase = true) ||
                    runCatching {
                        val day = java.time.Instant.ofEpochMilli(item.dateMillis)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        day.toString().startsWith(query) ||
                            day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
                                .contains(query, ignoreCase = true)
                    }.getOrDefault(false)
            }
        }
        if (_statusFilter.value != StatusFilter.ALL) {
            val map = _statusById.value
            view = view.filter { item ->
                when (_statusFilter.value) {
                    StatusFilter.BACKED_UP -> map[item.id] == BackupEntry.BACKED_UP
                    StatusFilter.CLOUD_ONLY -> map[item.id] == BackupEntry.CLOUD_ONLY
                    StatusFilter.PENDING ->
                        map[item.id] != BackupEntry.BACKED_UP && map[item.id] != BackupEntry.CLOUD_ONLY
                    StatusFilter.ALL -> true
                }
            }
        }
        _items.value = view.sortedWith(MediaSort.comparator(_sortField.value, _sortAscending.value))

        val map = _statusById.value
        _sectionCounts.value = mapOf(
            StatusFilter.ALL to allItems.size,
            StatusFilter.BACKED_UP to allItems.count { map[it.id] == BackupEntry.BACKED_UP },
            StatusFilter.PENDING to allItems.count {
                map[it.id] != BackupEntry.BACKED_UP && map[it.id] != BackupEntry.CLOUD_ONLY
            },
            StatusFilter.CLOUD_ONLY to allItems.count { map[it.id] == BackupEntry.CLOUD_ONLY }
        )
    }

    private fun loadFavorites(): Set<Long> =
        prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
}
