package app.eightcloud.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eightcloud.gallery.data.MediaSort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val cloudOnly by viewModel.cloudOnly.collectAsState()
    val progress by viewModel.restoreProgress.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) { viewModel.loadCloudOnly() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore from cloud") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (selected.isNotEmpty() && !busy) {
                        TextButton(onClick = {
                            viewModel.restoreSelected(selected)
                            selected = emptySet()
                        }) {
                            Text("Restore (${selected.size})")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            progress?.let { (done, total, name) ->
                Column(modifier = Modifier.padding(16.dp)) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Restoring $done / $total  $name",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            if (cloudOnly.isEmpty() && progress == null) {
                Text(
                    text = "Nothing to restore. Items you deleted locally after a verified backup show up here.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (!busy && cloudOnly.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.size == cloudOnly.size) emptySet()
                                else cloudOnly.map { it.contentHash }.toSet()
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selected.size == cloudOnly.size) "Clear selection" else "Select all (${cloudOnly.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(cloudOnly.size) { index ->
                        val entry = cloudOnly[index]
                        val checked = entry.contentHash in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    selected = if (checked) selected - entry.contentHash
                                    else selected + entry.contentHash
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = !busy
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${MediaSort.sizeLabel(entry.sizeBytes)} - " +
                                        Instant.ofEpochMilli(entry.uploadedAt)
                                            .atZone(ZoneId.systemDefault()).toLocalDate()
                                            .format(DateTimeFormatter.ofPattern("d MMM yyyy")) +
                                        if (entry.isVideo) " - video" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
