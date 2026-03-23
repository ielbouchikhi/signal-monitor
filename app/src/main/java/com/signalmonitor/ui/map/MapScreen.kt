package com.signalmonitor.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.quality.NetworkQualityScore
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val samples by viewModel.samples.collectAsState()
    val displaySamples by viewModel.displaySamples.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val showDeadZones by viewModel.showDeadZones.collectAsState()
    val deadZones by viewModel.deadZones.collectAsState()
    val selectedSample by viewModel.selectedSample.collectAsState()

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(20.0, 0.0), 2f)
    }

    // Auto-fit to data bounds once per range selection, as soon as data arrives.
    // Cancels and restarts whenever selectedRange changes.
    LaunchedEffect(selectedRange) {
        val firstBatch = viewModel.displaySamples
            .filter { it.isNotEmpty() }
            .first()
        val boundsBuilder = LatLngBounds.Builder()
        firstBatch.forEach { boundsBuilder.include(LatLng(it.latitude!!, it.longitude!!)) }
        try {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120),
                durationMs = 600,
            )
        } catch (_: Exception) {
            // Single point — zoom in directly
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(firstBatch.first().latitude!!, firstBatch.first().longitude!!), 14f
                )
            )
        }
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Map ───────────────────────────────────────────────────────────────
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(zoomControlsEnabled = true),
            onMapClick = { latLng ->
                val nearest = viewModel.findNearest(latLng.latitude, latLng.longitude)
                if (nearest != null) {
                    viewModel.selectSample(nearest)
                    scope.launch { sheetState.show() }
                }
            },
        ) {
            when (viewMode) {
                MapViewMode.CIRCLES -> {
                    displaySamples.forEach { sample ->
                        val color = qualityColor(sample)
                        Circle(
                            center = LatLng(sample.latitude!!, sample.longitude!!),
                            radius = 80.0,
                            fillColor = color.copy(alpha = 0.45f),
                            strokeColor = color,
                            strokeWidth = 2f,
                        )
                    }
                }
                MapViewMode.HEATMAP -> {
                    if (samples.isNotEmpty()) {
                        val weightedData = samples.map { s ->
                            val nqs = NetworkQualityScore.compute(s)
                            val weight = when {
                                nqs == null -> 0.3
                                nqs >= 60   -> 0.1   // good = low heat
                                nqs >= 40   -> 0.5
                                else        -> 1.0   // poor = high heat
                            }
                            WeightedLatLng(LatLng(s.latitude!!, s.longitude!!), weight)
                        }
                        val provider = HeatmapTileProvider.Builder()
                            .weightedData(weightedData)
                            .build()
                        TileOverlay(tileProvider = provider)
                    }
                }
                MapViewMode.CLUSTERS -> {
                    Clustering(
                        items = displaySamples.map { MapSampleItem(it) },
                        onClusterItemClick = { item ->
                            viewModel.selectSample(item.sample)
                            scope.launch { sheetState.show() }
                            false
                        },
                        clusterItemContent = { item ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(
                                        qualityColor(item.sample.rsrp),
                                        shape = RoundedCornerShape(7.dp)
                                    )
                            )
                        },
                    )
                }
            }

            // Dead zone overlay (works in all modes)
            if (showDeadZones) {
                deadZones.forEach { dz ->
                    Circle(
                        center = LatLng(dz.lat, dz.lng),
                        radius = 120.0,
                        fillColor = Color(0x88F44336),
                        strokeColor = Color(0xFFF44336),
                        strokeWidth = 3f,
                    )
                }
            }
        }

        // ── Top controls ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Time range
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MapRange.entries.forEach { range ->
                        FilterChip(
                            selected = selectedRange == range,
                            onClick = { viewModel.selectRange(range) },
                            label = { Text(range.label) },
                        )
                    }
                }
            }
            // View mode
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MapViewMode.entries.forEach { mode ->
                        FilterChip(
                            selected = viewMode == mode,
                            onClick = { viewModel.selectViewMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                    FilterChip(
                        selected = showDeadZones,
                        onClick = { viewModel.toggleDeadZones() },
                        label = { Text("Dead Zones") },
                    )
                }
            }
        }

        // ── Quality legend (bottom-left) ──────────────────────────────────────
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Network Quality (NQS)", style = MaterialTheme.typography.labelSmall)
                LegendRow(Color(0xFF4CAF50), "Excellent  (≥ 80)")
                LegendRow(Color(0xFF8BC34A), "Good  (60–79)")
                LegendRow(Color(0xFFFF9800), "Fair  (40–59)")
                LegendRow(Color(0xFFEF6C00), "Poor  (20–39)")
                LegendRow(Color(0xFFF44336), "Bad  (< 20)")
                LegendRow(Color.Gray,        "No data")
            }
        }

        // ── Empty state ───────────────────────────────────────────────────────
        if (samples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                ) {
                    Text(
                        text = "No location data yet.\nEnable monitoring to start collecting.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    // ── Sample detail bottom sheet ─────────────────────────────────────────
    if (selectedSample != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.selectSample(null) },
            sheetState = sheetState,
        ) {
            SampleDetailSheet(
                sample = selectedSample!!,
                onDismiss = {
                    scope.launch { sheetState.hide() }
                    viewModel.selectSample(null)
                },
            )
        }
    }
}

@Composable
private fun SampleDetailSheet(sample: MetricSample, onDismiss: () -> Unit) {
    val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm:ss", Locale.getDefault())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sample detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        Text(sdf.format(Date(sample.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        val nqs = NetworkQualityScore.compute(sample)
        DetailRow("NQS", nqs?.let { "$it / 100  (${NetworkQualityScore.label(it)})" } ?: "—")
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DetailRow("Network", sample.networkType)
        sample.band?.let { DetailRow("Band", "$it") }
        DetailRow("RSRP", sample.rsrp?.let { "$it dBm" } ?: "—")
        DetailRow("SINR", sample.sinr?.let { "$it dB" } ?: "—")
        DetailRow("RSRQ", sample.rsrq?.let { "$it dB" } ?: "—")
        DetailRow("Latency", sample.latencyMs?.let { "$it ms" } ?: "—")
        DetailRow("Download", sample.downloadMbps?.let { "%.2f Mbps".format(it) } ?: "—")
        sample.probeDownloadMbps?.let { DetailRow("Probe DL", "%.2f Mbps".format(it)) }
        sample.latitude?.let { lat -> sample.longitude?.let { lng -> DetailRow("Location", "%.5f, %.5f".format(lat, lng)) } }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Colour a sample by its composite NQS (falls back to RSRP-only when NQS is null). */
fun qualityColor(sample: MetricSample): Color {
    val nqs = NetworkQualityScore.compute(sample)
    return qualityColor(nqs)
}

fun qualityColor(rsrp: Int?): Color = when {
    rsrp == null  -> Color.Gray
    rsrp >= 80    -> Color(0xFF4CAF50)   // also works as NQS score
    rsrp >= 60    -> Color(0xFF8BC34A)
    rsrp >= 40    -> Color(0xFFFF9800)
    rsrp >= 20    -> Color(0xFFEF6C00)
    rsrp >= -85   -> Color(0xFF4CAF50)   // legacy RSRP path (negative values)
    rsrp >= -100  -> Color(0xFFFF9800)
    else          -> Color(0xFFF44336)
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(11.dp).background(color, shape = RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
