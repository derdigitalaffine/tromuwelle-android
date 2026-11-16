package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap

private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Native vector map of a selected US airport with bounded OpenSky positions.
 * Only renders metadata fetched by Tromuwelle's server; no browser WebView,
 * secret API key, client-side OpenSky polling, or background location request.
 */
@Composable
fun AirwavesAirportMap(latitude: Double, longitude: Double,
                       aircraft: List<JSONObject>,
                       modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var readyMap by remember(latitude, longitude) {
        mutableStateOf<MapLibreMap?>(null)
    }
    val markers = remember(latitude, longitude) { mutableListOf<Marker>() }
    val mapView = remember(latitude, longitude) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(mapView, lifecycleOwner) {
        mapView.getMapAsync { map ->
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(10.8)
                .build()
            map.setStyle(OPENFREEMAP_STYLE) { readyMap = map }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            markers.clear()
            readyMap = null
            mapView.onDestroy()
        }
    }
    LaunchedEffect(readyMap, aircraft) {
        val map = readyMap ?: return@LaunchedEffect
        markers.forEach { map.removeMarker(it) }
        markers.clear()
        for (plane in aircraft.take(80)) {
            val lat = plane.optDouble("lat", Double.NaN)
            val lon = plane.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val marker = map.addMarker(MarkerOptions()
                .position(LatLng(lat, lon))
                .title(plane.optString("callsign")
                    .ifBlank { plane.optString("icao24") })
                .snippet("ADS-B · ${plane.optInt("altitude_m")} m"))
            markers.add(marker)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AndroidView(factory = { mapView },
            modifier = Modifier.fillMaxWidth().height(265.dp))
        Text("Karte: OpenFreeMap / OpenStreetMap · Flugpositionen: OpenSky (Servercache)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
