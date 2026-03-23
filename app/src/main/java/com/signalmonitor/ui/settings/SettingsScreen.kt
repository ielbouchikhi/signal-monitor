package com.signalmonitor.ui.settings

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val shareIntent by viewModel.shareIntent.collectAsState()
    val context = LocalContext.current

    // Launch share sheet when intent is ready
    LaunchedEffect(shareIntent) {
        shareIntent?.let {
            context.startActivity(Intent.createChooser(it, "Export CSV"))
            viewModel.clearShareIntent()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            // ── Sample interval ───────────────────────────────────────────
            SectionHeader("Sample interval")
            Text("How often signal metrics are collected.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 30).forEach { sec ->
                    FilterChip(selected = settings.sampleIntervalSec == sec, onClick = { viewModel.setSampleInterval(sec) }, label = { Text("${sec}s") })
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))

            // ── Data retention ────────────────────────────────────────────
            SectionHeader("Data retention")
            Text("Samples older than this are deleted automatically.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 180, 365).forEach { days ->
                    FilterChip(selected = settings.retentionDays == days, onClick = { viewModel.setRetentionDays(days) }, label = { Text("${days}d") })
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))

            // ── Latency host ──────────────────────────────────────────────
            SectionHeader("Latency probe host")
            var pingHost by remember(settings.pingHost) { mutableStateOf(settings.pingHost) }
            OutlinedTextField(
                value = pingHost, onValueChange = { pingHost = it },
                label = { Text("URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("HTTP HEAD request; measured once per sample interval.") },
                trailingIcon = {
                    if (pingHost != settings.pingHost) {
                        androidx.compose.material3.TextButton(onClick = { viewModel.setPingHost(pingHost) }) { Text("Save") }
                    }
                },
            )

            Divider(Modifier.padding(vertical = 16.dp))

            // ── Active speed probe ────────────────────────────────────────
            SectionHeader("Active speed probe")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Periodic download test", style = MaterialTheme.typography.bodyMedium)
                    Text("Downloads ~512 KB to estimate capacity when phone is idle.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.activeProbeEnabled, onCheckedChange = { viewModel.setActiveProbeEnabled(it) })
            }
            if (settings.activeProbeEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("Probe interval", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30).forEach { min ->
                        FilterChip(selected = settings.probeIntervalMin == min, onClick = { viewModel.setProbeIntervalMin(min) }, label = { Text("${min}m") })
                    }
                }
                val mbPerDay = (24 * 60 / settings.probeIntervalMin) * 0.5f
                Text("~%.0f MB mobile data per day at current interval.".format(mbPerDay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Divider(Modifier.padding(vertical = 16.dp))

            // ── Signal quality alerts ─────────────────────────────────────
            SectionHeader("Signal quality alerts")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alert on poor signal", style = MaterialTheme.typography.bodyMedium)
                    Text("Notify when RSRP stays below threshold for N consecutive samples.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.alertEnabled, onCheckedChange = { viewModel.setAlertEnabled(it) })
            }
            if (settings.alertEnabled) {
                Spacer(Modifier.height(12.dp))
                Text("RSRP threshold: ${settings.alertRsrpThreshold} dBm", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.alertRsrpThreshold.toFloat(),
                    onValueChange = { viewModel.setAlertRsrpThreshold(it.roundToInt()) },
                    valueRange = -130f..-85f,
                    steps = 44,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Consecutive poor samples: ${settings.alertConsecutiveSamples}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { n ->
                        FilterChip(selected = settings.alertConsecutiveSamples == n, onClick = { viewModel.setAlertConsecutiveSamples(n) }, label = { Text("$n") })
                    }
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))

            // ── Export ────────────────────────────────────────────────────
            SectionHeader("Data export")
            Text("Export all logged samples as a CSV file.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.exportCsv() },
                    enabled = exportState !is ExportState.Exporting,
                ) { Text("Export CSV") }
                if (exportState is ExportState.Exporting) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
                if (exportState is ExportState.Error) {
                    Text((exportState as ExportState.Error).message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
}
