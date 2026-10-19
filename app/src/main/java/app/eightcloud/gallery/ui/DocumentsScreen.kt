package app.eightcloud.gallery.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One document file found on shared storage. */
data class DocFile(val path: String, val name: String, val size: Long, val modified: Long, val ext: String)

private val DOC_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "csv", "rtf", "epub"
)

internal fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

/** Walks shared storage for documents. Skips Android/ and hidden folders. */
internal fun scanDocuments(root: File): List<DocFile> {
    val out = mutableListOf<DocFile>()
    fun walk(dir: File, depth: Int) {
        if (depth > 12) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && name == "Android") continue
                walk(f, depth + 1)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in DOC_EXTENSIONS) {
                    out += DocFile(f.absolutePath, name, f.length(), f.lastModified(), ext)
                }
            }
        }
    }
    walk(root, 0)
    return out.sortedByDescending { it.modified }
}

private fun mimeFor(ext: String): String = when (ext) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt" -> "text/plain"
    "csv" -> "text/csv"
    "epub" -> "application/epub+zip"
    else -> "*/*"
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasAllFilesAccess()) }
    var docs by remember { mutableStateOf<List<DocFile>?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(allowed, reload) {
        if (allowed) {
            docs = withContext(Dispatchers.IO) {
                runCatching { scanDocuments(Environment.getExternalStorageDirectory()) }
                    .getOrDefault(emptyList())
            }
        }
    }
    // Re-check the permission when the user comes back from the system settings page.
    LaunchedEffect(Unit) {
        while (!allowed) {
            kotlinx.coroutines.delay(1000)
            allowed = hasAllFilesAccess()
        }
    }

    fun open(doc: DocFile, share: Boolean) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", File(doc.path)
            )
            val mime = mimeFor(doc.ext)
            val intent = if (share) {
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, doc.name
                )
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(intent)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Documents") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { innerPadding ->
        if (!allowed) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
                Text("All files access needed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Android only lets apps list PDFs and other documents with the All files access permission. The app only reads file names and opens them when you tap; nothing is uploaded unless you back documents up.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                }) { Text("Open settings") }
            }
        } else {
            val list = docs
            val shown = if (list == null) emptyList() else list.filter {
                query.isBlank() || it.name.contains(query, ignoreCase = true)
            }
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search documents") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (list == null) "Scanning..." else "${shown.size} of ${list.size} documents",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = { reload++ }) { Text("Rescan") }
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { doc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open(doc, false) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    doc.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    doc.ext.uppercase() + " - " + sizeText(doc.size) + " - " +
                                        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                                            .format(java.util.Date(doc.modified)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { open(doc, true) }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
