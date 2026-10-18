package app.eightcloud.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
    class Header(val title: String) : GridEntry("header-$title")
    class Media(val item: MediaItem) : GridEntry("media-${item.id}")
}

private fun buildEntries(items: List<MediaItem>, field: SortField): List<GridEntry> {
    val entries = ArrayList<GridEntry>(items.size + 12)
    var lastLabel: String? = null
    val zone = ZoneId.systemDefault()
    for (item in items) {
        val label: String? = when (field) {
            SortField.DATE -> MediaSort.monthLabel(
                Instant.ofEpochMilli(item.dateMillis).atZone(zone).toLocalDate()
            )
            SortField.NAME -> MediaSort.letterLabel(item.name)
            SortField.SIZE -> null
        }
        if (label != null && label != lastLabel) {
            entries.add(GridEntry.Header(label))
            lastLabel = label
        }
        entries.add(GridEntry.Media(item))
    }
    return entries
}

@Composable
fun GridScreen(viewModel: GalleryViewModel) {
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val statusById by viewModel.statusById.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshBackupStatus() }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var zoomAccumulator by remember { mutableStateOf(1f) }

    val entries = remember(items, sortField) { buildEntries(items, sortField) }
    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("8CloudGallery") },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavoritesOnly() }) {
                        Icon(
                            imageVector = if (favoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorites filter",
                            tint = if (favoritesOnly) Color(0xFFE2557A) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.List, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortOption("Date (newest first)", SortField.DATE, false, sortField, sortAscending) {
                                viewModel.setSort(SortField.DATE, false); sortMenuOpen = false
                            }
                            SortOption("Date (oldest first)", SortField.DATE, true, sortField, sortAscending) {
                                viewModel.setSort(SortField.DATE, true); sortMenuOpen = false
                            }
                            SortOption("Size (largest first)", SortField.SIZE, false, sortField, sortAscending) {
                                viewModel.setSort(SortField.SIZE, false); sortMenuOpen = false
                            }
                            SortOption("Size (smallest first)", SortField.SIZE, true, sortField, sortAscending) {
                                viewModel.setSort(SortField.SIZE, true); sortMenuOpen = false
                            }
                            SortOption("Name (A-Z)", SortField.NAME, true, sortField, sortAscending) {
                                viewModel.setSort(SortField.NAME, true); sortMenuOpen = false
                            }
                            SortOption("Name (Z-A)", SortField.NAME, false, sortField, sortAscending) {
                                viewModel.setSort(SortField.NAME, false); sortMenuOpen = false
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                items.isEmpty() -> Text(
                    text = if (favoritesOnly) "No favorites yet" else "No photos or videos yet",
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
                            detectTransformGestures { _, _, zoom, _ ->
                                zoomAccumulator *= zoom
                                if (zoomAccumulator > 1.3f && columns > MediaSort.MIN_COLUMNS) {
                                    viewModel.setColumns(columns - 1)
                                    zoomAccumulator = 1f
                                } else if (zoomAccumulator < 0.77f && columns < MediaSort.MAX_COLUMNS) {
                                    viewModel.setColumns(columns + 1)
                                    zoomAccumulator = 1f
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
                            is GridEntry.Header -> Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                            is GridEntry.Media -> MediaThumb(
                                item = entry.item,
                                status = statusById[entry.item.id],
                                isFavorite = favorites.contains(entry.item.id),
                                onClick = {
                                    val index = items.indexOfFirst { it.id == entry.item.id }
                                    if (index >= 0) viewModel.openViewer(index)
                                }
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
@Composable
private fun MediaThumb(item: MediaItem, status: String?, isFavorite: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
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
                .align(Alignment.TopStart)
                .padding(5.dp)
                .size(20.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val (badgeIcon, badgeTint, badgeDesc) = when (status) {
                BackupEntry.BACKED_UP -> Triple(Icons.Filled.Check, Color(0xFF7BE3A8), "Backed up")
                BackupEntry.CLOUD_ONLY -> Triple(Icons.Filled.KeyboardArrowDown, Color(0xFF9CC8EC), "Cloud only")
                BackupEntry.MISSING_IN_CLOUD -> Triple(Icons.Filled.Warning, Color(0xFFE2A455), "Missing in cloud")
                else -> Triple(Icons.Filled.Phone, Color.White, "Local only")
            }
            Icon(
                imageVector = badgeIcon,
                contentDescription = badgeDesc,
                tint = badgeTint,
                modifier = Modifier.size(12.dp)
            )
        }
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = Color(0xFFE2557A),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
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
