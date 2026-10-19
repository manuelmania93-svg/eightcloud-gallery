package app.eightcloud.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import app.eightcloud.gallery.data.MediaDetails
import app.eightcloud.gallery.data.MediaItem
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(viewModel: GalleryViewModel) {
    val items by viewModel.items.collectAsState()
    val startIndex by viewModel.viewerStartIndex.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    var showInfo by remember { mutableStateOf(false) }

    BackHandler { viewModel.backToGrid() }

    if (items.isEmpty()) {
        viewModel.backToGrid()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.size - 1),
        pageCount = { items.size }
    )
    val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = currentItem.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { viewModel.backToGrid() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            actions = {
                val context = androidx.compose.ui.platform.LocalContext.current
                val statusById by viewModel.statusById.collectAsState()
                val featureToggles by viewModel.featureToggles.collectAsState()
                val sharing by viewModel.sharing.collectAsState()
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val itemStatus = statusById[currentItem.id]
                val hasCloudCopy = itemStatus == app.eightcloud.gallery.backup.BackupEntry.BACKED_UP ||
                    itemStatus == app.eightcloud.gallery.backup.BackupEntry.CLOUD_ONLY
                val cloudOnlyHidden = itemStatus == app.eightcloud.gallery.backup.BackupEntry.CLOUD_ONLY &&
                    featureToggles["feat_cloud_share"] == false
                if (!currentItem.isVideo) {
                    IconButton(onClick = { viewModel.openEdit(currentItem) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color.White)
                    }
                }
                if (!cloudOnlyHidden) IconButton(
                    enabled = !sharing,
                    onClick = {
                        val cloudOnly = itemStatus == app.eightcloud.gallery.backup.BackupEntry.CLOUD_ONLY
                        if (!cloudOnly) {
                            // Local copy: share straight from MediaStore.
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = currentItem.mimeType.ifBlank { "*/*" }
                                putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(currentItem.uri))
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share"))
                        } else {
                            viewModel.shareFromCloud(
                                currentItem,
                                onReady = { file ->
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", file
                                    )
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = currentItem.mimeType.ifBlank { "*/*" }
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(send, "Share"))
                                },
                                onError = { msg ->
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = if (itemStatus == app.eightcloud.gallery.backup.BackupEntry.CLOUD_ONLY) "Share cloud copy" else "Share",
                        tint = if (sharing) Color.Gray else Color.White
                    )
                }
                if (itemStatus != app.eightcloud.gallery.backup.BackupEntry.CLOUD_ONLY) {
                    IconButton(onClick = {
                        viewModel.trashIds(setOf(currentItem.id))
                        viewModel.backToGrid()
                    }) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Move to trash",
                            tint = Color.White
                        )
                    }
                }
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(Icons.Filled.Info, contentDescription = "Info", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black,
                titleContentColor = Color.White
            )
        )

        Box(modifier = Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    VideoPage(item, isActive = pagerState.currentPage == page)
                } else {
                    PhotoPage(item)
                }
            }
            if (showInfo) {
                InfoPanel(
                    viewModel = viewModel,
                    item = currentItem,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            val isFav = favorites.contains(currentItem.id)
            IconButton(onClick = { viewModel.toggleFavorite(currentItem.id) }) {
                Icon(
                    imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFav) "Remove favorite" else "Add favorite",
                    tint = if (isFav) Color(0xFFE2557A) else Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
private fun PhotoPage(item: MediaItem) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Only consume gestures while zoomed or when pinching (2+ pointers);
                // single-finger swipes at 1x must stay unconsumed so the pager pages.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1.01f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                        }
                    }
                    if (scale <= 1.01f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(item: MediaItem, isActive: Boolean, onEnded: () -> Unit = {}) {
    val context = LocalContext.current
    var errorMessage by remember(item.id) { mutableStateOf<String?>(null) }
    var muted by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(item.uri)))
            prepare()
        }
    }
    DisposableEffect(item.id) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = error.errorCodeName
                app.eightcloud.gallery.AppLog.log("video", "playback error for " + item.name + ": " + error.errorCodeName)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.release() }
    }
    LaunchedEffect(isActive) {
        if (isActive && errorMessage == null) player.play() else player.pause()
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = { muted = !muted },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(70.dp, 8.dp, 8.dp, 8.dp)
        ) {
            Icon(
                imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = Color.White
            )
        }
        errorMessage?.let { msg ->
            Text(
                "This video can't be played ($msg)",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
    }
}

