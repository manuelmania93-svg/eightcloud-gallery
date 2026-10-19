package app.eightcloud.gallery.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun SlideshowVideo(item: app.eightcloud.gallery.data.MediaItem, onEnded: () -> Unit) {
    val context = LocalContext.current
    val player = remember(item.id) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(item.uri)))
            prepare()
            play()
        }
    }
    DisposableEffect(item.id) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) onEnded()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Skip a broken video instead of stalling the show.
                onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.release() }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.media3.ui.StyledPlayerView(ctx).apply {
                this.player = player
                useController = false
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun SlideshowScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsState()
    val order by viewModel.slideshowOrder.collectAsState()
    val speedSec by viewModel.slideshowSpeedSec.collectAsState()
    val loop by viewModel.slideshowLoop.collectAsState()
    val musicUri by viewModel.slideshowMusicUri.collectAsState()
    val videoMode by viewModel.slideshowVideoMode.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    val playlist = remember(items, order, videoMode) {
        val base = if (videoMode == "SKIP") items.filter { !it.isVideo } else items
        if (order == "RANDOM") base.shuffled() else base
    }
    var index by remember { mutableIntStateOf(0) }

    fun advance() {
        if (index + 1 < playlist.size) index++
        else if (loop) index = 0
        else onBack()
    }

    BackHandler { onBack() }

    // Keep the screen on during the show.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Optional background music from the phone.
    DisposableEffect(musicUri) {
        val player = musicUri?.let { uriString ->
            runCatching {
                MediaPlayer().apply {
                    setDataSource(context, Uri.parse(uriString))
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
        }
    }

    // Advance on the chosen interval; videos playing in full advance on end.
    val currentIsPlayingVideo = playlist.getOrNull(index)?.isVideo == true && videoMode == "PLAY"
    LaunchedEffect(index, playlist.size, speedSec, currentIsPlayingVideo) {
        if (playlist.isEmpty() || currentIsPlayingVideo) return@LaunchedEffect
        delay(speedSec * 1000L)
        advance()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        val current = playlist.getOrNull(index)
        if (current != null && current.isVideo && videoMode == "PLAY") {
            SlideshowVideo(item = current, onEnded = { advance() })
        } else if (current != null) {
            androidx.compose.animation.Crossfade(targetState = current, label = "slide") { item ->
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(item.uri)
                        .build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
