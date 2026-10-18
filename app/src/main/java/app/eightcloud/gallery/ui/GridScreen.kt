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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
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
import app.eightcloud.gallery.data.SortField
import coil.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private sealed class GridEntry(val key: String) {
    class Header(val title: String) : GridEntry("header-$title")
    class Media(val item: MediaItem) : GridEntry("media-${item.id}")
}

private fun buildEntries(items: List<MediaItem>, field: SortField): List<GridEntry> {
    if (field != SortField.DATE) return items.map { GridEntry.Media(it) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val entries = mutableListOf<GridEntry>()
    var lastDay: LocalDate? = null
    for (item in items) {
        val day = Instant.ofEpochMilli(item.dateMillis).atZone(zone).toLocalDate()
        if (day != lastDay) {
            entries.add(GridEntry.Header(MediaSort.dateLabel(day, today)))
            lastDay = day
        }
        entries.add(GridEntry.Media(item))
    }
    return entries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(viewModel: GalleryViewModel) {
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val columns by viewModel.columns.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var zoomAccumulator by remember { mutableStateOf(1f) }

    val entries = remember(items, sortField) { buildEntries(items, sortField) }

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
                            sortOption("Date (newest first)", SortField.DATE, false, sortField, sortAscending) {
                                viewModel.setSort(SortField.DATE, false); sortMenuOpen = false
                            }
                            sortOption("Date (oldest first)", SortField.DATE, true, sortField, sortAscending) {
                                viewModel.setSort(SortField.DATE, true); sortMenuOpen = false
                            }
                            sortOption("Size (largest first)", SortField.SIZE, false, sortField, sortAscending) {
                                viewModel.setSort(SortField.SIZE, false); sortMenuOpen = false
                            }
                            sortOption("Size (smallest first)", SortField.SIZE, true, sortField, sortAscending) {
                                viewModel.setSort(SortField.SIZE, true); sortMenuOpen = false
                            }
                            sortOption("Name (A-Z)", SortField.NAME, true, sortField, sortAscending) {
                                viewModel.setSort(SortField.NAME, true); sortMenuOpen = false
                            }
                            sortOption("Name (Z-A)", SortField.NAME, false, sortField, sortAscending) {
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
        }
    }
}

private fun sortOption(
    label: String,
    field: SortField,
    ascending: Boolean,
    currentField: SortField,
    currentAscending: Boolean,
    onClick: () -> Unit
): @Composable () -> Unit = {
    DropdownMenuItem(
        text = {
            Text(if (field == currentField && ascending == currentAscending) "$label  ✓" else label)
        },
        onClick = onClick
    )
}

@Composable
private fun MediaThumb(item: MediaItem, isFavorite: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.uri,
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
            Icon(
                imageVector = Icons.Filled.Phone,
                contentDescription = "Local only",
                tint = Color.White,
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
