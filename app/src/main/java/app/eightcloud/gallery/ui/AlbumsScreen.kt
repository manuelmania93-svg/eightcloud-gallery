package app.eightcloud.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(viewModel: GalleryViewModel) {
    val bucketCounts by viewModel.bucketCounts.collectAsState()
    val smartAlbums by viewModel.smartAlbums.collectAsState()
    val monthAlbums by viewModel.monthAlbums.collectAsState()
    val featureToggles by viewModel.featureToggles.collectAsState()
    val buckets = bucketCounts.entries.sortedByDescending { it.value }
    val showAuto = featureToggles["feat_auto_albums"] != false
    val categoryAlbums by viewModel.categoryAlbums.collectAsState()
    val labelScanProgress by viewModel.labelScanProgress.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.scanLabels() }

    Scaffold(topBar = { TopAppBar(title = { Text("Albums") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item(key = "hdr-cats") {
                Text(
                    "Categories" + labelScanProgress.let { p ->
                        if (p != null) " (analyzing ${p.first}/${p.second})" else ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(categoryAlbums, key = { it.key }) { album ->
                AlbumRow(
                    title = album.title,
                    count = album.count,
                    onClick = {
                        viewModel.setSmartFilter(album.key)
                        viewModel.backToGrid()
                    }
                )
                HorizontalDivider()
            }
            if (showAuto && smartAlbums.isNotEmpty()) {
                item(key = "hdr-smart") {
                    Text(
                        "Smart albums",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(smartAlbums, key = { it.key }) { album ->
                    AlbumRow(
                        title = album.title,
                        count = album.count,
                        onClick = {
                            viewModel.setSmartFilter(album.key)
                            viewModel.backToGrid()
                        }
                    )
                    HorizontalDivider()
                }
            }
            if (showAuto && monthAlbums.isNotEmpty()) {
                item(key = "hdr-months") {
                    Text(
                        "By month",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(monthAlbums, key = { it.key }) { album ->
                    AlbumRow(
                        title = album.title,
                        count = album.count,
                        onClick = {
                            viewModel.setSmartFilter(album.key)
                            viewModel.backToGrid()
                        }
                    )
                    HorizontalDivider()
                }
            }
            item(key = "hdr-folders") {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(buckets, key = { it.key }) { entry ->
                AlbumRow(
                    title = entry.key,
                    count = entry.value,
                    onClick = {
                        viewModel.setBucketFilter(entry.key)
                        viewModel.backToGrid()
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AlbumRow(title: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                "$count items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
