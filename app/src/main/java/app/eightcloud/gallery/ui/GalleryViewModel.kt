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
enum class Screen { GRID, VIEWER, SETTINGS, BACKUP, ALBUMS, TRASH, DUPLICATES, RESTORE, STORAGE_STATS, CLEANUP, SLIDESHOW, SECURE, EDIT, MAP, DOCUMENTS }
enum class StatusFilter { ALL, BACKED_UP, PENDING, CLOUD_ONLY }

data class SmartAlbum(val key: String, val title: String, val count: Int)

/** One face cluster shown in the People section. */
data class PersonAlbum(val id: Long, val title: String, val count: Int, val coverJpeg: ByteArray?)

internal fun monthTitle(yearMonth: String): String = runCatching {
    java.time.YearMonth.parse(yearMonth)
        .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
}.getOrDefault(yearMonth)

/** Confs for matching; legacy rows (no confs stored) get a neutral 0.70 per label. */
internal fun confsOf(entry: app.eightcloud.gallery.backup.MediaLabelEntry): String =
    if (entry.labelConfs.isNotBlank()) entry.labelConfs
    else entry.labels.split(",").filter { it.isNotBlank() }.joinToString(",") { "$it:0.70" }

internal fun isSensitiveItem(
    item: MediaItem,
    labelsById: Map<Long, app.eightcloud.gallery.backup.MediaLabelEntry>,
    overrides: Map<Long, Map<String, Boolean>>
): Boolean {
    overrides[item.id]?.get("cat_adult")?.let { excluded -> return !excluded }
    val entry = labelsById[item.id] ?: return false
    return entry.nsfwScore >= app.eightcloud.gallery.labels.Categories.NSFW_THRESHOLD
}

