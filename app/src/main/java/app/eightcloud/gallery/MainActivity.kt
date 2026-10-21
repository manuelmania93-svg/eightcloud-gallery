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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import app.eightcloud.gallery.ui.AlbumsScreen
import app.eightcloud.gallery.ui.DocumentsScreen
import app.eightcloud.gallery.ui.DuplicatesScreen
import app.eightcloud.gallery.ui.EditScreen
import app.eightcloud.gallery.ui.MapScreen
import app.eightcloud.gallery.ui.RestoreScreen
import app.eightcloud.gallery.ui.TrashScreen
import app.eightcloud.gallery.ui.BackupScreen
import app.eightcloud.gallery.ui.CleanupScreen
import app.eightcloud.gallery.ui.BackupViewModel
import app.eightcloud.gallery.ui.EightCloudTheme
import app.eightcloud.gallery.ui.GalleryViewModel
import app.eightcloud.gallery.ui.GridScreen
import app.eightcloud.gallery.ui.Screen
import app.eightcloud.gallery.ui.SecureFolderScreen
import app.eightcloud.gallery.ui.SettingsScreen
import app.eightcloud.gallery.ui.SlideshowScreen
import app.eightcloud.gallery.ui.StorageStatsScreen
import app.eightcloud.gallery.ui.ThemeMode
import app.eightcloud.gallery.ui.ViewerScreen

class MainActivity : androidx.fragment.app.FragmentActivity() {

    /** Re-locks the app (when app lock is on) as soon as the screen turns off. */
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != android.content.Intent.ACTION_SCREEN_OFF) return
            val lockOn = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("appLock", false)
            if (lockOn) {
                androidx.lifecycle.ViewModelProvider(this@MainActivity)[app.eightcloud.gallery.ui.GalleryViewModel::class.java]
                    .lockApp()
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        AppLog.init(this)
        AppLog.log("app", "start v" + BuildConfig.VERSION_NAME + " on " +
            android.os.Build.MODEL + " API " + android.os.Build.VERSION.SDK_INT)
        AppLog.recordLastExitReasons(this)
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("deprecated in Android")
            override fun onLowMemory() { AppLog.log("memory", "onLowMemory") }
            override fun onTrimMemory(level: Int) {
                if (level >= TRIM_MEMORY_RUNNING_LOW) AppLog.log("memory", "onTrimMemory level=" + level)
            }
        })
        registerReceiver(
            screenOffReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        )
        enableEdgeToEdge()
        // VideoFrameDecoder so video thumbnails actually render in the grid.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                // Cap the bitmap memory cache: with 40k+ items, aggressive
                // caching plus video frame decodes can OOM during fast
                // scrolling.
                .memoryCache {
                    coil.memory.MemoryCache.Builder(this)
                        .maxSizePercent(0.20)
                        .build()
                }
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
    val appLock by viewModel.appLock.collectAsState()
    val appUnlocked by viewModel.appUnlocked.collectAsState()
    val privacyScreen by viewModel.privacyScreen.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()
    val screen by viewModel.screen.collectAsState()
    val context = LocalContext.current

    // Privacy screen: hides the app in recents and blocks screenshots.
    LaunchedEffect(privacyScreen, screen) {
        (context as? android.app.Activity)?.window?.let { w ->
            if (privacyScreen || screen == app.eightcloud.gallery.ui.Screen.SECURE) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

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
            if (granted && appLock && !appUnlocked) {
                LockScreen(onUnlock = {
                    runCatching {
                        val executor = ContextCompat.getMainExecutor(context)
                        val prompt = androidx.biometric.BiometricPrompt(
                            context as androidx.fragment.app.FragmentActivity,
                            executor,
                            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(
                                    result: androidx.biometric.BiometricPrompt.AuthenticationResult
                                ) {
                                    viewModel.unlockApp()
                                }
                            }
                        )
                        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Unlock 8CloudGallery")
                            .setDeviceCredentialAllowed(true)
                            .build()
                        prompt.authenticate(info)
                    }.onFailure {
                        // No working biometrics/screen lock on this device:
                        // unlock rather than crash-lock the user out.
                        viewModel.unlockApp()
                    }
                })
            } else if (granted) {
                androidx.compose.material3.Scaffold(
                    bottomBar = {
                        if (screen != Screen.VIEWER) {
                            androidx.compose.material3.NavigationBar {
                                NavigationBarItem(
                                    selected = screen == Screen.GRID,
                                    onClick = { viewModel.backToGrid() },
                                    icon = {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.Filled.Image,
                                            contentDescription = "Photos"
                                        )
                                    },
                                    label = { Text("Photos") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.ALBUMS,
                                    onClick = { viewModel.openAlbums() },
                                    icon = {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.Filled.Folder,
                                            contentDescription = "Albums"
                                        )
                                    },
                                    label = { Text("Albums") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.BACKUP,
                                    onClick = { viewModel.openBackup() },
                                    icon = {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.Filled.Cloud,
                                            contentDescription = "Cloud"
                                        )
                                    },
                                    label = { Text("Cloud") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.SETTINGS,
                                    onClick = { viewModel.openSettings() },
                                    icon = {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.Filled.Settings,
                                            contentDescription = "Settings"
                                        )
                                    },
                                    label = { Text("Settings") }
                                )
                            }
                        }
                    }
                ) { navPadding ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(navPadding)
                    ) {
                        when (screen) {
                            Screen.GRID -> GridScreen(viewModel)
                            Screen.VIEWER -> ViewerScreen(viewModel)
                            Screen.ALBUMS -> AlbumsScreen(viewModel)
                            Screen.TRASH -> TrashScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.DUPLICATES -> DuplicatesScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.RESTORE -> {
                                val backupViewModel: BackupViewModel = viewModel()
                                RestoreScreen(backupViewModel, onBack = { viewModel.backToGrid() })
                            }
                            Screen.SETTINGS -> SettingsScreen(viewModel)
                            Screen.STORAGE_STATS -> StorageStatsScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.CLEANUP -> CleanupScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.SLIDESHOW -> SlideshowScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.EDIT -> EditScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.MAP -> MapScreen(viewModel, onBack = { viewModel.backToGrid() })
                            Screen.DOCUMENTS -> DocumentsScreen(onBack = { viewModel.backToGrid() })
                            Screen.SECURE -> SecureFolderScreen(
                                viewModel,
                                onBack = { viewModel.backToGrid() },
                                onRequestUnlock = {
                                    runCatching {
                                        val executor = ContextCompat.getMainExecutor(context)
                                        val prompt = androidx.biometric.BiometricPrompt(
                                            context as androidx.fragment.app.FragmentActivity,
                                            executor,
                                            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                                                override fun onAuthenticationSucceeded(
                                                    result: androidx.biometric.BiometricPrompt.AuthenticationResult
                                                ) {
                                                    viewModel.unlockSecure()
                                                }
                                            }
                                        )
                                        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                                            .setTitle("Unlock secure folder")
                                            .setDeviceCredentialAllowed(true)
                                            .build()
                                        prompt.authenticate(info)
                                    }.onFailure {
                                        // No biometrics/screen lock on this device.
                                        viewModel.unlockSecure()
                                    }
                                }
                            )
                            Screen.BACKUP -> {
                                val backupViewModel: BackupViewModel = viewModel()
                                BackupScreen(backupViewModel, onBack = { viewModel.backToGrid() })
                            }
                        }
                    }
                }
            } else {
                PermissionRequest(onRequest = { launcher.launch(requiredPermissions()) })
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "8CloudGallery is locked",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) {
            Text("Unlock")
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
