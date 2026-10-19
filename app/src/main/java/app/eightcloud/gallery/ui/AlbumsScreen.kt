package app.eightcloud.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(viewModel: GalleryViewModel) {
    val bucketCounts by viewModel.bucketCounts.collectAsState()
    val smartAlbums by viewModel.smartAlbums.collectAsState()
    val monthAlbums by viewModel.monthAlbums.collectAsState()
    val featureToggles by viewModel.featureToggles.collectAsState()
    val buckets = bucketCounts.entries.sortedByDescending { it.value }
    val showAuto = featureToggles["feat_auto_albums"] != false
    val categoryAlbums by viewModel.categoryAlbums.collectAsState()
    val labelScanProgress by viewModel.labelScanProgress.collectAsState()
    val personAlbums by viewModel.personAlbums.collectAsState()
    val faceScanProgress by viewModel.faceScanProgress.collectAsState()
    val showPeople = featureToggles["feat_face_grouping"] != false
    var renamingPerson by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PersonAlbum?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(showPeople) {
        viewModel.scanLabels()
        if (showPeople) viewModel.scanFaces()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Albums") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (featureToggles["feat_documents"] != false) {
                item(key = "row-documents") {
                    AlbumRow(
                        title = "Documents",
                        count = -1,
                        onClick = { viewModel.openDocuments() }
                    )
                    HorizontalDivider()
                }
            }
            item(key = "hdr-cats") {
                Text(
                    "Categories" + labelScanProgress.let { p ->
                        if (p != null) " (analyzing ${p.first}/${p.second})" else ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(categoryAlbums, key = { it.key }) { album ->
                AlbumRow(
                    title = album.title,
                    count = album.count,
                    onClick = {
                        viewModel.setSmartFilter(album.key)
                        viewModel.backToGrid()
                    }
                )
                HorizontalDivider()
            }
            if (showPeople && (personAlbums.isNotEmpty() || faceScanProgress != null)) {
                item(key = "hdr-people") {
                    Text(
                        "People" + faceScanProgress.let { p ->
                            if (p != null) " (analyzing ${p.first}/${p.second})" else ""
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(personAlbums, key = { "person-" + it.id }) { person ->
                    PersonRow(
                        person = person,
                        onClick = {
                            viewModel.setSmartFilter("person:" + person.id)
                            viewModel.backToGrid()
                        },
                        onLongClick = { renamingPerson = person }
                    )
                    HorizontalDivider()
                }
            }
            if (showAuto && smartAlbums.isNotEmpty()) {
                item(key = "hdr-smart") {
                    Text(
                        "Smart albums",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(smartAlbums, key = { it.key }) { album ->
                    AlbumRow(
                        title = album.title,
                        count = album.count,
                        onClick = {
                            viewModel.setSmartFilter(album.key)
                            viewModel.backToGrid()
                        }
                    )
                    HorizontalDivider()
                }
            }
            if (showAuto && monthAlbums.isNotEmpty()) {
                item(key = "hdr-months") {
                    Text(
                        "By month",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(monthAlbums, key = { it.key }) { album ->
                    AlbumRow(
                        title = album.title,
                        count = album.count,
                        onClick = {
                            viewModel.setSmartFilter(album.key)
                            viewModel.backToGrid()
                        }
                    )
                    HorizontalDivider()
                }
            }
            item(key = "hdr-folders") {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(buckets, key = { it.key }) { entry ->
                AlbumRow(
                    title = entry.key,
                    count = entry.value,
                    onClick = {
                        viewModel.setBucketFilter(entry.key)
                        viewModel.backToGrid()
                    }
                )
                HorizontalDivider()
            }
        }
    }

    renamingPerson?.let { person ->
        var name by remember(person.id) { mutableStateOf(if (person.title.startsWith("Person ")) "" else person.title) }
        AlertDialog(
            onDismissRequest = { renamingPerson = null },
            title = { Text("Name this person") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name (stored on this device only)") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renamePerson(person.id, name)
                    renamingPerson = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingPerson = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(person: PersonAlbum, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cover = remember(person.id, person.coverJpeg) {
            person.coverJpeg?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = person.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(person.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${person.count} photos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AlbumRow(title: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (count < 0) "PDFs and other files on this phone" else "$count items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
