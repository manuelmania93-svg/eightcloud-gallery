package app.eightcloud.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val queue by viewModel.cleanupQueue.collectAsState()
    val index by viewModel.cleanupIndex.collectAsState()
    val trashedCount by viewModel.cleanupTrashedCount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cleanup - ${index.coerceAtMost(queue.size)} of ${queue.size}") },
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
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val item = queue.getOrNull(index)
            if (item == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Done!", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "$trashedCount items moved to trash this session",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Back to grid")
                    }
                }
            } else {
                var offsetX by remember(item.id) { mutableStateOf(0f) }
                val threshold = 280f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(item.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (abs(offsetX) > threshold) {
                                        val trash = offsetX < 0
                                        scope.launch {
                                            if (trash) viewModel.cleanupTrashCurrent()
                                            else viewModel.cleanupKeepCurrent()
                                        }
                                    }
                                    offsetX = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    offsetX += dragAmount
                                }
                            )
                        }
                        .graphicsLayer {
                            translationX = offsetX
                            rotationZ = offsetX / 40f
                        }
                ) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(item.uri)
                            .size(1080, 1080)
                            .build(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (offsetX < -40) {
                        Text(
                            "TRASH",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(20.dp)
                                .background(Color(0xCCB3261E), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    if (offsetX > 40) {
                        Text(
                            "KEEP",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(20.dp)
                                .background(Color(0xCC2E7D32), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { viewModel.cleanupTrashCurrent() }) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text(" Trash")
                    }
                    OutlinedButton(
                        onClick = { viewModel.cleanupUndo() },
                        enabled = index > 0
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(onClick = { viewModel.cleanupKeepCurrent() }) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Text(" Keep")
                    }
                }
                Text(
                    "Swipe left to trash, right to keep",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}
