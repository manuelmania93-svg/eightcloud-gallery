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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val privacyScreen by viewModel.privacyScreen.collectAsState()
    val hideSensitive by viewModel.hideSensitive.collectAsState()
    val autoSecureSensitive by viewModel.autoSecureSensitive.collectAsState()
    var askAutoSecure by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val featureToggles by viewModel.featureToggles.collectAsState()
    val slideshowOrder by viewModel.slideshowOrder.collectAsState()
    val slideshowSpeedSec by viewModel.slideshowSpeedSec.collectAsState()
    val slideshowLoop by viewModel.slideshowLoop.collectAsState()
    val slideshowMusicUri by viewModel.slideshowMusicUri.collectAsState()
    val slideshowVideoMode by viewModel.slideshowVideoMode.collectAsState()
    val splitLargeFiles by viewModel.splitLargeFiles.collectAsState()

    BackHandler { viewModel.backToGrid() }
    val context = androidx.compose.ui.platform.LocalContext.current

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
                .clickable { viewModel.setPrivacyScreen(!privacyScreen) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Privacy screen", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Hide app content in recent apps and block screenshots everywhere (Secure folder always blocks them)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = privacyScreen,
                onCheckedChange = { viewModel.setPrivacyScreen(it) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setHideSensitive(!hideSensitive) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Hide Sensitive items", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep photos and videos the on-device scan marks Sensitive out of the grid, On this day and other albums. They stay in the Sensitive category.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = hideSensitive,
                onCheckedChange = { viewModel.setHideSensitive(it) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (autoSecureSensitive) viewModel.setAutoSecureSensitive(false, false)
                    else askAutoSecure = viewModel.sensitiveNotSecure().size + 1
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Move Sensitive to Secure folder", style = MaterialTheme.typography.titleMedium)
                Text(
                    "After each scan, move new Sensitive items into the Secure folder. Off by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = autoSecureSensitive,
                onCheckedChange = {
                    if (autoSecureSensitive) viewModel.setAutoSecureSensitive(false, false)
                    else askAutoSecure = viewModel.sensitiveNotSecure().size + 1
                }
            )
        }

        if (askAutoSecure > 0) {
            val existing = askAutoSecure - 1
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { askAutoSecure = 0 },
                title = { Text("Move Sensitive items?") },
                text = {
                    Text(
                        "$existing Sensitive items found so far. Move them to the Secure folder now? New ones will be moved after each scan. The scan is automatic and can be wrong; you can move items back from the Secure folder."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.setAutoSecureSensitive(true, true)
                        askAutoSecure = 0
                    }) { Text("Move") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.setAutoSecureSensitive(true, false)
                        askAutoSecure = 0
                    }) { Text("Only new ones") }
                }
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

        if (featureToggles["feat_storage_stats"] != false) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openSecureFolder() }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Secure folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hidden items, locked behind your screen lock",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openMap() }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Map", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your photos with location on a map",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openStorageStats() }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Storage stats", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Sizes per folder, biggest files, space you can free",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (featureToggles["feat_slideshow"] != false) {
            var showSlideshowDialog by remember { mutableStateOf(false) }
            val musicPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    viewModel.setSlideshowMusic(uri.toString())
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSlideshowDialog = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Slideshow", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Order, speed, music - plays the current view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showSlideshowDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showSlideshowDialog = false },
                    title = { Text("Slideshow") },
                    text = {
                        Column {
                            Text("Order", style = MaterialTheme.typography.titleSmall)
                            listOf("DATE" to "By date", "RANDOM" to "Random").forEach { (value, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setSlideshowOrder(value) }
                                ) {
                                    RadioButton(
                                        selected = slideshowOrder == value,
                                        onClick = { viewModel.setSlideshowOrder(value) }
                                    )
                                    Text(label)
                                }
                            }
                            Text(
                                "Speed: ${slideshowSpeedSec}s per photo",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Slider(
                                value = slideshowSpeedSec.toFloat(),
                                onValueChange = { viewModel.setSlideshowSpeedSec(it.toInt()) },
                                valueRange = 2f..15f
                            )
                            Text("Videos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                            listOf("PLAY" to "Play fully", "SKIP" to "Skip videos").forEach { (value, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setSlideshowVideoMode(value) }
                                ) {
                                    RadioButton(
                                        selected = slideshowVideoMode == value,
                                        onClick = { viewModel.setSlideshowVideoMode(value) }
                                    )
                                    Text(label)
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Loop", modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(
                                    checked = slideshowLoop,
                                    onCheckedChange = { viewModel.setSlideshowLoop(it) }
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (slideshowMusicUri != null) "Music: selected" else "No music",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                androidx.compose.material3.TextButton(onClick = {
                                    musicPicker.launch(arrayOf("audio/*"))
                                }) { Text("Pick") }
                                if (slideshowMusicUri != null) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        viewModel.setSlideshowMusic(null)
                                    }) { Text("Clear") }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showSlideshowDialog = false
                            viewModel.openSlideshow()
                        }) { Text("Start") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showSlideshowDialog = false
                        }) { Text("Cancel") }
                    }
                )
            }
        }

        Text(
            "Categories",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        app.eightcloud.gallery.labels.Categories.ALL.forEach { key ->
            var enabled by androidx.compose.runtime.remember(key) {
                androidx.compose.runtime.mutableStateOf(viewModel.categoryEnabled(key))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    app.eightcloud.gallery.labels.Categories.title(key),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        viewModel.setCategoryEnabled(key, it)
                    }
                )
            }
        }

        if (featureToggles["feat_cleanup"] != false) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openCleanup() }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Cleanup mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Swipe through your photos: left to trash, right to keep",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Split files over 2 GB", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Big videos upload in parts - Telegram's free limit is 2 GB per file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = splitLargeFiles,
                onCheckedChange = { viewModel.setSplitLargeFiles(it) }
            )
        }

        Text(
            "Features",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        FEATURE_TOGGLES.filter { it.available }.forEach { toggle ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(toggle.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        toggle.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = featureToggles[toggle.key] != false,
                    onCheckedChange = { viewModel.setFeatureEnabled(toggle.key, it) }
                )
            }
        }

        Text(
            text = "Version " + app.eightcloud.gallery.BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp)
        )

        // Crash log: if the app died unexpectedly, the last stack trace can
        // be viewed and shared from here after the restart.
        var crashLog by remember { mutableStateOf(app.eightcloud.gallery.CrashLogger.read(context)) }
        var showCrashDialog by remember { mutableStateOf(false) }
        if (crashLog != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCrashDialog = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Last crash log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The app crashed last time - tap to view or share",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (showCrashDialog && crashLog != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showCrashDialog = false },
                title = { Text("Last crash") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(crashLog ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, crashLog)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(send, "Share crash log")
                        )
                    }) { Text("Share") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        app.eightcloud.gallery.CrashLogger.clear(context)
                        crashLog = null
                        showCrashDialog = false
                    }) { Text("Delete") }
                }
            )
        }

        // App log: rolling on-device log (starts, backup runs, slider drags,
        // memory warnings, previous exit reasons). Share sends it as text.
        var showLogDialog by remember { mutableStateOf(false) }
        var logVersion by remember { mutableStateOf(0) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLogDialog = true }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("App log", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Diagnostics for bug reports - stays on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showLogDialog) {
            val logText = remember(logVersion) { app.eightcloud.gallery.AppLog.read(context) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showLogDialog = false },
                title = { Text("App log") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            if (logText.isBlank()) "No log entries yet" else logText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, logText)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(send, "Share app log")
                        )
                    }) { Text("Share") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        app.eightcloud.gallery.AppLog.clear(context)
                        logVersion++
                    }) { Text("Clear") }
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openRestore() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Restore from cloud", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Download backed-up items you deleted from this device",
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
