package app.eightcloud.gallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.eightcloud.gallery.vlm.VlmModel
import app.eightcloud.gallery.vlm.VlmSelect
import app.eightcloud.gallery.vlm.VlmService
import app.eightcloud.gallery.vlm.VlmState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Settings block: AI model (Gemma 4 E2B) download, delete, redownload and the second pass. */
@Composable
fun VlmSettings(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val status by VlmState.status.collectAsState()
    val running by VlmState.running.collectAsState()
    val version by VlmState.version.collectAsState()
    val results by viewModel.vlmResults.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var showTutorial by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var unsure by remember { mutableIntStateOf(-1) }

    LaunchedEffect(running, status) { tick++ }
    LaunchedEffect(running) { while (running) { delay(2000); tick++ } }
    var remaining by remember { mutableIntStateOf(-1) }
    // Remaining photos: refreshed on every result batch and every 20 s while Settings is open.
    LaunchedEffect(version) { unsure = withContext(Dispatchers.IO) { VlmSelect.pending(context).size } }
    LaunchedEffect(version, running) {
        while (true) {
            remaining = withContext(Dispatchers.IO) { VlmSelect.fullQueue(context).size }
            delay(20000)
        }
    }

    val verified = tick.let { VlmModel.isVerified(context) }
    val onDisk = tick.let { VlmModel.onDiskBytes(context) }
    val freeMb = context.filesDir.usableSpace / 1_000_000
    val downloading = VlmModel.busy.get()

    Text(
        "AI model (Gemma)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    Text(
        "Optional. Official Google Gemma 4 E2B (2.6 GB, runs fully on this phone). It only looks again at photos the normal scanner is unsure about (memes, drawings) and adds a short description. It never decides Sensitive.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        when {
            verified -> "Model: ready (${onDisk / 1_000_000} MB, checksum verified). Free storage: $freeMb MB."
            downloading -> "Model: downloading ${VlmModel.progress}"
            onDisk > 0 -> "Model: incomplete (${onDisk / 1_000_000} of ${VlmModel.BYTES / 1_000_000} MB). Free storage: $freeMb MB."
            else -> "Model: not downloaded. Needs ${VlmModel.BYTES / 1_000_000} MB, free storage: $freeMb MB."
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
    Text(
        "Photos with an AI check: ${results.size}. Remaining to check: ${if (remaining < 0) "..." else remaining.toString()} (of those ${if (unsure < 0) "..." else unsure.toString()} unsure, done first). New photos are added automatically once the normal scan has seen them. Only unsure photos can change Memes/Drawn; the rest get a description. Results stay when you delete the model.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (!verified) {
            Button(enabled = !running && !downloading, onClick = {
                if (!VlmModel.isOnWifi(context)) VlmState.status("Connect to Wi-Fi (an unmetered network) first. The download is 2.6 GB.")
                else if (freeMb < 3200 && onDisk == 0L) VlmState.status("Not enough free storage (needs about 3.2 GB).")
                else VlmService.start(context, VlmService.ACTION_DOWNLOAD)
            }) { Text(if (onDisk > 0) "Resume download" else "Download") }
        } else {
            OutlinedButton(enabled = !running, onClick = { confirmDelete = true }) { Text("Delete model") }
        }
        TextButton(onClick = { showTutorial = true }) { Text("How it works") }
    }
    if (running) {
        val paused by VlmState.paused.collectAsState()
        val auto by VlmState.autoPause.collectAsState()
        val prog by VlmState.progress.collectAsState()
        if (prog.second > 0 && !downloading) {
            Text(
                "This run: ${prog.first} of ${prog.second} photos" + if (auto.isNotBlank()) ". $auto" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row {
            if (!downloading) {
                Button(onClick = { VlmState.paused(!paused) }) { Text(if (paused) "Resume" else "Pause") }
            }
            OutlinedButton(
                modifier = Modifier.padding(start = 8.dp),
                onClick = { VlmService.start(context, VlmService.ACTION_STOP) }
            ) { Text("Stop") }
        }
    }
    if (verified && !running) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            OutlinedButton(onClick = { VlmService.start(context, VlmService.ACTION_PASS, 50) }) { Text("Test on 50 photos") }
            Button(
                modifier = Modifier.padding(start = 8.dp),
                onClick = {
                    if (!VlmService.isCharging(context)) VlmState.status("Plug in the charger first.")
                    else VlmService.start(context, VlmService.ACTION_PASS, 0)
                }
            ) { Text("Check all photos") }
        }
        Text(
            "The full run does unsure photos first, then every other photo, newest first. It needs the charger, pauses by itself when the phone is hot (42 C, resumes at 38 C), the battery is under 15% or you unplug, and continues where it stopped. Stop and start again any time. The 50-photo test works without the charger.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the model?") },
            text = { Text("Frees 2.6 GB. Tags, descriptions and categories it already made stay. You can download it again any time.") },
            confirmButton = {
                TextButton(onClick = { VlmModel.delete(context); VlmState.status("Model deleted."); confirmDelete = false; tick++ }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            title = { Text("AI model: how it works") },
            text = {
                Column {
                    Text("1. Download the model once (2.6 GB, Wi-Fi only, resumes if interrupted, checked against a checksum).")
                    Text("2. Tap \"Test on 50 photos\" and look at a few results in a photo's info sheet (\"AI check\").", modifier = Modifier.padding(top = 6.dp))
                    Text("3. For the whole library, plug in the charger and tap \"Check all photos\". Unsure photos go first, then all others. It takes days in total: use Pause/Resume, it also pauses by itself when hot or low on battery and continues where it stopped. ", modifier = Modifier.padding(top = 6.dp))
                    Text("4. Delete the model any time to free space. Results stay. Download again to check more photos.", modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showTutorial = false }) { Text("OK") } }
        )
    }
}
