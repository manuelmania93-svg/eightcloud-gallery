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

@Composable
fun SlideshowScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsState()
    val order by viewModel.slideshowOrder.collectAsState()
    val speedSec by viewModel.slideshowSpeedSec.collectAsState()
    val loop by viewModel.slideshowLoop.collectAsState()
    val musicUri by viewModel.slideshowMusicUri.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    val playlist = remember(items, order) {
        if (order == "RANDOM") items.shuffled() else items
    }
    var index by remember { mutableIntStateOf(0) }

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

    // Advance on the chosen interval.
    LaunchedEffect(index, playlist.size, speedSec) {
        if (playlist.isEmpty()) return@LaunchedEffect
        delay(speedSec * 1000L)
        if (index + 1 < playlist.size) {
            index++
        } else if (loop) {
            index = 0
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        val current = playlist.getOrNull(index)
        if (current != null) {
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
