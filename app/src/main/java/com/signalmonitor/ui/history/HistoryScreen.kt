package com.signalmonitor.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.db.MetricStats
import com.signalmonitor.ui.components.ChartPoint
import com.signalmonitor.ui.components.TimeSeriesChart
import com.signalmonitor.ui.theme.ChartLatency
import com.signalmonitor.ui.theme.ChartProbe
import com.signalmonitor.ui.theme.ChartRsrp
import com.signalmonitor.ui.theme.ChartSinr
import com.signalmonitor.ui.theme.ChartThroughput

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val samples by viewModel.samples.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    val stats by viewModel.stats.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("History", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // Time-range selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val gappedSamples = samples.withGapMarkers()

            // RSRP chart
            ChartSection(
                title = "RSRP — Signal Strength",
                unit = "dBm",
                points = gappedSamples.map { it?.toPoint { s -> s.rsrp?.toFloat() } },
                lineColor = ChartRsrp,
                stats = stats?.let { Triple(it.minRsrp, it.avgRsrp, it.maxRsrp) },
            )

            // SINR chart
            ChartSection(
                title = "SINR — Signal Quality",
                unit = "dB",
                points = gappedSamples.map { it?.toPoint { s -> s.sinr?.toFloat() } },
                lineColor = ChartSinr,
                stats = stats?.let { Triple(it.minSinr, it.avgSinr, it.maxSinr) },
            )

            // Latency chart
            ChartSection(
                title = "Latency — Round Trip",
                unit = "ms",
                points = gappedSamples.map { it?.toPoint { s -> s.latencyMs?.toFloat() } },
                lineColor = ChartLatency,
                stats = stats?.let { Triple(it.minLatency, it.avgLatency, it.maxLatency) },
            )

            // Throughput chart (passive + probe overlay)
            val passivePoints = gappedSamples.map { it?.toPoint { s -> s.downloadMbps } }
            val probePoints = gappedSamples.map { it?.toPoint { s -> s.probeDownloadMbps } }
            val hasProbeData = probePoints.any { it != null }

            ChartSection(
                title = if (hasProbeData) "Throughput (passive + probe)" else "Throughput — Download",
                unit = "Mbps",
                points = passivePoints,
                secondarySeries = if (hasProbeData) probePoints else emptyList(),
                lineColor = ChartThroughput,
                secondaryColor = ChartProbe,
                stats = stats?.let { Triple(it.minDl, it.avgDl, it.maxDl) },
                passiveNote = if (hasProbeData) "Solid = passive  ·  Dashed = probe" else null,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ChartSection(
    title: String,
    unit: String,
    points: List<ChartPoint?>,
    lineColor: Color,
    secondarySeries: List<ChartPoint?> = emptyList(),
    secondaryColor: Color = ChartProbe,
    stats: Triple<Number?, Double?, Number?>?,
    passiveNote: String? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(unit, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (passiveNote != null) {
                Text(
                    passiveNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            TimeSeriesChart(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                lineColor = lineColor,
                secondarySeries = secondarySeries,
                secondaryColor = secondaryColor,
            )

            // Stats row
            if (stats != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatLabel("Min", stats.first?.let { formatStat(it.toFloat()) } ?: "—")
                    StatLabel("Avg", stats.second?.let { formatStat(it.toFloat()) } ?: "—")
                    StatLabel("Max", stats.third?.let { formatStat(it.toFloat()) } ?: "—")
                }
            }
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun MetricSample.toPoint(selector: (MetricSample) -> Float?): ChartPoint? =
    selector(this)?.let { ChartPoint(timestamp, it) }

/**
 * Inserts null sentinels between consecutive samples whose timestamps differ by
 * more than 3× the typical (median) inter-sample interval, or 60 s minimum.
 * These nulls cause the chart to break the line and show a visible gap rather
 * than connecting across a logging pause.
 */
internal fun List<MetricSample>.withGapMarkers(): List<MetricSample?> {
    if (size <= 1) return this
    val intervals = zipWithNext { a, b -> b.timestamp - a.timestamp }.filter { it > 0 }
    val medianInterval = if (intervals.isNotEmpty()) intervals.sorted()[intervals.size / 2] else 60_000L
    val gapThreshold = maxOf(medianInterval * 3, 60_000L)
    val result = mutableListOf<MetricSample?>()
    for (i in indices) {
        result.add(this[i])
        if (i < size - 1 && this[i + 1].timestamp - this[i].timestamp > gapThreshold) {
            result.add(null)
        }
    }
    return result
}

private fun formatStat(v: Float): String =
    if (v == kotlin.math.floor(v.toDouble()).toFloat()) v.toInt().toString()
    else "%.1f".format(v)
