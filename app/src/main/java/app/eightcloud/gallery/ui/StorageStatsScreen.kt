package app.eightcloud.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import app.eightcloud.gallery.data.MediaItem
import app.eightcloud.gallery.data.MediaSort

data class BucketStat(val bucket: String, val count: Int, val bytes: Long)

data class StorageStats(
    val totalCount: Int,
    val totalBytes: Long,
    val buckets: List<BucketStat>,
    val biggest: List<MediaItem>,
    val trashedCount: Int,
    val trashedBytes: Long,
    val duplicateSavingsBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageStatsScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val stats by viewModel.storageStats.collectAsState()
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val s = stats
            if (s == null) {
                Text("Calculating...", modifier = Modifier.padding(vertical = 24.dp))
            } else {
                Text(
                    MediaSort.sizeLabel(s.totalBytes),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    "${s.totalCount} photos and videos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (s.trashedBytes > 0 || s.duplicateSavingsBytes > 0) {
                    Text(
                        "Can be freed",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                    )
                    if (s.trashedBytes > 0) {
                        Text(
                            "Trash: ${MediaSort.sizeLabel(s.trashedBytes)} (${s.trashedCount} items)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    if (s.duplicateSavingsBytes > 0) {
                        Text(
                            "Duplicate copies: ${MediaSort.sizeLabel(s.duplicateSavingsBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                Text(
                    "By folder",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                )
                val maxBytes = s.buckets.maxOfOrNull { it.bytes } ?: 1L
                s.buckets.forEach { b ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                b.bucket,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${b.count} - ${MediaSort.sizeLabel(b.bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (b.bytes.toFloat() / maxBytes).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }
                }

                if (s.biggest.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    Text(
                        "Biggest files",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    s.biggest.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    item.bucket,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                MediaSort.sizeLabel(item.sizeBytes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
