package app.eightcloud.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import app.eightcloud.gallery.backup.BackupProgress
import app.eightcloud.gallery.data.MediaSort
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val authState by viewModel.authState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val accountName by viewModel.accountName.collectAsState()
    val backedUp by viewModel.backedUp.collectAsState()
    val backupScope by viewModel.backupScope.collectAsState()
    val chargingOnly by viewModel.chargingOnly.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val deletableBytes by viewModel.deletableBytes.collectAsState()
    val destTitle by viewModel.destTitle.collectAsState()
    val groups by viewModel.groups.collectAsState()
    var showDestDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showFreeUpDialog by remember { mutableStateOf(false) }
    var showSetPassDialog by remember { mutableStateOf(false) }
    var showUnlockPassDialog by remember { mutableStateOf(false) }
    val passphraseEnabled by viewModel.passphraseEnabled.collectAsState()
    val freeUpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        viewModel.refreshCounts()
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportToFile(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }
    fun backUpWithPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.backUpNow()
    }
    if (showDestDialog) {
        AlertDialog(
            onDismissRequest = { showDestDialog = false },
            title = { Text("Backup destination") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setDestinationSavedMessages()
                                showDestDialog = false
                            }
                            .padding(vertical = 8.dp)
                    ) { Text("Saved Messages (private)") }
                    for ((id, title) in groups) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setDestination(id, title)
                                    showDestDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) { Text(title) }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showDestDialog = false
                                showManualDialog = true
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            "Enter IDs manually...",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDestDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showManualDialog) {
        var chatIdText by remember { mutableStateOf("") }
        var picsText by remember { mutableStateOf("") }
        var vidsText by remember { mutableStateOf("") }
        var generalText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Destination IDs") },
            text = {
                Column {
                    OutlinedTextField(
                        value = chatIdText,
                        onValueChange = { chatIdText = it },
                        label = { Text("Chat / group ID") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = picsText,
                        onValueChange = { picsText = it },
                        label = { Text("Pics topic ID (optional)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = vidsText,
                        onValueChange = { vidsText = it },
                        label = { Text("Vids topic ID (optional)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = generalText,
                        onValueChange = { generalText = it },
                        label = { Text("General topic ID (optional)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chatId = chatIdText.trim().toLongOrNull()
                    if (chatId != null) {
                        viewModel.setDestinationManual(
                            chatId,
                            picsText.trim().toLongOrNull() ?: 0L,
                            vidsText.trim().toLongOrNull() ?: 0L,
                            generalText.trim().toLongOrNull() ?: 0L
                        )
                    }
                    showManualDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showFreeUpDialog) {
        val (freeBytes, freeCount) = deletableBytes
        AlertDialog(
            onDismissRequest = { showFreeUpDialog = false },
            title = { Text("Free up space") },
            text = {
                Text("Delete $freeCount local copies (${MediaSort.sizeLabel(freeBytes)})? Each one has a completed cloud copy. Restoring needs this app installation: after an uninstall or clear-data the encryption key is gone and cloud copies can no longer be decrypted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showFreeUpDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        scope.launch {
                            val uris = viewModel.backedUpLocalUris()
                            if (uris.isNotEmpty()) {
                                val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                                freeUpLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            }
                        }
                    } else {
                        Toast.makeText(context, "Needs Android 11 or newer", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Delete local copies") }
            },
            dismissButton = {
                TextButton(onClick = { showFreeUpDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showSetPassDialog) {
        var pass1 by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSetPassDialog = false },
            title = { Text("Set backup passphrase") },
            text = {
                Column {
                    Text(
                        "Only for a NEW backup. If you already have a cloud backup from another install, cancel and use \"Unlock with passphrase\" instead.\n\nIf you forget this passphrase, your cloud backup CANNOT be restored. It is never uploaded or stored.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = pass1,
                        onValueChange = { pass1 = it },
                        label = { Text("Passphrase (min. 8 characters)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it },
                        label = { Text("Repeat passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pass2.isNotEmpty() && pass1 != pass2,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSetPassDialog = false
                        viewModel.setBackupPassphrase(pass1)
                    },
                    enabled = pass1.length >= 8 && pass1 == pass2
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showSetPassDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showUnlockPassDialog) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUnlockPassDialog = false },
            title = { Text(if (passphraseEnabled) "Verify passphrase" else "Unlock with passphrase") },
            text = {
                Column {
                    if (!passphraseEnabled) {
                        Text(
                            "Enter the passphrase of your existing cloud backup. The app fetches the key slot from your destination and unlocks the backup key.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnlockPassDialog = false
                        viewModel.unlockOrVerifyPassphrase(pass)
                    },
                    enabled = pass.isNotEmpty()
                ) { Text(if (passphraseEnabled) "Verify" else "Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockPassDialog = false }) { Text("Cancel") }
            }
        )
    }
    val backupBuckets by viewModel.backupBuckets.collectAsState()
    val availableBuckets by viewModel.availableBuckets.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val backupEnabled by viewModel.backupEnabled.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            when (authState) {
                is TdApi.AuthorizationStateReady -> {
                    Text(
                        "Connected${accountName?.let { " as $it" } ?: ""}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Backed up: $backedUp · Pending: $pending",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup destination")
                            Text(
                                destTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            viewModel.loadGroups()
                            showDestDialog = true
                        }) { Text("Change") }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text("Auto backup (every 12 h)", modifier = Modifier.weight(1f))
                        Switch(checked = backupEnabled, onCheckedChange = { viewModel.setBackupEnabled(it) })
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wi-Fi only", modifier = Modifier.weight(1f))
                        Switch(checked = wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) })
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Only while charging", modifier = Modifier.weight(1f))
                        Switch(checked = chargingOnly, onCheckedChange = { viewModel.setChargingOnly(it) })
                    }
                    Text(
                        "Backup scope",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setBackupScope("ALL") }
                    ) {
                        RadioButton(selected = backupScope == "ALL", onClick = { viewModel.setBackupScope("ALL") })
                        Text("All photos & videos on this device")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setBackupScope("SELECTED") }
                    ) {
                        RadioButton(selected = backupScope == "SELECTED", onClick = { viewModel.setBackupScope("SELECTED") })
                        Text("Only chosen folders")
                    }
                    if (backupScope == "SELECTED") {
                        if (availableBuckets.isEmpty()) {
                            Text(
                                "No folders found yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                            )
                        }
                        for (bucket in availableBuckets) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setBucketIncluded(bucket, bucket !in backupBuckets) }
                                    .padding(start = 16.dp)
                            ) {
                                Checkbox(
                                    checked = bucket in backupBuckets,
                                    onCheckedChange = { viewModel.setBucketIncluded(bucket, it) }
                                )
                                Text(bucket)
                            }
                        }
                    }
                    if (progress.running || progress.total > 0) {
                        val pct = if (progress.total > 0)
                            (progress.done * 100) / progress.total else 0
                        Text(
                            "${progress.done} of ${progress.total} · $pct%",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (progress.total > 0)
                                    progress.done.toFloat() / progress.total else 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                        val remaining = progress.bytesTotal - progress.bytesDone
                        Text(
                            listOfNotNull(
                                progress.currentName.takeIf { it.isNotBlank() },
                                if (progress.bytesTotal > 0)
                                    "remaining: ${MediaSort.sizeLabel(remaining)}" else null,
                                if (progress.bytesPerSec > 0)
                                    BackupProgress.formatSpeed(progress.bytesPerSec) else null,
                                BackupProgress.formatEta(progress.etaSeconds)
                                    .takeIf { it.isNotEmpty() }?.let { "$it left" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (progress.running) {
                            OutlinedButton(
                                onClick = { viewModel.toggleUploadPause() },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(if (progress.paused) "Resume uploads" else "Pause uploads")
                            }
                        }
                    }
                    if (failed.isNotEmpty()) {
                        Text(
                            "Failed (${failed.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        for (entry in failed.take(20)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(
                                        entry.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.retryFailed() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Retry failed") }
                    }
                    val (freeBytes, freeCount) = deletableBytes
                    if (freeCount > 0) {
                        OutlinedButton(
                            onClick = { showFreeUpDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Free up space (${MediaSort.sizeLabel(freeBytes)})") }
                    }
                    Button(
                        onClick = { backUpWithPermission() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Back up now") }
                    OutlinedButton(
                        onClick = { viewModel.verify() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Verify cloud copies") }
                    OutlinedButton(
                        onClick = { viewModel.importIndex() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Import index from cloud") }
                    OutlinedButton(
                        onClick = { viewModel.rebuildFromCloud() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Find files already in the cloud") }
                    OutlinedButton(
                        onClick = {
                            val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                                .format(java.util.Date())
                            exportLauncher.launch("8cloudgallery-backup-$day.json")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Export settings + index to file") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Import settings + index from file") }
                    Text(
                        if (passphraseEnabled) "Backup passphrase: set (survives reinstall)"
                        else "Backup passphrase: not set - cloud backup dies with this install",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (passphraseEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    if (!passphraseEnabled) {
                        OutlinedButton(
                            onClick = { showSetPassDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) { Text("Set passphrase (new backup)") }
                    }
                    OutlinedButton(
                        onClick = { showUnlockPassDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) { Text(if (passphraseEnabled) "Verify passphrase" else "Unlock with passphrase") }
                }
                is TdApi.AuthorizationStateWaitPhoneNumber, null -> {
                    if (viewModel.credentialsMissing) {
                        Text(
                            "Telegram API credentials are not configured in this build yet.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        PhoneStep(busy) { viewModel.sendPhone(it) }
                    }
                }
                is TdApi.AuthorizationStateWaitCode -> {
                    CodeStep(busy) { viewModel.sendCode(it) }
                }
                is TdApi.AuthorizationStateWaitPassword -> {
                    PasswordStep(busy) { viewModel.sendPassword(it) }
                }
                else -> {
                    Text("Connecting to Telegram...", style = MaterialTheme.typography.bodyMedium)
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun PhoneStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    Text("Log in with the Telegram account that stores your backup (your second account).", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Phone number (+43...)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    )
    Button(
        onClick = { onSubmit(phone) },
        enabled = !busy && phone.length >= 6,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) { Text(if (busy) "Sending..." else "Send code") }
}

@Composable
private fun CodeStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text("Enter the code Telegram sent to that account.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Code") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    )
    Button(
        onClick = { onSubmit(code) },
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) { Text(if (busy) "Checking..." else "Log in") }
}

@Composable
private fun PasswordStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Text("This account has two-step verification. Enter the password.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("2FA password") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    )
    Button(
        onClick = { onSubmit(password) },
        enabled = !busy && password.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) { Text(if (busy) "Checking..." else "Unlock") }
}
