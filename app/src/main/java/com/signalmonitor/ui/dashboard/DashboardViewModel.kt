package com.signalmonitor.ui.dashboard

import android.app.Application
import android.util.Log
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.quality.NetworkQualityScore
import com.signalmonitor.data.repository.MonitoringRepository
import com.signalmonitor.service.MonitoringService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val monitoringEnabled: Boolean = false,
    val networkType: String = "—",
    val band: String = "—",
    val rsrp: Int? = null,
    val sinr: Int? = null,
    val latencyMs: Int? = null,
    val downloadMbps: Float? = null,
    val probeDownloadMbps: Float? = null,
    val nqs: Int? = null,             // Network Quality Score 0-100
    val recentSamples: List<MetricSample> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    app: Application,
    private val repository: MonitoringRepository,
) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    val uiState: StateFlow<DashboardUiState> = repository.observeLatest(120)
        .map { samples ->
            val latest = samples.firstOrNull()
            val settings = repository.prefs.settings
            DashboardUiState(
                networkType = latest?.networkType ?: "—",
                band = latest?.band?.toString() ?: "—",
                rsrp = latest?.rsrp,
                sinr = latest?.sinr,
                latencyMs = latest?.latencyMs,
                downloadMbps = latest?.downloadMbps,
                probeDownloadMbps = samples.firstOrNull { it.probeDownloadMbps != null }?.probeDownloadMbps,
                nqs = latest?.let { NetworkQualityScore.compute(it) },
                recentSamples = samples,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val monitoringEnabled: StateFlow<Boolean> = repository.prefs.settings
        .map { it.monitoringEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val loggingPaused: StateFlow<Boolean> = repository.prefs.settings
        .map { it.loggingPaused }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleMonitoring(enabled: Boolean) {
        Log.d("SignalMonitor", "toggleMonitoring enabled=$enabled")
        viewModelScope.launch {
            repository.prefs.setMonitoringEnabled(enabled)
            if (enabled) {
                Log.d("SignalMonitor", "Calling startForegroundService")
                context.startForegroundService(MonitoringService.startIntent(context))
            } else {
                // Reset pause state so next start begins collecting immediately.
                repository.prefs.setLoggingPaused(false)
                context.startService(MonitoringService.stopIntent(context))
            }
        }
    }

    fun pauseLogging() {
        context.startService(MonitoringService.pauseIntent(context))
    }

    fun resumeLogging() {
        context.startService(MonitoringService.resumeIntent(context))
    }
}