@Composable
private fun InfoPanel(viewModel: GalleryViewModel, item: MediaItem, modifier: Modifier = Modifier) {
    val details by produceState<MediaDetails?>(initialValue = null, item.id) {
        value = viewModel.loadDetails(item)
    }
    var showRename by remember(item.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val writeConsent = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            android.widget.Toast.makeText(context, "Tap rename again to finish", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    if (showRename) {
        var newName by remember { mutableStateOf(item.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = newName.isNotBlank() && newName != item.name,
                    onClick = {
                        showRename = false
                        viewModel.renameItem(
                            item, newName.trim(),
                            onNeedConsent = { sender ->
                                runCatching {
                                    writeConsent.launch(
                                        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                                    )
                                }
                            },
                            onDone = { ok, err ->
                                android.widget.Toast.makeText(
                                    context,
                                    if (ok) "Renamed" else (err ?: "Rename failed"),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRename = false }) { Text("Cancel") }
            }
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xE610181F),
        tonalElevation = 0.dp
    ) {
        val d = details
        if (d == null) {
            Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showRename = true }
                ) {
                    Text(
                        text = "File name",
                        color = Color(0xFFB9CCDC),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        text = d.name,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Rename",
                        tint = Color(0xFFB9CCDC),
                        modifier = Modifier.size(16.dp)
                    )
                }
                infoRow("Date", d.dateText)
                infoRow("Size", d.sizeText)
                infoRow("Resolution", d.resolution)
                d.durationText?.let { infoRow("Duration", it) }
                infoRow("Type", d.mimeType)
                if (d.album.isNotBlank()) infoRow("Album", d.album)
                d.camera?.let { infoRow("Camera", it) }
                d.iso?.let { infoRow("ISO", it) }
                d.aperture?.let { infoRow("Aperture", it) }
                d.focalLength?.let { infoRow("Focal length", "$it mm") }
                d.exposure?.let { infoRow("Exposure", it) }
                infoRow("Cloud status", d.cloudStatus)

                // Analysis: what the on-device scanner saw, and corrections.
                val labelsMap by viewModel.mediaLabels.collectAsState()
                val overridesMap by viewModel.categoryOverrides.collectAsState()
                val entry = labelsMap[item.id]
                if (entry != null && !item.isVideo) {
                    val b = item.bucket.lowercase()
                    val cats = app.eightcloud.gallery.labels.Categories.forItem(
                        entry.faceCount, entry.faceMaxRatio, confsOf(entry), entry.ocrChars,
                        entry.nsfwScore, b.contains("screenshot"),
                        b.contains("camera") || b.contains("dcim")
                    )
                    val excluded = overridesMap[item.id]?.filterValues { it }?.keys ?: emptySet()
                    Text(
                        text = "Analysis",
                        color = Color(0xFFB9CCDC),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    val confPairs = app.eightcloud.gallery.labels.Categories.parseConfs(confsOf(entry))
                    if (confPairs.isNotEmpty()) {
                        infoRow(
                            "Labels",
                            confPairs.filter { !it.first.startsWith("~") }.take(8).joinToString(", ") {
                                it.first + " " + (it.second * 100).toInt() + "%"
                            }
                        )
                    }
                    val clipShares = confPairs.filter { it.first.startsWith("~") && it.second >= 0.05f }
                        .sortedByDescending { it.second }
                    if (clipShares.isNotEmpty()) {
                        infoRow(
                            "CLIP shares",
                            clipShares.joinToString(", ") {
                                it.first.substring(1) + " " + (it.second * 100).toInt() + "%"
                            }
                        )
                    }
                    val clipTop by androidx.compose.runtime.produceState<List<Pair<String, Float>>?>(
                        null, item.id, entry.labelConfs
                    ) { value = viewModel.clipTop(item.id) }
                    clipTop?.let { top ->
                        infoRow(
                            "Top 3 matches",
                            top.joinToString("; ") { it.first + " " + (it.second * 100).toInt() + "%" }
                        )
                    }
                    if (entry.faceCount > 0) {
                        infoRow(
                            "Faces",
                            entry.faceCount.toString() + " (largest " +
                                String.format(java.util.Locale.US, "%.1f", entry.faceMaxRatio * 100) + "%)"
                        )
                    }
                    infoRow("Text (OCR)", entry.ocrChars.toString() + " chars")
                    if (entry.nsfwScore >= 0f) {
                        infoRow(
                            "Sensitive score",
                            String.format(java.util.Locale.US, "%.2f", entry.nsfwScore)
                        )
                    }
                    infoRow(
                        "Categories",
                        if (cats.isEmpty()) "none" else cats.joinToString(", ") {
                            app.eightcloud.gallery.labels.Categories.title(it)
                        }
                    )
                    cats.forEach { cat ->
                        TextButton(
                            onClick = { viewModel.setCategoryOverride(item.id, cat, true) },
                            modifier = Modifier.padding(vertical = 0.dp)
                        ) {
                            Text(
                                "Not " + app.eightcloud.gallery.labels.Categories.title(cat),
                                color = Color(0xFFEF9A9A)
                            )
                        }
                    }
                    excluded.forEach { cat ->
                        TextButton(
                            onClick = { viewModel.setCategoryOverride(item.id, cat, null) },
                            modifier = Modifier.padding(vertical = 0.dp)
                        ) {
                            Text(
                                "Undo: not " + app.eightcloud.gallery.labels.Categories.title(cat),
                                color = Color(0xFFA5D6A7)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun infoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            color = Color(0xFFB9CCDC),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
