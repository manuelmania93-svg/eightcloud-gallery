package app.eightcloud.gallery

import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.eightcloud.gallery.ui.BackupScreen
import app.eightcloud.gallery.ui.BackupViewModel
import app.eightcloud.gallery.ui.EightCloudTheme
import app.eightcloud.gallery.ui.GalleryViewModel
import app.eightcloud.gallery.ui.GridScreen
import app.eightcloud.gallery.ui.Screen
import app.eightcloud.gallery.ui.SettingsScreen
import app.eightcloud.gallery.ui.ThemeMode
import app.eightcloud.gallery.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // VideoFrameDecoder so video thumbnails actually render in the grid.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()
        )
        setContent {
            EightCloudApp()
        }
    }
}

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun EightCloudApp() {
    val viewModel: GalleryViewModel = viewModel()
    val themeMode by viewModel.themeMode.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()
    val screen by viewModel.screen.collectAsState()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.setPermissionGranted(result.values.any { it })
    }

    EightCloudTheme(themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val granted = permissionGranted || requiredPermissions().any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            LaunchedEffect(granted) {
                if (granted && !permissionGranted) viewModel.setPermissionGranted(true)
            }
            if (granted) {
                when (screen) {
                    Screen.GRID -> GridScreen(viewModel)
                    Screen.VIEWER -> ViewerScreen(viewModel)
                    Screen.SETTINGS -> SettingsScreen(viewModel)
                    Screen.BACKUP -> {
                        val backupViewModel: BackupViewModel = viewModel()
                        BackupScreen(backupViewModel, onBack = { viewModel.backToGrid() })
                    }
                }
            } else {
                PermissionRequest(onRequest = { launcher.launch(requiredPermissions()) })
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "8CloudGallery needs access to your photos and videos to show your gallery.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text("Grant access")
        }
    }
}
