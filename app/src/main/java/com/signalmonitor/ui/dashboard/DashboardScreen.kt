package com.signalmonitor.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.text.font.FontWeight
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.quality.NetworkQualityScore
import com.signalmonitor.ui.chart.MetricKey
import com.signalmonitor.ui.theme.ChartNqs
import com.signalmonitor.ui.components.ChartPoint
import com.signalmonitor.ui.components.MetricCard
import com.signalmonitor.ui.theme.ChartLatency
import com.signalmonitor.ui.theme.ChartRsrp
import com.signalmonitor.ui.theme.ChartSinr
import com.signalmonitor.ui.theme.ChartThroughput
import com.signalmonitor.ui.theme.QualityBad
import com.signalmonitor.ui.theme.QualityExcellent
import com.signalmonitor.ui.theme.QualityFair
import com.signalmonitor.ui.theme.QualityGood
import com.signalmonitor.ui.theme.QualityPoor

@Composable
fun DashboardScreen(
    onChartClick: (MetricKey) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val monitoring by viewModel.monitoringEnabled.collectAsState()
    val paused by viewModel.loggingPaused.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Signal Monitor", style = MaterialTheme.typography.titleLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NetworkBadge(state.networkType)
                        if (state.band != "—") {
                            Text(
                                "Band ${state.band}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (monitoring) {
                        IconButton(onClick = {
                            if (paused) viewModel.resumeLogging() else viewModel.pauseLogging()
                        }) {
                            Icon(
                                imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (paused) "Resume logging" else "Pause logging",
                            )
                        }
                    }
                    Text(
                        when {
                            !monitoring -> "Off"
                            paused -> "Paused"
                            else -> "On"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = monitoring,
                        onCheckedChange = { viewModel.toggleMonitoring(it) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── NQS gauge ─────────────────────────────────────────────────────
            NqsGauge(nqs = state.nqs, samples = state.recentSamples)

            Spacer(Modifier.height(12.dp))

            // ── Metric cards ─────────────────────────────────────────────────
            val samples = state.recentSamples

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // RSRP
                item {
                    MetricCard(
                        label = "RSRP  (Signal Strength)",
                        value = state.rsrp?.toString() ?: "—",
                        unit = "dBm",
                        qualityColor = rsrpQuality(state.rsrp),
                        sparkPoints = samples.map { s ->
                            s.rsrp?.let { ChartPoint(s.timestamp, it.toFloat()) }
                        },
                        lineColor = ChartRsrp,
                        onClick = { onChartClick(MetricKey.RSRP) },
                    )
                }
                // SINR
                item {
                    MetricCard(
                        label = "SINR  (Signal Quality)",
                        value = state.sinr?.toString() ?: "—",
                        unit = "dB",
                        qualityColor = sinrQuality(state.sinr),
                        sparkPoints = samples.map { s ->
                            s.sinr?.let { ChartPoint(s.timestamp, it.toFloat()) }
                        },
                        lineColor = ChartSinr,
                        onClick = { onChartClick(MetricKey.SINR) },
                    )
                }
                // Latency
                item {
                    MetricCard(
                        label = "Latency  (RTT)",
                        value = state.latencyMs?.toString() ?: "—",
                        unit = "ms",
                        qualityColor = latencyQuality(state.latencyMs),
                        sparkPoints = samples.map { s ->
                            s.latencyMs?.let { ChartPoint(s.timestamp, it.toFloat()) }
                        },
                        lineColor = ChartLatency,
                        onClick = { onChartClick(MetricKey.LATENCY) },
                    )
                }
                // Throughput
                item {
                    val dlValue = state.probeDownloadMbps ?: state.downloadMbps
                    val dlLabel = when {
                        state.probeDownloadMbps != null -> "Download  (probe)"
                        state.downloadMbps != null -> "Download  (passive)"
                        else -> "Download"
                    }
                    MetricCard(
                        label = dlLabel,
                        value = dlValue?.let { "%.1f".format(it) } ?: "—",
                        unit = "Mbps",
                        qualityColor = throughputQuality(dlValue),
                        sparkPoints = samples.map { s ->
                            val v = s.probeDownloadMbps ?: s.downloadMbps
                            v?.let { ChartPoint(s.timestamp, it) }
                        },
                        lineColor = ChartThroughput,
                        onClick = { onChartClick(MetricKey.THROUGHPUT) },
                    )
                }
                // NQS
                item {
                    MetricCard(
                        label = "NQS  (Composite)",
                        value = state.nqs?.toString() ?: "—",
                        unit = "/ 100",
                        qualityColor = nqsColor(state.nqs),
                        sparkPoints = samples.map { s ->
                            NetworkQualityScore.compute(s)?.let { ChartPoint(s.timestamp, it.toFloat()) }
                        },
                        lineColor = ChartNqs,
                        onClick = { onChartClick(MetricKey.NQS) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkBadge(networkType: String) {
    val (label, color) = when (networkType) {
        "5G_SA" -> "5G SA" to Color(0xFF00897B)
        "5G_NSA" -> "5G NSA" to Color(0xFF1E88E5)
        "LTE" -> "LTE" to Color(0xFF5E35B1)
        else -> "—" to Color.Gray
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.15f)),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = color.copy(alpha = 0.6f),
        ),
    )
}

// ── Quality colour helpers ────────────────────────────────────────────────────

private fun rsrpQuality(rsrp: Int?): Color = when {
    rsrp == null -> Color.Gray
    rsrp >= -80 -> QualityExcellent
    rsrp >= -90 -> QualityGood
    rsrp >= -100 -> QualityFair
    rsrp >= -110 -> QualityPoor
    else -> QualityBad
}

private fun sinrQuality(sinr: Int?): Color = when {
    sinr == null -> Color.Gray
    sinr >= 20 -> QualityExcellent
    sinr >= 13 -> QualityGood
    sinr >= 0 -> QualityFair
    sinr >= -3 -> QualityPoor
    else -> QualityBad
}

private fun latencyQuality(ms: Int?): Color = when {
    ms == null -> Color.Gray
    ms <= 30 -> QualityExcellent
    ms <= 60 -> QualityGood
    ms <= 100 -> QualityFair
    ms <= 200 -> QualityPoor
    else -> QualityBad
}

private fun throughputQuality(mbps: Float?): Color = when {
    mbps == null -> Color.Gray
    mbps >= 50f -> QualityExcellent
    mbps >= 20f -> QualityGood
    mbps >= 5f -> QualityFair
    mbps >= 1f -> QualityPoor
    else -> QualityBad
}

// ── NQS colour (0-100) ───────────────────────────────────────────────────────

fun nqsColor(nqs: Int?): Color = when {
    nqs == null -> Color.Gray
    nqs >= 80   -> QualityExcellent
    nqs >= 60   -> QualityGood
    nqs >= 40   -> QualityFair
    nqs >= 20   -> QualityPoor
    else        -> QualityBad
}

// ── NQS Gauge ─────────────────────────────────────────────────────────────────

@Composable
private fun NqsGauge(nqs: Int?, samples: List<MetricSample>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Circular gauge
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (nqs ?: 0) / 100f },
                    modifier = Modifier.size(64.dp),
                    color = nqsColor(nqs),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 6.dp,
                )
                Text(
                    text = nqs?.toString() ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = nqsColor(nqs),
                )
            }

            // Label + sub-text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Network Quality",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = nqs?.let { NetworkQualityScore.label(it) } ?: "No data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = nqsColor(nqs),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "ETSI/ITU composite · RSRP·SINR·Latency·DL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Trend: average NQS over recent samples
            val recentNqs = samples.mapNotNull { NetworkQualityScore.compute(it) }
            if (recentNqs.size >= 2) {
                val avg = recentNqs.average().toInt()
                val trend = recentNqs.first() - recentNqs.last() // positive = improving
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (trend > 0) "▲" else if (trend < 0) "▼" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (trend > 0) QualityExcellent
                                else if (trend < 0) QualityBad
                                else Color.Gray,
                    )
                    Text(
                        text = "avg $avg",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
