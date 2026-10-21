package app.eightcloud.gallery.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.eightcloud.gallery.edit.EditSession
import app.eightcloud.gallery.edit.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class EditMode { NONE, CROP, FILTERS, ADJUST }

/** Basic editor: rotate, flip, crop, filters, brightness/contrast/saturation. Saves as a copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val item = viewModel.editItem
    if (item == null) {
        onBack()
        return
    }
    val session = remember(item.id) { EditSession(context, Uri.parse(item.uri), item.name) }
    var tick by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(EditMode.NONE) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val preview by produceState<Bitmap?>(initialValue = null, item.id, tick) {
        value = withContext(Dispatchers.Default) {
            session.loadPreview()?.let { session.applyAll(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    Text(
                        if (saving) "Saving..." else "",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(
                        enabled = !saving && session.isDirty,
                        onClick = {
                            saving = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) { session.saveCopy() }
                                saving = false
                                if (out != null) {
                                    Toast.makeText(context, "Saved a copy", Toast.LENGTH_SHORT).show()
                                    viewModel.loadMedia()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save a copy")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = {
                    session.rotationQuarterTurns = (session.rotationQuarterTurns + 1) % 4
                    tick++
                }) {
                    Icon(Icons.Filled.Rotate90DegreesCcw, contentDescription = "Rotate")
                }
                IconButton(onClick = {
                    session.flipped = !session.flipped
                    tick++
                }) {
                    Icon(Icons.Filled.Flip, contentDescription = "Flip")
                }
                IconButton(onClick = { mode = if (mode == EditMode.CROP) EditMode.NONE else EditMode.CROP }) {
                    Icon(Icons.Filled.Crop, contentDescription = "Crop")
                }
                IconButton(onClick = { mode = if (mode == EditMode.FILTERS) EditMode.NONE else EditMode.FILTERS }) {
                    Icon(Icons.Filled.FilterVintage, contentDescription = "Filters")
                }
                IconButton(onClick = { mode = if (mode == EditMode.ADJUST) EditMode.NONE else EditMode.ADJUST }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Brightness and more")
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
            ) {
                preview?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (mode == EditMode.CROP) {
                    CropOverlay(
                        initial = session.cropRect,
                        onChanged = { rect -> session.cropRect = rect; tick++ }
                    )
                }
            }
            when (mode) {
                EditMode.FILTERS -> Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ImageOps.FILTER_NAMES.forEach { name ->
                        TextButton(onClick = { session.filterName = name; tick++ }) {
                            Text(
                                name,
                                color = if (session.filterName == name)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                EditMode.ADJUST -> Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Brightness", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = session.brightness,
                        onValueChange = { session.brightness = it; tick++ },
                        valueRange = -1f..1f
                    )
                    Text("Contrast", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = session.contrast,
                        onValueChange = { session.contrast = it; tick++ },
                        valueRange = -1f..1f
                    )
                    Text("Saturation", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = session.saturation,
                        onValueChange = { session.saturation = it; tick++ },
                        valueRange = -1f..1f
                    )
                }
                EditMode.CROP -> Text(
                    "Drag the corners to crop, then Save",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
                EditMode.NONE -> Unit
            }
        }
    }
}

/** Draggable 4-corner crop rectangle over the preview, in fractions 0..1. */
@Composable
private fun CropOverlay(initial: FloatArray?, onChanged: (FloatArray) -> Unit) {
    var rect by remember { mutableStateOf(initial ?: floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Dimmed frame around the rect.
        Box(
            modifier = Modifier
                .offset { IntOffset((rect[0] * wPx).roundToInt(), (rect[1] * hPx).roundToInt()) }
                .size(
                    with(density) { ((rect[2] - rect[0]) * wPx).toDp() },
                    with(density) { ((rect[3] - rect[1]) * hPx).toDp() }
                )
                .border(2.dp, Color.White)
        )
        // Corner handles: top-left and bottom-right are enough for a v1 drag crop.
        listOf(0 to 1, 2 to 3).forEach { (xi, yi) ->
            Box(
                modifier = Modifier
                    .offset { IntOffset((rect[xi] * wPx).roundToInt() - 24, (rect[yi] * hPx).roundToInt() - 24) }
                    .size(48.dp)
                    .pointerInput(xi, yi) {
                        detectDragGestures(
                            onDragEnd = { onChanged(rect) }
                        ) { change, dragAmount: Offset ->
                            change.consume()
                            val next = rect.copyOf()
                            next[xi] = (next[xi] + dragAmount.x / wPx).coerceIn(0f, 1f)
                            next[yi] = (next[yi] + dragAmount.y / hPx).coerceIn(0f, 1f)
                            // keep at least 10% size and l<r, t<b
                            if (next[2] - next[0] >= 0.1f || xi == 2) {
                                if (xi == 0) next[0] = minOf(next[0], next[2] - 0.1f)
                                else next[2] = maxOf(next[2], next[0] + 0.1f)
                            } else {
                                next[xi] = rect[xi]
                            }
                            if (next[3] - next[1] >= 0.1f || yi == 3) {
                                if (yi == 1) next[1] = minOf(next[1], next[3] - 0.1f)
                                else next[3] = maxOf(next[3], next[1] + 0.1f)
                            } else {
                                next[yi] = rect[yi]
                            }
                            rect = next
                        }
                    }
                    .background(Color.White.copy(alpha = 0.7f), CircleShape)
                    .border(1.dp, Color.Black, CircleShape)
            )
        }
    }
}
