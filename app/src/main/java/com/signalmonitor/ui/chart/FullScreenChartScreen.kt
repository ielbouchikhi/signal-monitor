package com.signalmonitor.ui.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.ui.components.ChartPoint
import com.signalmonitor.ui.components.TimeSeriesChart
import com.signalmonitor.ui.history.TimeRange
import com.signalmonitor.ui.history.HistoryViewModel
import com.signalmonitor.ui.history.withGapMarkers
import com.signalmonitor.ui.theme.ChartLatency
import com.signalmonitor.ui.theme.ChartProbe
import com.signalmonitor.data.quality.NetworkQualityScore
import com.signalmonitor.ui.theme.ChartRsrp
import com.signalmonitor.ui.theme.ChartSinr
import com.signalmonitor.ui.theme.ChartThroughput

enum class MetricKey(
    val label: String,
    val unit: String,
    val color: Color,
) {
    RSRP("RSRP — Signal Strength", "dBm", ChartRsrp),
    SINR("SINR — Signal Quality", "dB", ChartSinr),
    LATENCY("Latency — Round Trip", "ms", ChartLatency),
    THROUGHPUT("Throughput — Download", "Mbps", ChartThroughput),
    NQS("Network Quality Score", "", Color(0xFF26A69A)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenChartScreen(
    metricKey: MetricKey,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val samples by viewModel.samples.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    val stats by viewModel.stats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metricKey.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
            ) {
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

                // Full-screen chart
                val (points, secondarySeries) = when (metricKey) {
                    MetricKey.RSRP -> samples.toPoints { it.rsrp?.toFloat() } to emptyList()
                    MetricKey.SINR -> samples.toPoints { it.sinr?.toFloat() } to emptyList()
                    MetricKey.LATENCY -> samples.toPoints { it.latencyMs?.toFloat() } to emptyList()
                    MetricKey.THROUGHPUT -> samples.toPoints { it.downloadMbps } to
                            samples.toPoints { it.probeDownloadMbps }
                    MetricKey.NQS -> samples.toPoints { NetworkQualityScore.compute(it)?.toFloat() } to
                            emptyList()
                }

                TimeSeriesChart(
                    points = points,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    lineColor = metricKey.color,
                    secondarySeries = secondarySeries,
                    secondaryColor = ChartProbe,
                )

                Spacer(Modifier.height(16.dp))

                // Stats row
                val (min, avg, max) = when (metricKey) {
                    MetricKey.RSRP -> Triple(stats?.minRsrp, stats?.avgRsrp, stats?.maxRsrp)
                    MetricKey.SINR -> Triple(stats?.minSinr, stats?.avgSinr, stats?.maxSinr)
                    MetricKey.LATENCY -> Triple(stats?.minLatency, stats?.avgLatency, stats?.maxLatency)
                    MetricKey.THROUGHPUT -> Triple(stats?.minDl, stats?.avgDl, stats?.maxDl)
                    MetricKey.NQS -> Triple(null, null, null) // computed client-side, not in DB stats
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatBlock("Min", min?.let { formatStat(it.toFloat()) } ?: "—", metricKey.unit)
                    StatBlock("Avg", avg?.let { formatStat(it.toFloat()) } ?: "—", metricKey.unit)
                    StatBlock("Max", max?.let { formatStat(it.toFloat()) } ?: "—", metricKey.unit)
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            if (value != "—") {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

private fun List<MetricSample>.toPoints(selector: (MetricSample) -> Float?): List<ChartPoint?> =
    withGapMarkers().map { s -> s?.let { selector(it)?.let { v -> ChartPoint(it.timestamp, v) } } }

/** Show integers without decimals, floats with at most 1 decimal place. */
private fun formatStat(v: Float): String =
    if (v == kotlin.math.floor(v.toDouble()).toFloat()) v.toInt().toString()
    else "%.1f".format(v)