internal fun matchesSmartAlbum(
    item: MediaItem,
    key: String,
    labelsById: Map<Long, app.eightcloud.gallery.backup.MediaLabelEntry> = emptyMap(),
    overrides: Map<Long, Map<String, Boolean>> = emptyMap(),
    personPhotos: Map<Long, Set<Long>> = emptyMap()
): Boolean {
    val b = item.bucket.lowercase()
    if (key.startsWith("person:")) {
        val pid = key.removePrefix("person:").toLongOrNull() ?: return false
        return personPhotos[pid]?.contains(item.id) == true
    }
    if (key.startsWith("cat_")) {
        // Manual correction always wins over the scanner.
        overrides[item.id]?.get(key)?.let { excluded -> return !excluded }
        val entry = labelsById[item.id] ?: return false
        val cats = app.eightcloud.gallery.labels.Categories.forItem(
            faceCount = entry.faceCount,
            faceMaxRatio = entry.faceMaxRatio,
            labelConfs = confsOf(entry),
            ocrChars = entry.ocrChars,
            nsfwScore = entry.nsfwScore,
            isScreenshot = b.contains("screenshot"),
            cameraBucket = b.contains("camera") || b.contains("dcim")
        )
        return key in cats
    }
    return when {
        key == "screenshots" -> b.contains("screenshot")
        key == "videos" -> item.isVideo
        key == "camera" -> b.contains("camera") || b.contains("dcim")
        key == "memes" -> b.contains("meme")
        key.startsWith("month:") ->
            java.time.Instant.ofEpochMilli(item.dateMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")) ==
                key.removePrefix("month:")
        else -> true
    }
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var allItems: List<MediaItem> = emptyList()

    private val _bucketFilter = MutableStateFlow<String?>(prefs.getString("bucketFilter", null))
    val bucketFilter: StateFlow<String?> = _bucketFilter

    private val _bucketCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val bucketCounts: StateFlow<Map<String, Int>> = _bucketCounts

    fun setBucketFilter(bucket: String?) {
        _bucketFilter.value = bucket
        if (bucket != null) _smartFilter.value = null
        prefs.edit().putString("bucketFilter", bucket).putString("smartFilter", _smartFilter.value).apply()
        applyView()
    }

    // Smart albums (screenshots/videos/camera/memes) and by-month albums.
    private val _smartFilter = MutableStateFlow<String?>(prefs.getString("smartFilter", null))
    val smartFilter: StateFlow<String?> = _smartFilter

    fun setSmartFilter(key: String?) {
        _smartFilter.value = key
        if (key != null) _bucketFilter.value = null
        prefs.edit().putString("smartFilter", key).putString("bucketFilter", _bucketFilter.value).apply()
        applyView()
    }

    fun smartTitle(key: String): String = when {
        key.startsWith("cat_") -> app.eightcloud.gallery.labels.Categories.title(key)
        key.startsWith("person:") -> {
            val pid = key.removePrefix("person:").toLongOrNull()
            _personAlbums.value.firstOrNull { it.id == pid }?.title ?: "Person"
        }
        key.startsWith("month:") -> monthTitle(key.removePrefix("month:"))
        key == "screenshots" -> "Screenshots"
        key == "videos" -> "Videos"
        key == "camera" -> "Camera"
        key == "memes" -> "Memes"
        else -> key
    }

    private val _smartAlbums = MutableStateFlow<List<SmartAlbum>>(emptyList())
    val smartAlbums: StateFlow<List<SmartAlbum>> = _smartAlbums

    private val _monthAlbums = MutableStateFlow<List<SmartAlbum>>(emptyList())
    val monthAlbums: StateFlow<List<SmartAlbum>> = _monthAlbums

    // "On this day": same month-day in earlier years, newest first.
    private val _memories = MutableStateFlow<List<MediaItem>>(emptyList())
    val memories: StateFlow<List<MediaItem>> = _memories

    private fun computeAlbumsAndMemories() {
        val source = allItems
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val zone = java.time.ZoneId.systemDefault()
                val monthFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
                val smart = listOf(
                    SmartAlbum("screenshots", "Screenshots",
                        source.count { it.bucket.lowercase().contains("screenshot") }),
                    SmartAlbum("videos", "Videos", source.count { it.isVideo }),
                    SmartAlbum("camera", "Camera",
                        source.count { it.bucket.lowercase().let { b -> b.contains("camera") || b.contains("dcim") } }),
                    SmartAlbum("memes", "Memes",
                        source.count { it.bucket.lowercase().contains("meme") })
                ).filter { it.count > 0 }
                val months = source
                    .groupBy { java.time.Instant.ofEpochMilli(it.dateMillis).atZone(zone).format(monthFmt) }
                    .map { (m, l) -> SmartAlbum("month:$m", monthTitle(m), l.size) }
                    .sortedByDescending { it.key }
                val today = java.time.LocalDate.now(zone)
                val mem = source.filter {
                    val d = java.time.Instant.ofEpochMilli(it.dateMillis).atZone(zone).toLocalDate()
                    d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth &&
                        d.year < today.year
                }.sortedByDescending { it.dateMillis }.take(10)
                Triple(smart, months, mem)
            }
            _smartAlbums.value = result.first
            _monthAlbums.value = result.second
            _memories.value = result.third
        }
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

    private val _videosOnly = MutableStateFlow(prefs.getBoolean("videosOnly", false))
    val videosOnly: StateFlow<Boolean> = _videosOnly

    private val _statusFilter = MutableStateFlow(
        runCatching { StatusFilter.valueOf(prefs.getString("statusFilter", "ALL")!!) }.getOrDefault(StatusFilter.ALL)
    )
    val statusFilter: StateFlow<StatusFilter> = _statusFilter

    private val _favoritesOnly = MutableStateFlow(prefs.getBoolean("favoritesOnly", false))
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

    // MediaStore ids whose backup is stored as multiple Telegram messages.
    private val _splitIds = MutableStateFlow<Set<Long>>(emptySet())
    val splitIds: StateFlow<Set<Long>> = _splitIds

    private val _splitLargeFiles = MutableStateFlow(prefs.getBoolean("splitLargeFiles", true))
    val splitLargeFiles: StateFlow<Boolean> = _splitLargeFiles

    fun setSplitLargeFiles(on: Boolean) {
        _splitLargeFiles.value = on
        prefs.edit().putBoolean("splitLargeFiles", on).apply()
    }

    fun refreshBackupStatus() {
        viewModelScope.launch {
            val (map, splits) = withContext(Dispatchers.IO) {
                val all = BackupDb.get(getApplication()).dao().all()
                all.associate { it.mediaStoreId to it.status } to
                    all.filter { it.telegramMessageIds.contains(",") }
                        .map { it.mediaStoreId }.toSet()
            }
            _splitIds.value = splits
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
            _secureIds.value = withContext(Dispatchers.IO) { secureDao.ids() }.toSet()
            refreshSecureItems()
            _mediaLabels.value = withContext(Dispatchers.IO) {
                mediaLabelDao.all().associateBy { it.mediaStoreId }
            }
            computeCategoryAlbums()
            computeAlbumsAndMemories()
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
        prefs.edit().putBoolean("favoritesOnly", _favoritesOnly.value).apply()
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

    /** Test hook: reset every persisted view-state value to its default. */
    fun resetViewStateForTest() {
        _searchQuery.value = ""
        _favoritesOnly.value = false
        _videosOnly.value = false
        _statusFilter.value = StatusFilter.ALL
        _bucketFilter.value = null
        _smartFilter.value = null
        _sortField.value = SortField.DATE
        _sortAscending.value = false
        gridAnchorId = null
        gridAnchorOffset = 0
        applyView()
    }

    /** Photo currently open in the editor (session state lives in EditScreen). */
    var editItem: MediaItem? = null
        private set

    fun openEdit(item: MediaItem) {
        editItem = item
        _screen.value = Screen.EDIT
    }

    fun openMap() { _screen.value = Screen.MAP }

    fun openDocuments() { _screen.value = Screen.DOCUMENTS }

    // Map: EXIF GPS points, scanned incrementally in the background.
    private val _geoPoints = MutableStateFlow<List<app.eightcloud.gallery.data.GeoIndex.Point>>(emptyList())
    val geoPoints: StateFlow<List<app.eightcloud.gallery.data.GeoIndex.Point>> = _geoPoints
    private val _geoScanning = MutableStateFlow(false)
    val geoScanning: StateFlow<Boolean> = _geoScanning
    private var geoScanJob: kotlinx.coroutines.Job? = null

    // v0.7.0: on-device categorization (people/documents/memes/scenes).
    private val mediaLabelDao by lazy { BackupDb.get(getApplication()).mediaLabelDao() }
    private val _mediaLabels = MutableStateFlow<Map<Long, app.eightcloud.gallery.backup.MediaLabelEntry>>(emptyMap())
    val mediaLabels: StateFlow<Map<Long, app.eightcloud.gallery.backup.MediaLabelEntry>> = _mediaLabels
    private val _labelScanProgress = MutableStateFlow<Pair<Int, Int>?>(null) // scanned/total while running
    val labelScanProgress: StateFlow<Pair<Int, Int>?> = _labelScanProgress
    private var labelScanJob: kotlinx.coroutines.Job? = null

    private val _categoryOverrides = MutableStateFlow<Map<Long, Map<String, Boolean>>>(emptyMap())
    val categoryOverrides: StateFlow<Map<Long, Map<String, Boolean>>> = _categoryOverrides

    private var overridesLoaded = false

    private fun loadCategoryOverrides() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) {
                app.eightcloud.gallery.backup.BackupDb.get(getApplication())
                    .categoryOverrideDao().all()
            }
            _categoryOverrides.value = all.groupBy({ it.mediaStoreId }, { it.category to it.excluded })
                .mapValues { it.value.toMap() }
            overridesLoaded = true
            computeCategoryAlbums()
            applyView()
        }
    }

    /** Marks an item as "not this category" (excluded=true) or back to normal (null). */
    fun setCategoryOverride(mediaStoreId: Long, category: String, excluded: Boolean?) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = app.eightcloud.gallery.backup.BackupDb.get(getApplication()).categoryOverrideDao()
            if (excluded == null) dao.delete(mediaStoreId, category)
            else dao.upsert(
                app.eightcloud.gallery.backup.CategoryOverride(
                    mediaStoreId, category, excluded, System.currentTimeMillis()
                )
            )
            val all = dao.all()
            _categoryOverrides.value = all.groupBy({ it.mediaStoreId }, { it.category to it.excluded })
                .mapValues { it.value.toMap() }
            withContext(Dispatchers.Main) {
                computeCategoryAlbums()
                applyView()
            }
            rescoreClip()
        }
    }

    private var clipPromptsCache: app.eightcloud.gallery.labels.ClipPrompts? = null

    private fun clipPrompts(): app.eightcloud.gallery.labels.ClipPrompts =
        clipPromptsCache ?: app.eightcloud.gallery.labels.ClipPrompts.load(getApplication())
            .also { clipPromptsCache = it }

    /** Top-3 CLIP prompts for one item (info-sheet debug view); null when no embedding yet. */
    suspend fun clipTop(mediaStoreId: Long): List<Pair<String, Float>>? = withContext(Dispatchers.IO) {
        val row = app.eightcloud.gallery.backup.BackupDb.get(getApplication()).clipDao()
            .byIds(listOf(mediaStoreId)).firstOrNull() ?: return@withContext null
        clipPrompts().top(app.eightcloud.gallery.labels.ClipPrompts.fromBlob(row.embedding), 3)
    }

    /**
     * Recomputes the CLIP category shares from the stored embeddings. Photos that
     * look like one the user rejected ("Not this category") lose that category.
     */
    fun rescoreClip() {
        viewModelScope.launch(Dispatchers.IO) {
            val ClipPrompts = app.eightcloud.gallery.labels.ClipPrompts
            val db = app.eightcloud.gallery.backup.BackupDb.get(getApplication())
            val clipDao = db.clipDao()
            val prompts = clipPrompts()
            val rejected = _categoryOverrides.value
                .mapValues { e -> e.value.filterValues { it }.keys.toList() }
                .filterValues { it.isNotEmpty() }
            val negatives = mutableMapOf<String, MutableList<FloatArray>>()
            rejected.keys.toList().chunked(500).forEach { chunk ->
                clipDao.byIds(chunk).forEach { row ->
                    val emb = ClipPrompts.fromBlob(row.embedding)
                    rejected[row.mediaStoreId]?.forEach { cat ->
                        negatives.getOrPut(cat) { mutableListOf() }.add(emb)
                    }
                }
            }
            var after = Long.MIN_VALUE
            var changedTotal = 0
            while (true) {
                val page = clipDao.page(after, 1000)
                if (page.isEmpty()) break
                after = page.last().mediaStoreId
                val labels = _mediaLabels.value
                val updates = mutableListOf<app.eightcloud.gallery.backup.MediaLabelEntry>()
                for (row in page) {
                    val entry = labels[row.mediaStoreId] ?: continue
                    val scores = prompts.scores(ClipPrompts.fromBlob(row.embedding), negatives)
                    val merged = ClipPrompts.mergeIntoLabelConfs(entry.labelConfs, scores)
                    if (merged != entry.labelConfs) updates += entry.copy(labelConfs = merged)
                }
                if (updates.isNotEmpty()) {
                    updates.forEach { db.mediaLabelDao().upsert(it) }
                    _mediaLabels.value = _mediaLabels.value + updates.associateBy { it.mediaStoreId }
                    changedTotal += updates.size
                }
            }
            if (changedTotal > 0) {
                withContext(Dispatchers.Main) {
                    computeCategoryAlbums()
                    applyView()
                }
            }
        }
    }

    fun categoryEnabled(key: String): Boolean = prefs.getBoolean("cat_enabled_" + key, true)

    fun setCategoryEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean("cat_enabled_" + key, enabled).apply()
        computeCategoryAlbums()
    }

    private val _categoryAlbums = MutableStateFlow<List<SmartAlbum>>(emptyList())
    val categoryAlbums: StateFlow<List<SmartAlbum>> = _categoryAlbums

    private fun computeCategoryAlbums() {
        val labels = _mediaLabels.value
        val source = allItems
        viewModelScope.launch {
            val albums = withContext(Dispatchers.Default) {
                app.eightcloud.gallery.labels.Categories.ALL
                    .filter { categoryEnabled(it) }
                    .map { key ->
                        SmartAlbum(
                            key,
                            app.eightcloud.gallery.labels.Categories.title(key),
                            source.count { item -> matchesSmartAlbum(item, key, labels, _categoryOverrides.value) }
                        )
                    }
            }
            _categoryAlbums.value = albums
        }
    }

    // ----- People (on-device face grouping) -----

    private val _personAlbums = MutableStateFlow<List<PersonAlbum>>(emptyList())
    val personAlbums: StateFlow<List<PersonAlbum>> = _personAlbums
    private val _personPhotoIds = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    private val _faceScanProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val faceScanProgress: StateFlow<Pair<Int, Int>?> = _faceScanProgress
    private var faceScanJob: kotlinx.coroutines.Job? = null

    private suspend fun refreshPersons() {
        val db = app.eightcloud.gallery.backup.BackupDb.get(getApplication())
        val (persons, photos) = withContext(Dispatchers.IO) {
            db.personDao().all() to db.faceDao().allPersonPhotos()
        }
        val visibleIds = allItems.map { it.id }.toSet() - _secureIds.value - _trashed.value.keys
        val visiblePhotos = photos.filter { it.mediaStoreId in visibleIds }
        _personAlbums.value = persons.mapNotNull {
            val count = visiblePhotos.filter { photo -> photo.personId == it.id }.map { photo -> photo.mediaStoreId }.distinct().size
            if (count == 0) null else PersonAlbum(it.id, it.name ?: "Person " + it.id, count, it.coverJpeg)
        }
        _personPhotoIds.value = photos.groupBy({ it.personId }, { it.mediaStoreId })
            .mapValues { it.value.toSet() }
    }

    fun renamePerson(id: Long, name: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            app.eightcloud.gallery.backup.BackupDb.get(getApplication())
                .personDao().rename(id, name?.trim()?.ifBlank { null })
            refreshPersons()
            withContext(Dispatchers.Main) { applyView() }
        }
    }

    /**
     * Group faces into persons, incrementally and fully on-device. Images only;
     * scans items whose label scan found at least one face.
     */
    fun scanFaces() {
        if (faceScanJob?.isActive == true) return
        if (_featureToggles.value["feat_face_grouping"] == false) return
        faceScanJob = viewModelScope.launch(Dispatchers.IO) {
            val db = app.eightcloud.gallery.backup.BackupDb.get(getApplication())
            val grouper = app.eightcloud.gallery.labels.FaceGrouper(getApplication())
            if (_mediaLabels.value.isEmpty()) {
                _mediaLabels.value = withContext(Dispatchers.IO) {
                    db.mediaLabelDao().all().associateBy { it.mediaStoreId }
                }
            }
            refreshPersons()
            val failed = mutableSetOf<Long>()
            try {
            while (true) {
                if (_featureToggles.value["feat_face_grouping"] == false) break
                val labels = _mediaLabels.value
                val processed = withContext(Dispatchers.IO) {
                    db.faceScanDao().processedIds().toSet()
                }
                val candidates = allItems.filter {
                    !it.isVideo && (labels[it.id]?.faceCount ?: 0) > 0 && it.id !in processed &&
                        it.id !in failed && it.id !in _secureIds.value && it.id !in _trashed.value
                }
                if (candidates.isEmpty()) {
                    // The label scan may still be finding faces; wait for it.
                    if (labelScanJob?.isActive == true) {
                        kotlinx.coroutines.delay(3000)
                        continue
                    }
                    break
                }
                val total = processed.size + candidates.size
                _faceScanProgress.value = processed.size to total
                candidates.take(10).forEach { item ->
                    val bmp = withContext(Dispatchers.IO) {
                        grouper.loadBitmap(android.net.Uri.parse(item.uri), 1024)
                    }
                    if (bmp != null) {
                        try {
                            grouper.process(item.id, bmp)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed += item.id
                        } finally {
                            bmp.recycle()
                        }
                    } else {
                        failed += item.id
                    }
                }
                refreshPersons()
            }
            refreshPersons()
            withContext(Dispatchers.Main) { applyView() }
            } finally {
                grouper.close()
                _faceScanProgress.value = null
            }
        }
    }

    /** Incrementally analyze the library (images only for now), cached in Room. */
    fun scanLabels() {
        if (labelScanJob?.isActive == true) return
        labelScanJob = viewModelScope.launch {
            val scanner = app.eightcloud.gallery.labels.MediaScanner(getApplication())
            _mediaLabels.value = withContext(Dispatchers.IO) {
                mediaLabelDao.all().associateBy { it.mediaStoreId }
            }
            computeCategoryAlbums()
            if (!overridesLoaded) loadCategoryOverrides()
            val videoLabels = _featureToggles.value["feat_video_labels"] != false
            val failed = mutableSetOf<Long>() // don't retry failures within one run
            while (true) {
                val done = _mediaLabels.value
                val current = app.eightcloud.gallery.labels.MediaScanner.CURRENT_SCAN_VERSION
                val pending = allItems.filter {
                    (!it.isVideo || videoLabels) && it.id !in failed &&
                        (it.id !in done || (done[it.id]?.scanVersion ?: 0) < current)
                }
                if (pending.isEmpty()) break
                _labelScanProgress.value = (allItems.size - pending.size) to allItems.size
                // Big rescans only run while charging (default); small ones always run.
                while (pending.size > 200 && _scanOnlyWhileCharging.value && !isCharging()) {
                    kotlinx.coroutines.delay(30_000)
                }
                kotlinx.coroutines.delay(250) // throttle: keep the phone cool during long scans
                pending.take(25).forEach { item ->
                    val entry = runCatching {
                        if (item.isVideo) {
                            scanner.scanVideo(item.id, android.net.Uri.parse(item.uri))
                        } else {
                            scanner.scan(item.id, android.net.Uri.parse(item.uri))
                        }
                    }.getOrNull()
                    if (entry != null) {
                        _mediaLabels.value = _mediaLabels.value + (entry.mediaStoreId to entry)
                    } else {
                        failed += item.id
                    }
                }
                computeCategoryAlbums()
            }
            _labelScanProgress.value = null
            rescoreClip()
            if (_autoSecureSensitive.value) moveToSecure(sensitiveNotSecure())
            applyView()
        }
    }

    fun scanGeo() {
        if (geoScanJob?.isActive == true) return
        geoScanJob = viewModelScope.launch {
            _geoScanning.value = true
            _geoPoints.value = app.eightcloud.gallery.data.GeoIndex.located(getApplication())
            while (true) {
                val done = app.eightcloud.gallery.data.GeoIndex.scanBatch(getApplication(), allItems)
                if (done > 0) {
                    _geoPoints.value = app.eightcloud.gallery.data.GeoIndex.located(getApplication())
                } else break
            }
            _geoScanning.value = false
        }
    }

    /**
     * Rename the real file through MediaStore and update the cloud index
     * in place (the content hash does not change, so nothing re-uploads).
     * On Android 11+ the system may show a write-consent dialog first.
     * onDone(success, errorMessage).
     */
    fun renameItem(
        item: MediaItem,
        newDisplayName: String,
        onNeedConsent: (android.content.IntentSender) -> Unit,
        onDone: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, newDisplayName)
                    }
                    val rows = getApplication<android.app.Application>().contentResolver
                        .update(android.net.Uri.parse(item.uri), values, null, null)
                    if (rows <= 0) throw IllegalStateException("MediaStore refused the rename")
                }.recoverCatching { e ->
                    val rse = e as? android.app.RecoverableSecurityException
                        ?: throw e
                    withContext(Dispatchers.Main) {
                        onNeedConsent(rse.userAction.actionIntent.intentSender)
                    }
                    throw RenamingNeedsConsent()
                }
            }
            result.onSuccess {
                allItems = allItems.map { if (it.id == item.id) it.copy(name = newDisplayName) else it }
                // Keep the backup index in sync without re-uploading.
                runCatching {
                    val db = BackupDb.get(getApplication()).dao()
                    db.byMediaStoreId(item.id)?.let { entry ->
                        db.upsert(entry.copy(name = newDisplayName))
                    }
                }
                applyView()
                onDone(true, null)
            }.onFailure { e ->
                if (e !is RenamingNeedsConsent) onDone(false, e.message ?: "Rename failed")
            }
        }
    }

    private class RenamingNeedsConsent : Exception()

    fun openViewer(index: Int) {
        _viewerStartIndex.value = index.coerceAtLeast(0)
        _screen.value = Screen.VIEWER
    }

    fun openSettings() { _screen.value = Screen.SETTINGS }

    // Slideshow preferences + screen.
    private val _slideshowOrder = MutableStateFlow(prefs.getString("slideshowOrder", "DATE")!!)
    val slideshowOrder: StateFlow<String> = _slideshowOrder
    private val _slideshowSpeedSec = MutableStateFlow(prefs.getInt("slideshowSpeedSec", 4))
    val slideshowSpeedSec: StateFlow<Int> = _slideshowSpeedSec
    private val _slideshowLoop = MutableStateFlow(prefs.getBoolean("slideshowLoop", true))
    val slideshowLoop: StateFlow<Boolean> = _slideshowLoop
    private val _slideshowMusicUri = MutableStateFlow(prefs.getString("slideshowMusic", null))
    val slideshowMusicUri: StateFlow<String?> = _slideshowMusicUri

    fun setSlideshowOrder(order: String) {
        _slideshowOrder.value = order
        prefs.edit().putString("slideshowOrder", order).apply()
    }

    fun setSlideshowSpeedSec(sec: Int) {
        _slideshowSpeedSec.value = sec.coerceIn(2, 15)
        prefs.edit().putInt("slideshowSpeedSec", _slideshowSpeedSec.value).apply()
    }

    fun setSlideshowLoop(loop: Boolean) {
        _slideshowLoop.value = loop
        prefs.edit().putBoolean("slideshowLoop", loop).apply()
    }

    fun setSlideshowMusic(uri: String?) {
        _slideshowMusicUri.value = uri
        if (uri == null) prefs.edit().remove("slideshowMusic").apply()
        else prefs.edit().putString("slideshowMusic", uri).apply()
    }

    // Last visible grid position, anchored by item id so it survives list changes.
    var gridAnchorId: Long? = prefs.getLong("gridAnchorId", -1L).takeIf { it != -1L }
        private set
    var gridAnchorOffset: Int = prefs.getInt("gridAnchorOffset", 0)
        private set

    fun saveGridAnchor(id: Long?, offset: Int) {
        gridAnchorId = id
        gridAnchorOffset = offset
        prefs.edit()
            .putLong("gridAnchorId", id ?: -1L)
            .putInt("gridAnchorOffset", offset)
            .apply()
    }

    private val _slideshowVideoMode = MutableStateFlow(prefs.getString("slideshowVideos", "PLAY")!!)
    val slideshowVideoMode: StateFlow<String> = _slideshowVideoMode

    fun setSlideshowVideoMode(mode: String) {
        _slideshowVideoMode.value = mode
        prefs.edit().putString("slideshowVideos", mode).apply()
    }

    fun openSlideshow() { _screen.value = Screen.SLIDESHOW }
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
        if (key == "feat_face_grouping") {
            if (enabled) scanFaces() else faceScanJob?.cancel()
        }
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

    // Secure folder: ids hidden from the main grid, stored in Room (backup-index.db).
    private val secureDao by lazy { BackupDb.get(getApplication()).secureDao() }
    private val _secureIds = MutableStateFlow<Set<Long>>(emptySet())
    val secureIds: StateFlow<Set<Long>> = _secureIds
    private val _secureItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val secureItems: StateFlow<List<MediaItem>> = _secureItems
    private val _secureUnlocked = MutableStateFlow(false)
    val secureUnlocked: StateFlow<Boolean> = _secureUnlocked

    fun unlockSecure() { _secureUnlocked.value = true }

    fun openSecureFolder() { _screen.value = Screen.SECURE }

    fun moveSelectionToSecure() {
        moveToSecure(_selection.value)
        clearSelection()
    }

    fun moveToSecure(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val rows = ids.mapNotNull { id ->
                allItems.firstOrNull { it.id == id }
                    ?.let { app.eightcloud.gallery.backup.SecureEntry(id, it.name, it.isVideo, now) }
            }
            withContext(Dispatchers.IO) { secureDao.upsertAll(rows) }
            _secureIds.value = _secureIds.value + rows.map { it.mediaStoreId }
            refreshSecureItems()
            applyView()
        }
    }

    fun removeFromSecure(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { secureDao.delete(ids.toList()) }
            _secureIds.value = _secureIds.value - ids
            refreshSecureItems()
            applyView()
        }
    }

    private fun refreshSecureItems() {
        val ids = _secureIds.value
        _secureItems.value = allItems.filter { it.id in ids }.sortedByDescending { it.dateMillis }
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

    /** Re-locks the app (screen off). No-op state change when lock is off. */
    fun lockApp() { _appUnlocked.value = false }

    private val _scanOnlyWhileCharging = MutableStateFlow(prefs.getBoolean("scanOnlyWhileCharging", true))
    val scanOnlyWhileCharging: StateFlow<Boolean> = _scanOnlyWhileCharging

    fun setScanOnlyWhileCharging(enabled: Boolean) {
        _scanOnlyWhileCharging.value = enabled
        prefs.edit().putBoolean("scanOnlyWhileCharging", enabled).apply()
    }

    private fun isCharging(): Boolean {
        val intent = getApplication<android.app.Application>().registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        return (intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private val _hideSensitive = MutableStateFlow(prefs.getBoolean("hideSensitive", true))
    val hideSensitive: StateFlow<Boolean> = _hideSensitive

    fun setHideSensitive(enabled: Boolean) {
        _hideSensitive.value = enabled
        prefs.edit().putBoolean("hideSensitive", enabled).apply()
        applyView()
    }

    private val _autoSecureSensitive = MutableStateFlow(prefs.getBoolean("autoSecureSensitive", false))
    val autoSecureSensitive: StateFlow<Boolean> = _autoSecureSensitive

    /** Ids of Sensitive items that are not in the Secure folder or trash yet. */
    fun sensitiveNotSecure(): Set<Long> {
        val labels = _mediaLabels.value
        val overrides = _categoryOverrides.value
        return allItems.filter {
            it.id !in _secureIds.value && it.id !in _trashed.value &&
                isSensitiveItem(it, labels, overrides)
        }.map { it.id }.toSet()
    }

    /** Settings asks the user first, then calls this with the answer. */
    fun setAutoSecureSensitive(enabled: Boolean, moveExisting: Boolean) {
        _autoSecureSensitive.value = enabled
        prefs.edit().putBoolean("autoSecureSensitive", enabled).apply()
        if (enabled && moveExisting) moveToSecure(sensitiveNotSecure())
    }

    private val _privacyScreen = MutableStateFlow(prefs.getBoolean("privacyScreen", false))
    val privacyScreen: StateFlow<Boolean> = _privacyScreen

    fun setPrivacyScreen(enabled: Boolean) {
        _privacyScreen.value = enabled
        prefs.edit().putBoolean("privacyScreen", enabled).apply()
    }

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

    fun backToGrid() {
        _screen.value = Screen.GRID
        // Cheap sync-back: cleanup mode marks trash without refreshing.
        refreshTrashItems()
        applyView()
    }

    // Swipe-to-sort cleanup: oldest first, trash committed in batches by the
    // trash store itself (SharedPreferences), view refresh deferred to exit.
    private val _cleanupQueue = MutableStateFlow<List<MediaItem>>(emptyList())
    val cleanupQueue: StateFlow<List<MediaItem>> = _cleanupQueue
    private val _cleanupIndex = MutableStateFlow(0)
    val cleanupIndex: StateFlow<Int> = _cleanupIndex
    private val _cleanupTrashedCount = MutableStateFlow(0)
    val cleanupTrashedCount: StateFlow<Int> = _cleanupTrashedCount
    private val cleanupHistory = ArrayDeque<Pair<Long, Boolean>>()

    fun openCleanup() {
        _cleanupQueue.value = allItems
            .filter { !_trashed.value.containsKey(it.id) }
            .sortedBy { it.dateMillis }
        _cleanupIndex.value = 0
        _cleanupTrashedCount.value = 0
        cleanupHistory.clear()
        _screen.value = Screen.CLEANUP
    }

    fun cleanupKeepCurrent() {
        val item = _cleanupQueue.value.getOrNull(_cleanupIndex.value) ?: return
        cleanupHistory.addLast(item.id to false)
        _cleanupIndex.value++
    }

    fun cleanupTrashCurrent() {
        val item = _cleanupQueue.value.getOrNull(_cleanupIndex.value) ?: return
        _trashed.value = _trashed.value + (item.id to System.currentTimeMillis())
        saveTrash()
        _cleanupTrashedCount.value++
        cleanupHistory.addLast(item.id to true)
        _cleanupIndex.value++
    }

    fun cleanupUndo() {
        val last = cleanupHistory.removeLastOrNull() ?: return
        if (last.second) {
            _trashed.value = _trashed.value - last.first
            saveTrash()
            _cleanupTrashedCount.value--
        }
        _cleanupIndex.value--
    }

    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing

    /** Downloads + decrypts the cloud copy of a backed-up item, then hands the cache file over. */
    fun shareFromCloud(item: MediaItem, onReady: (java.io.File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _sharing.value = true
            try {
                val td = app.eightcloud.gallery.telegram.TdClient.get(getApplication())
                td.start()
                if (td.authState !is org.drinkless.tdlib.TdApi.AuthorizationStateReady) {
                    onError("Telegram backup is not connected")
                    return@launch
                }
                val engine = app.eightcloud.gallery.backup.BackupEngine(getApplication())
                val entry = engine.entryFor(item.id)
                if (entry == null ||
                    (entry.status != app.eightcloud.gallery.backup.BackupEntry.BACKED_UP &&
                        entry.status != app.eightcloud.gallery.backup.BackupEntry.CLOUD_ONLY)
                ) {
                    onError("No cloud copy of this item")
                    return@launch
                }
                onReady(engine.decryptToCache(entry))
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            } finally {
                _sharing.value = false
            }
        }
    }

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
        prefs.edit().putBoolean("videosOnly", _videosOnly.value).apply()
        applyView()
    }

    fun setStatusFilter(filter: StatusFilter) {
        _statusFilter.value = filter
        prefs.edit().putString("statusFilter", filter.name).apply()
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
        val smart = _smartFilter.value
        val secure = _secureIds.value
        val labelSnapshot = _mediaLabels.value
        val overrideSnapshot = _categoryOverrides.value
        val memoriesEnabled = _featureToggles.value["feat_on_this_day"] != false
        val hideSensitive = _hideSensitive.value
        val memories = if (hideSensitive) {
            _memories.value.filter { !isSensitiveItem(it, labelSnapshot, overrideSnapshot) }
        } else _memories.value
        val source = allItems
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                var base = if (trashed.isEmpty()) source else source.filter { !trashed.containsKey(it.id) }
                if (secure.isNotEmpty()) base = base.filter { it.id !in secure }
                if (hideSensitive && smart != "cat_adult") {
                    base = base.filter { !isSensitiveItem(it, labelSnapshot, overrideSnapshot) }
                }
                var view = base
                if (bucketFilter != null) view = view.filter { it.bucket == bucketFilter }
                if (smart != null) view = view.filter {
                    matchesSmartAlbum(it, smart, labelSnapshot, overrideSnapshot, _personPhotoIds.value)
                }
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
                var entries = buildEntries(sorted, field)
                val unfiltered = bucketFilter == null && smart == null && query.isEmpty() &&
                    statusFilter == StatusFilter.ALL && !favoritesOnly && !videosOnly
                if (field == SortField.DATE && unfiltered && memoriesEnabled && memories.isNotEmpty()) {
                    entries = listOf(GridEntry.Memories(memories)) + entries
                }
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
