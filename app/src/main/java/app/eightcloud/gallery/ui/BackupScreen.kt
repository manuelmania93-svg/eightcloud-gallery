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
                    Button(
                        onClick = { viewModel.backUpNow() },
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
