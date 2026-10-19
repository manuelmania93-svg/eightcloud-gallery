package app.eightcloud.gallery.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Items hidden from the main grid. The list is kept in Room and only shown
 * after the device unlock (biometric or screen lock) for this session.
 * The files themselves stay in MediaStore - other gallery apps can still
 * see them; this is app-level privacy, not file-level encryption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureFolderScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onRequestUnlock: () -> Unit
) {
    val secureItems by viewModel.secureItems.collectAsState()
    val unlocked by viewModel.secureUnlocked.collectAsState()
    var selected by remember { mutableStateOf(setOf<Long>()) }

    if (!unlocked) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(
                "Secure folder",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "Unlock to see your hidden items.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = onRequestUnlock, modifier = Modifier.padding(top = 24.dp)) {
                Text("Unlock")
            }
            Text(
                "Note: files are hidden in this app only.",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) "Secure folder (${secureItems.size})"
                        else "${selected.size} selected"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected.isNotEmpty()) selected = emptySet() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.removeFromSecure(selected)
                            selected = emptySet()
                        }) {
                            Icon(Icons.Filled.LockOpen, contentDescription = "Remove from secure folder")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (secureItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Text(
                    "Nothing hidden yet.\nSelect items in the gallery and tap the lock.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(secureItems, key = { it.id }) { item ->
                    val isSelected = item.id in selected
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                selected = if (isSelected) selected - item.id else selected + item.id
                            }
                    ) {
                        AsyncImage(
                            model = Uri.parse(item.uri),
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
