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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.eightcloud.gallery.data.MediaSort
import app.eightcloud.gallery.data.UpdateState
import app.eightcloud.gallery.data.SortField
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: GalleryViewModel) {
    val columns by viewModel.columns.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

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

            Text("Theme", style = MaterialTheme.typography.titleMedium)
            sortChoice("System", themeMode == ThemeMode.SYSTEM) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
            sortChoice("Light", themeMode == ThemeMode.LIGHT) { viewModel.setThemeMode(ThemeMode.LIGHT) }
            sortChoice("Dark", themeMode == ThemeMode.DARK) { viewModel.setThemeMode(ThemeMode.DARK) }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("App update", style = MaterialTheme.typography.titleMedium)
            Text(
                "Installed: ${viewModel.installedVersionName} (${viewModel.installedVersionCode})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            when (val state = updateState) {
                UpdateState.Idle -> updateAction("Check for updates", "update_check") { viewModel.checkForUpdate(silent = false) }
                UpdateState.Checking -> updateStatus("Checking for updates...")
                UpdateState.UpToDate -> {
                    updateStatus("You're on the latest version.")
                    updateAction("Check again", "update_check") { viewModel.checkForUpdate(silent = false) }
                }
                is UpdateState.Available -> {
                    updateStatus("Update available: ${state.info.versionName} (${state.info.versionCode})")
                    updateAction("Download update", "update_download") { viewModel.downloadUpdate() }
                }
                is UpdateState.Downloading -> updateStatus("Downloading update... ${state.percent}%")
                is UpdateState.ReadyToInstall -> {
                    updateStatus("Update ready to install.")
                    updateAction("Install update", "update_install") { viewModel.installUpdate() }
                }
                is UpdateState.Failed -> {
                    updateStatus(
                        if (state.info == null) "Couldn't check for updates. Check your connection."
                        else "Update failed. The download could not be verified, so nothing was installed."
                    )
                    updateAction("Try again", "update_check") { viewModel.checkForUpdate(silent = false) }
                }
            }
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

@Composable
private fun updateAction(label: String, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(top = 4.dp)
            .testTag(tag)
    ) {
        Text(label)
    }
}

@Composable
private fun updateStatus(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}
