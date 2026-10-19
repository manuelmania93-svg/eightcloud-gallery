package app.eightcloud.gallery.ui

import androidx.compose.foundation.background
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eightcloud.gallery.data.DetailsLoader
import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.MediaSort
import app.eightcloud.gallery.backup.BackupEntry
import app.eightcloud.gallery.data.SortField
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private sealed class GridEntry(val key: String) {
    class Header(val title: String, var count: Int = 0) : GridEntry("header-$title")
    class Media(val item: MediaItem) : GridEntry("media-${item.id}")
}

private fun buildEntries(items: List<MediaItem>, field: SortField): List<GridEntry> {
    val entries = ArrayList<GridEntry>(items.size + 64)
    var lastLabel: String? = null
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    for (item in items) {
        val label: String? = when (field) {
            SortField.DATE -> {
                val day = Instant.ofEpochMilli(item.dateMillis).atZone(zone).toLocalDate()
                when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(dayFormatter)
                }
            }
            SortField.NAME -> MediaSort.letterLabel(item.name)
            SortField.SIZE -> null
        }
        if (label != null && label != lastLabel) {
            entries.add(GridEntry.Header(label))
            lastLabel = label
        }
        if (label != null) (entries.last() as? GridEntry.Header)?.let { it.count++ }
        entries.add(GridEntry.Media(item))
    }
    return entries
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GridScreen(viewModel: GalleryViewModel) {
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val sectionCounts by viewModel.sectionCounts.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val videosOnly by viewModel.videosOnly.collectAsState()
    var searchActive by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        viewModel.clearSelection()
        viewModel.loadMedia()
    }
    val favorites by viewModel.favorites.collectAsState()
    val statusById by viewModel.statusById.collectAsState()
    val bucketFilter by viewModel.bucketFilter.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshBackupStatus() }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var zoomAccumulator by remember { mutableStateOf(1f) }

    val entries = remember(items, sortField) { buildEntries(items, sortField) }
    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.backUpSelection() }) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = "Back up selected")
                        }
                        IconButton(onClick = {
                            val uris = items.filter { it.id in selection }.map { Uri.parse(it.uri) }
                            if (uris.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "*/*"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share selected")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete local copies")
                        }
                    }
                )
            } else {
            val sortLabel = when (sortField) {
                SortField.DATE -> "Date"
                SortField.NAME -> "Name"
                SortField.SIZE -> "Size"
            }
            SkyHeader(
                sortLabel = sortLabel,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchChange = { viewModel.setSearchQuery(it) },
                onToggleSearch = {
                    if (searchActive) viewModel.setSearchQuery("")
                    searchActive = !searchActive
                },
                favoritesOnly = favoritesOnly,
                onToggleFavorites = { viewModel.toggleFavoritesOnly() },
                sortMenuOpen = sortMenuOpen,
                onSortMenuOpen = { sortMenuOpen = it },
                sortField = sortField,
                sortAscending = sortAscending,
                onSort = { f, a -> viewModel.setSort(f, a); sortMenuOpen = false }
            )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (bucketFilter != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clickable { viewModel.setBucketFilter(null) }
                ) {
                    Text(
                        text = "Album: $bucketFilter  ✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            StatusFilterRow(
                selected = statusFilter,
                counts = sectionCounts,
                favoritesOnly = favoritesOnly,
                videosOnly = videosOnly,
                onSelect = { viewModel.setStatusFilter(it) },
                onToggleFavorites = { viewModel.toggleFavoritesOnly() },
                onToggleVideos = { viewModel.toggleVideosOnly() }
            )
            Box(modifier = Modifier.weight(1f)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                items.isEmpty() -> Text(
                    text = if (favoritesOnly) "No favorites yet" else if (statusFilter != StatusFilter.ALL) "Nothing in this section" else "No photos or videos yet",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(columns) {
                            // Pinch-to-zoom only: consume only when 2+ fingers are
                            // down, so single-finger scrolling is never eaten.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    if (event.changes.count { it.pressed } > 1) {
                                        val zoom = event.calculateZoom()
                                        zoomAccumulator *= zoom
                                        if (zoomAccumulator > 1.3f && columns > MediaSort.MIN_COLUMNS) {
                                            viewModel.setColumns(columns - 1)
                                            zoomAccumulator = 1f
                                        } else if (zoomAccumulator < 0.77f && columns < MediaSort.MAX_COLUMNS) {
                                            viewModel.setColumns(columns + 1)
                                            zoomAccumulator = 1f
                                        }
                                        event.changes.forEach {
                                            if (it.position != it.previousPosition) it.consume()
                                        }
                                    }
                                }
                            }
                        },
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(entries, key = { it.key }, contentType = {
                        when (it) {
                            is GridEntry.Header -> "header"
                            is GridEntry.Media -> "media"
                        }
                    }, span = {
                        when (it) {
                            is GridEntry.Header -> GridItemSpan(maxLineSpan)
                            is GridEntry.Media -> GridItemSpan(1)
                        }
                    }) { entry ->
                        when (entry) {
                            is GridEntry.Header -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "${entry.count} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is GridEntry.Media -> MediaThumb(
                                item = entry.item,
                                status = statusById[entry.item.id],
                                isFavorite = favorites.contains(entry.item.id),
                                isSelected = selection.contains(entry.item.id),
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        viewModel.toggleSelect(entry.item.id)
                                    } else {
                                        val index = items.indexOfFirst { it.id == entry.item.id }
                                        if (index >= 0) viewModel.openViewer(index)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelect(entry.item.id) }
                            )
                        }
                    }
                }
            }
            if (entries.size > 30) {
                FastScrollBar(gridState, entries, sortField)
            }
            }
        }
    }

    if (showDeleteDialog) {
        val deletable = items.filter { it.id in selection && statusById[it.id] == BackupEntry.BACKED_UP }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Free up space") },
            text = {
                Text(
                    if (deletable.isEmpty())
                        "None of the selected items are verified in your cloud backup yet, so nothing can be deleted."
                    else
                        "Delete ${deletable.size} local copies? All of them are verified in your cloud backup." +
                            if (deletable.size < selection.size) " (${selection.size - deletable.size} selected items are not backed up and will be kept.)" else ""
                )
            },
            confirmButton = {
                TextButton(
                    enabled = deletable.isNotEmpty(),
                    onClick = {
                        showDeleteDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val request = MediaStore.createDeleteRequest(
                                context.contentResolver,
                                deletable.map { Uri.parse(it.uri) }
                            )
                            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        } else {
                            Toast.makeText(context, "Needs Android 11 or newer", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FastScrollBar(gridState: LazyGridState, entries: List<GridEntry>, field: SortField) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(28.dp)
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val thumbHeight = 48.dp
        val trackHeightPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val total = entries.size
        var dragOffsetPx by remember { mutableStateOf<Float?>(null) }

        val fraction: Float = dragOffsetPx?.let { (it / trackHeightPx).coerceIn(0f, 1f) }
            ?: if (total <= 1) 0f
            else (gridState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        val thumbOffsetDp = with(density) { (fraction * trackHeightPx).toDp() }
        val dragging = dragOffsetPx != null

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbOffsetDp)
                .padding(end = 4.dp)
                .width(6.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 0.9f else 0.45f))
                .pointerInput(total, trackHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { dragOffsetPx = fraction * trackHeightPx },
                        onDragEnd = { dragOffsetPx = null },
                        onDragCancel = { dragOffsetPx = null },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val next = ((dragOffsetPx ?: 0f) + dragAmount).coerceIn(0f, trackHeightPx)
                            dragOffsetPx = next
                            val index = ((next / trackHeightPx) * (total - 1))
                                .roundToInt()
                                .coerceIn(0, total - 1)
                            scope.launch { gridState.scrollToItem(index) }
                        }
                    )
                }
        )

        if (dragging) {
            val label = bubbleLabelOf(entries.getOrNull(gridState.firstVisibleItemIndex), field)
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(y = thumbOffsetDp)
                    .padding(end = 18.dp)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun bubbleLabelOf(entry: GridEntry?, field: SortField): String = when (entry) {
    is GridEntry.Header -> entry.title
    is GridEntry.Media -> when (field) {
        SortField.DATE -> Instant.ofEpochMilli(entry.item.dateMillis)
            .atZone(ZoneId.systemDefault()).toLocalDate().format(fullDateFormatter)
        SortField.SIZE -> MediaSort.sizeLabel(entry.item.sizeBytes)
        SortField.NAME -> MediaSort.letterLabel(entry.item.name)
    }
    null -> ""
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumb(
    item: MediaItem,
    status: String?,
    isFavorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(item.uri)
                .size(512, 512)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(26.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val (badgeIcon, badgeTint, badgeDesc) = when (status) {
                BackupEntry.BACKED_UP -> Triple(Icons.Filled.CloudDone, Color(0xFF2E86D4), "Backed up")
                BackupEntry.CLOUD_ONLY -> Triple(Icons.Filled.CloudDownload, Color(0xFF41719E), "Cloud only")
                BackupEntry.MISSING_IN_CLOUD -> Triple(Icons.Filled.Warning, Color(0xFFE2A455), "Missing in cloud")
                else -> Triple(Icons.Filled.Phone, Color(0xFF8AA0B4), "Local only")
            }
            Icon(
                imageVector = badgeIcon,
                contentDescription = badgeDesc,
                tint = badgeTint,
                modifier = Modifier.size(15.dp)
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            )
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .size(22.dp)
            )
        }
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = Color(0xFFE2557A),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(18.dp)
            )
        }
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = DetailsLoader.formatDuration(item.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudPuff(modifier: Modifier = Modifier, alpha: Float = 0.85f) {
    val c = Color.White.copy(alpha = alpha)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Box(modifier = Modifier.size(22.dp).background(c, CircleShape))
        Box(modifier = Modifier.size(34.dp).offset(y = (-10).dp).background(c, CircleShape))
        Box(modifier = Modifier.size(22.dp).background(c, CircleShape))
    }
}

@Composable
private fun SkyHeader(
    sortLabel: String,
    searchActive: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    favoritesOnly: Boolean,
    onToggleFavorites: () -> Unit,
    sortMenuOpen: Boolean,
    onSortMenuOpen: (Boolean) -> Unit,
    sortField: SortField,
    sortAscending: Boolean,
    onSort: (SortField, Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5FA9E8), Color(0xFF9CCDF3), Color(0xFFEDF7FF))
                )
            )
            .statusBarsPadding()
            .padding(bottom = 10.dp)
    ) {
        CloudPuff(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
            alpha = 0.55f
        )
        CloudPuff(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 120.dp, top = 2.dp),
            alpha = 0.35f
        )
        if (searchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search name or date (2026-10)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )
                IconButton(onClick = onToggleSearch) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search", tint = Color(0xFF0A3A66))
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                    Text(
                        text = "8",
                        color = Color(0xFF2E86D4),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "8CloudGallery",
                    color = Color(0xFF0A3A66),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color(0xFF0A3A66))
                }
                IconButton(onClick = onToggleFavorites) {
                    Icon(
                        imageVector = if (favoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorites filter",
                        tint = if (favoritesOnly) Color(0xFFE2557A) else Color(0xFF0A3A66)
                    )
                }
                Box {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(50),
                        shadowElevation = 2.dp,
                        modifier = Modifier.clickable { onSortMenuOpen(true) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SwapVert,
                                contentDescription = null,
                                tint = Color(0xFF2E86D4),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Sort: $sortLabel",
                                color = Color(0xFF0A3A66),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Sort options",
                                tint = Color(0xFF0A3A66),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { onSortMenuOpen(false) }) {
                        SortOption("Date (newest first)", SortField.DATE, false, sortField, sortAscending) {
                            onSort(SortField.DATE, false)
                        }
                        SortOption("Date (oldest first)", SortField.DATE, true, sortField, sortAscending) {
                            onSort(SortField.DATE, true)
                        }
                        SortOption("Size (largest first)", SortField.SIZE, false, sortField, sortAscending) {
                            onSort(SortField.SIZE, false)
                        }
                        SortOption("Size (smallest first)", SortField.SIZE, true, sortField, sortAscending) {
                            onSort(SortField.SIZE, true)
                        }
                        SortOption("Name (A-Z)", SortField.NAME, true, sortField, sortAscending) {
                            onSort(SortField.NAME, true)
                        }
                        SortOption("Name (Z-A)", SortField.NAME, false, sortField, sortAscending) {
                            onSort(SortField.NAME, false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortOption(
    label: String,
    field: SortField,
    ascending: Boolean,
    currentField: SortField,
    currentAscending: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(if (field == currentField && ascending == currentAscending) "$label  ✓" else label)
        },
        onClick = onClick
    )
}

@Composable
private fun StatusFilterRow(
    selected: StatusFilter,
    counts: Map<StatusFilter, Int>,
    favoritesOnly: Boolean,
    videosOnly: Boolean,
    onSelect: (StatusFilter) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleVideos: () -> Unit
) {
    val options = listOf(
        StatusFilter.ALL to "All",
        StatusFilter.BACKED_UP to "Backed up",
        StatusFilter.PENDING to "Pending",
        StatusFilter.CLOUD_ONLY to "Cloud only"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((filter, label) in options) {
            val isSelected = filter == selected
            val count = counts[filter] ?: 0
            Surface(
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(filter) }
            ) {
                Text(
                    text = "$label ($count)",
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FilterChip(label = "Favorites", selected = favoritesOnly, onClick = onToggleFavorites)
        FilterChip(label = "Videos", selected = videosOnly, onClick = onToggleVideos)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
