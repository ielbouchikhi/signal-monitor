package com.signalmonitor.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.signalmonitor.data.quality.NetworkQualityScore
import com.signalmonitor.ui.dashboard.nqsColor
import com.signalmonitor.ui.map.qualityColor

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Statistics", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // ── Range selector ────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Loading spinner while SQL aggregates are computing
            if (state.isLoading) {
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (state.totalSamples == 0) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Text("No data yet. Enable monitoring to collect samples.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            // ── Overview cards ─────────────────────────────────────────────────
            Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Avg NQS", state.avgNqs?.let { "$it / 100  (${NetworkQualityScore.label(it)})" } ?: "—",
                    valueColor = nqsColor(state.avgNqs), modifier = Modifier.weight(1f))
                StatCard("Samples", "${state.totalSamples}", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Avg RSRP", state.avgRsrp?.let { "${it} dBm" } ?: "—",
                    valueColor = qualityColor(state.avgRsrp), modifier = Modifier.weight(1f))
                StatCard("Avg SINR", state.avgSinr?.let { "${it} dB" } ?: "—", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Avg Latency", state.avgLatencyMs?.let { "${it} ms" } ?: "—", modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(20.dp))

            // ── Network type breakdown ─────────────────────────────────────────
            Text("Network type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.networkBreakdown.forEach { entry ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(entry.type, style = MaterialTheme.typography.bodyMedium)
                                Text("%.0f%%  (${entry.count})".format(entry.pct),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { entry.pct / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = networkTypeColor(entry.type),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Hourly RSRP averages ───────────────────────────────────────────
            Text("RSRP by hour of day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.hourlyAvg
                        .filter { it.avgRsrp != null }
                        .forEach { h ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("%02d:00".format(h.hour),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.width(48.dp))
                                LinearProgressIndicator(
                                    progress = { rsrpToProgress(h.avgRsrp) },
                                    modifier = Modifier.weight(1f),
                                    color = qualityColor(h.avgRsrp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("${h.avgRsrp} dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(64.dp))
                            }
                        }
                    if (state.hourlyAvg.all { it.avgRsrp == null }) {
                        Text("No data with timestamps yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Dead zones ─────────────────────────────────────────────────────
            Text("Dead zones  (avg RSRP < −100 dBm)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (state.deadZones.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No dead zones detected in this period.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.deadZones.forEachIndexed { i, dz ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("#${i + 1}  %.4f, %.4f".format(dz.lat, dz.lng),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${dz.sampleCount} samples",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${dz.avgRsrp} dBm",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFF44336),
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun networkTypeColor(type: String): Color = when (type) {
    "5G_SA"  -> Color(0xFF4CAF50)
    "5G_NSA" -> Color(0xFF8BC34A)
    "LTE"    -> Color(0xFF2196F3)
    else     -> Color.Gray
}

// Map RSRP (-140 to -44) to 0..1 progress (inverted: better = more)
private fun rsrpToProgress(rsrp: Int?): Float {
    if (rsrp == null) return 0f
    return ((rsrp - (-140f)) / (-44f - (-140f))).coerceIn(0f, 1f)
}
