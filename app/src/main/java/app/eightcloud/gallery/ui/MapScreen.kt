package app.eightcloud.gallery.ui

import android.preference.PreferenceManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Photos with GPS data on an OpenStreetMap map. Tap a pin to open the photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsState()
    val geoPoints by viewModel.geoPoints.collectAsState()
    val geoScanning by viewModel.geoScanning.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.scanGeo() }
    val byId = items.associateBy { it.id }
    val geoItems = geoPoints.mapNotNull { p -> byId[p.mediaStoreId]?.let { it to p } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map (${geoItems.size} photos" + (if (geoScanning) ", scanning..." else "") + ")") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (geoItems.isEmpty() && !geoScanning) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Text(
                    "No photos with location data yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().load(
                        ctx, PreferenceManager.getDefaultSharedPreferences(ctx)
                    )
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        val points = geoItems.map { (_, p) -> GeoPoint(p.lat, p.lon) }
                        val center = GeoPoint(
                            points.map { it.latitude }.average(),
                            points.map { it.longitude }.average()
                        )
                        controller.setZoom(6.0)
                        controller.setCenter(center)
                        geoItems.forEach { (item, p) ->
                            val marker = Marker(this)
                            marker.position = GeoPoint(p.lat, p.lon)
                            marker.title = item.name
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            marker.setOnMarkerClickListener { _, _ ->
                                val idx = viewModel.items.value.indexOfFirst { it.id == item.id }
                                if (idx >= 0) viewModel.openViewer(idx)
                                true
                            }
                            overlays.add(marker)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                update = { map ->
                    map.overlays.clear()
                    geoItems.forEach { (item, p) ->
                        val marker = Marker(map)
                        marker.position = GeoPoint(p.lat, p.lon)
                        marker.title = item.name
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        marker.setOnMarkerClickListener { _, _ ->
                            val idx = viewModel.items.value.indexOfFirst { it.id == item.id }
                            if (idx >= 0) viewModel.openViewer(idx)
                            true
                        }
                        map.overlays.add(marker)
                    }
                    if (geoItems.isNotEmpty()) {
                        val lats = geoItems.map { it.second.lat }
                        val lons = geoItems.map { it.second.lon }
                        map.controller.setCenter(GeoPoint(lats.average(), lons.average()))
                    }
                    map.invalidate()
                }
            )
        }
    }
}
