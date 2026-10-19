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

enum class ThemeMode { CLOUD_SKY, SYSTEM, LIGHT, DARK }
enum class Screen { GRID, VIEWER, SETTINGS, BACKUP, ALBUMS, TRASH, DUPLICATES, RESTORE, STORAGE_STATS }
enum class StatusFilter { ALL, BACKED_UP, PENDING, CLOUD_ONLY }

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var allItems: List<MediaItem> = emptyList()

    private val _bucketFilter = MutableStateFlow<String?>(null)
    val bucketFilter: StateFlow<String?> = _bucketFilter

    private val _bucketCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val bucketCounts: StateFlow<Map<String, Int>> = _bucketCounts

    fun setBucketFilter(bucket: String?) {
        _bucketFilter.value = bucket
        applyView()
    }

    // Trash: ids mapped to the time they were trashed; kept 30 days (app-level).
    private val _trashed = MutableStateFlow<Map<Long, Long>>(loadTrash())
    val trashed: StateFlow<Map<Long, Long>> = _trashed

    private val _trashItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val trashItems: StateFlow<List<MediaItem>> = _trashItems

    private fun loadTrash(): Map<Long, Long> =
        (prefs.getStringSet("trash", emptySet()) ?: emptySet()).mapNotNull {
            val parts = it.split(':')
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                val ts = parts[1].toLongOrNull()
                if (id != null && ts != null) id to ts else null
            } else null
        }.toMap()

    private fun saveTrash() {
        prefs.edit()
            .putStringSet("trash", _trashed.value.map { "${it.key}:${it.value}" }.toSet())
            .apply()
    }

    fun trashSelection() {
        val now = System.currentTimeMillis()
        val sel = _selection.value
        if (sel.isEmpty()) return
        _trashed.value = _trashed.value + sel.associateWith { now }
        saveTrash()
        clearSelection()
        refreshTrashItems()
        applyView()
    }

    fun trashIds(ids: Set<Long>) {
        val now = System.currentTimeMillis()
        _trashed.value = _trashed.value + ids.associateWith { now }
        saveTrash()
        clearSelection()
        refreshTrashItems()
        applyView()
        computeDuplicates()
    }

    fun restoreFromTrash(ids: Set<Long>) {
        _trashed.value = _trashed.value - ids
        saveTrash()
        refreshTrashItems()
        applyView()
    }

    fun removeFromTrashRecords(ids: Set<Long>) {
        _trashed.value = _trashed.value - ids
        saveTrash()
        refreshTrashItems()
    }

    fun isTrashExpired(id: Long): Boolean {
        val ts = _trashed.value[id] ?: return false
        return System.currentTimeMillis() - ts > TRASH_RETENTION_MS
    }

    private fun refreshTrashItems() {
        _trashItems.value = allItems.filter { _trashed.value.containsKey(it.id) }
    }

    // Duplicates: same byte size + same dimensions, no hashing needed.
    private val _duplicateGroups = MutableStateFlow<List<List<MediaItem>>>(emptyList())
    val duplicateGroups: StateFlow<List<List<MediaItem>>> = _duplicateGroups

    private fun computeDuplicates() {
        val source = allItems
        val trashed = _trashed.value
        viewModelScope.launch {
            val groups = withContext(Dispatchers.Default) {
                source
                    .filter { it.sizeBytes > 0 && !trashed.containsKey(it.id) }
                    .groupBy { "${it.sizeBytes}:${it.width}x${it.height}" }
                    .values
                    .filter { it.size > 1 }
                    .sortedByDescending { it.size }
            }
            _duplicateGroups.value = groups
        }
    }

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items

    // Grid entries (day/letter headers interleaved) built off the main
    // thread in applyView; building 40k+ entries during composition froze
    // the UI on large libraries.
    private val _entries = MutableStateFlow<List<GridEntry>>(emptyList())
    internal val entries: StateFlow<List<GridEntry>> = _entries

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
        runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "CLOUD_SKY")!!) }.getOrDefault(ThemeMode.SYSTEM)
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
            app.eightcloud.gallery.AppLog.log("media", "loaded " + result.size + " items")
            _bucketCounts.value = result.groupingBy { it.bucket.ifBlank { "Other" } }.eachCount()
            refreshTrashItems()
            computeDuplicates()
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
    fun openAlbums() { _screen.value = Screen.ALBUMS }
    fun openTrash() { _screen.value = Screen.TRASH }
    fun openDuplicates() { _screen.value = Screen.DUPLICATES }
    fun openRestore() { _screen.value = Screen.RESTORE }

    // Optional features, each switchable in Settings (default on).
    private val _featureToggles = MutableStateFlow(loadFeatureToggles())
    val featureToggles: StateFlow<Map<String, Boolean>> = _featureToggles

    private fun loadFeatureToggles(): Map<String, Boolean> =
        FEATURE_TOGGLES.associate { it.key to prefs.getBoolean(it.key, true) }

    fun setFeatureEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
        _featureToggles.value = _featureToggles.value + (key to enabled)
    }

    fun featureEnabled(key: String): Boolean = _featureToggles.value[key] != false

    // Storage stats, computed on demand when the screen opens.
    private val _storageStats = MutableStateFlow<StorageStats?>(null)
    val storageStats: StateFlow<StorageStats?> = _storageStats

    fun openStorageStats() {
        _screen.value = Screen.STORAGE_STATS
        if (_storageStats.value != null) return
        val source = allItems
        val trashed = _trashed.value
        val groups = _duplicateGroups.value
        viewModelScope.launch {
            _storageStats.value = withContext(Dispatchers.Default) {
                val active = source.filter { !trashed.containsKey(it.id) }
                val buckets = active.groupBy { it.bucket.ifBlank { "Other" } }
                    .map { (b, list) -> BucketStat(b, list.size, list.sumOf { it.sizeBytes }) }
                    .sortedByDescending { it.bytes }
                val biggest = active.sortedByDescending { it.sizeBytes }.take(20)
                val trashItems = source.filter { trashed.containsKey(it.id) }
                val dupSavings = groups.sumOf { g ->
                    g.sumOf { it.sizeBytes } - (g.firstOrNull()?.sizeBytes ?: 0L)
                }
                StorageStats(
                    totalCount = active.size,
                    totalBytes = active.sumOf { it.sizeBytes },
                    buckets = buckets,
                    biggest = biggest,
                    trashedCount = trashItems.size,
                    trashedBytes = trashItems.sumOf { it.sizeBytes },
                    duplicateSavingsBytes = dupSavings
                )
            }
        }
    }

    private val _appLock = MutableStateFlow(prefs.getBoolean("appLock", false))
    val appLock: StateFlow<Boolean> = _appLock

    // In-memory only: locking again happens automatically on process restart.
    private val _appUnlocked = MutableStateFlow(false)
    val appUnlocked: StateFlow<Boolean> = _appUnlocked

    fun setAppLock(enabled: Boolean) {
        _appLock.value = enabled
        prefs.edit().putBoolean("appLock", enabled).apply()
    }

    fun unlockApp() { _appUnlocked.value = true }

    /** Test hook: loads synthetic items without MediaStore (debug builds only). */
    fun loadSyntheticForTest(count: Int) {
        if (!app.eightcloud.gallery.BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        allItems = (1..count).map {
            MediaItem(
                id = it.toLong(),
                uri = "content://synthetic/$it",
                isVideo = it % 9 == 0,
                name = "SYN_$it.jpg",
                dateMillis = now - it * 3_600_000L,
                sizeBytes = 1_000_000L + it,
                width = 4000,
                height = 3000,
                durationMs = if (it % 9 == 0) 60_000L else 0L,
                bucket = "Synthetic",
                mimeType = "image/jpeg"
            )
        }
        _bucketCounts.value = allItems.groupingBy { it.bucket }.eachCount()
        refreshTrashItems()
        computeDuplicates()
        applyView()
        _loading.value = false
    }

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
        // Snapshot state, then run filter/sort/count off the main thread:
        // with ~40k items the full pass takes too long for the UI thread.
        val favorites = _favorites.value
        val favoritesOnly = _favoritesOnly.value
        val videosOnly = _videosOnly.value
        val query = _searchQuery.value.trim()
        val statusFilter = _statusFilter.value
        val bucketFilter = _bucketFilter.value
        val trashed = _trashed.value
        val field = _sortField.value
        val ascending = _sortAscending.value
        val statusMap = _statusById.value
        val source = allItems
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val base = if (trashed.isEmpty()) source else source.filter { !trashed.containsKey(it.id) }
                var view = base
                if (bucketFilter != null) view = view.filter { it.bucket == bucketFilter }
                if (favoritesOnly) view = view.filter { favorites.contains(it.id) }
                if (videosOnly) view = view.filter { it.isVideo }
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
                if (statusFilter != StatusFilter.ALL) {
                    view = view.filter { item ->
                        when (statusFilter) {
                            StatusFilter.BACKED_UP -> statusMap[item.id] == BackupEntry.BACKED_UP
                            StatusFilter.CLOUD_ONLY -> statusMap[item.id] == BackupEntry.CLOUD_ONLY
                            StatusFilter.PENDING ->
                                statusMap[item.id] != BackupEntry.BACKED_UP &&
                                    statusMap[item.id] != BackupEntry.CLOUD_ONLY
                            StatusFilter.ALL -> true
                        }
                    }
                }
                val sorted = view.sortedWith(MediaSort.comparator(field, ascending))
                val entries = buildEntries(sorted, field)
                val active = base
                val counts = mapOf(
                    StatusFilter.ALL to active.size,
                    StatusFilter.BACKED_UP to active.count { statusMap[it.id] == BackupEntry.BACKED_UP },
                    StatusFilter.PENDING to active.count {
                        statusMap[it.id] != BackupEntry.BACKED_UP &&
                            statusMap[it.id] != BackupEntry.CLOUD_ONLY
                    },
                    StatusFilter.CLOUD_ONLY to active.count { statusMap[it.id] == BackupEntry.CLOUD_ONLY }
                )
                Triple(sorted, counts, entries)
            }
            _items.value = result.first
            _sectionCounts.value = result.second
            _entries.value = result.third
        }
    }

    private fun loadFavorites(): Set<Long> =
        prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
}
