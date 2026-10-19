package app.eightcloud.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.eightcloud.gallery.data.MediaSort
import app.eightcloud.gallery.data.SortField
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: GalleryViewModel) {
    val columns by viewModel.columns.collectAsState()
    val trashItems by viewModel.trashItems.collectAsState()
    val duplicateGroups by viewModel.duplicateGroups.collectAsState()
    val appLock by viewModel.appLock.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    BackHandler { viewModel.backToGrid() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.backToGrid() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Grid", style = MaterialTheme.typography.titleMedium)
            Text(
                "Columns: $columns",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = columns.toFloat(),
                onValueChange = { viewModel.setColumns(it.roundToInt()) },
                valueRange = MediaSort.MIN_COLUMNS.toFloat()..MediaSort.MAX_COLUMNS.toFloat(),
                steps = MediaSort.MAX_COLUMNS - MediaSort.MIN_COLUMNS - 1,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Tip: pinch on the grid also changes the column count.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setAppLock(!appLock) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text("App lock", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Require fingerprint or PIN on start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = appLock,
                onCheckedChange = { viewModel.setAppLock(it) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openTrash() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Trash", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${trashItems.size} items - kept for 30 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openDuplicates() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Duplicates", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${duplicateGroups.size} groups - same size and dimensions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

            Text("Default sort", style = MaterialTheme.typography.titleMedium)
            sortChoice("Date (newest first)", sortField == SortField.DATE && !sortAscending) {
                viewModel.setSort(SortField.DATE, false)
            }
            sortChoice("Date (oldest first)", sortField == SortField.DATE && sortAscending) {
                viewModel.setSort(SortField.DATE, true)
            }
            sortChoice("Size (largest first)", sortField == SortField.SIZE && !sortAscending) {
                viewModel.setSort(SortField.SIZE, false)
            }
            sortChoice("Size (smallest first)", sortField == SortField.SIZE && sortAscending) {
                viewModel.setSort(SortField.SIZE, true)
            }
            sortChoice("Name (A-Z)", sortField == SortField.NAME && sortAscending) {
                viewModel.setSort(SortField.NAME, true)
            }
            sortChoice("Name (Z-A)", sortField == SortField.NAME && !sortAscending) {
                viewModel.setSort(SortField.NAME, false)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Cloud backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Back up photos and videos, encrypted, into your second Telegram account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            androidx.compose.material3.Button(
                onClick = { viewModel.openBackup() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) { Text("Open backup") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Theme", style = MaterialTheme.typography.titleMedium)
            sortChoice("Cloud sky (default)", themeMode == ThemeMode.CLOUD_SKY) { viewModel.setThemeMode(ThemeMode.CLOUD_SKY) }
            sortChoice("System", themeMode == ThemeMode.SYSTEM) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
            sortChoice("Light", themeMode == ThemeMode.LIGHT) { viewModel.setThemeMode(ThemeMode.LIGHT) }
            sortChoice("Dark", themeMode == ThemeMode.DARK) { viewModel.setThemeMode(ThemeMode.DARK) }
        }
    }
}

@Composable
private fun sortChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
