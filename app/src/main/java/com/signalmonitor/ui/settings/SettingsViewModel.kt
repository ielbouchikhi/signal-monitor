package com.signalmonitor.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalmonitor.data.export.CsvExporter
import com.signalmonitor.data.preferences.AppSettings
import com.signalmonitor.data.preferences.UserPreferences
import com.signalmonitor.data.repository.MonitoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val csvExporter: CsvExporter,
    private val repository: MonitoringRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _shareIntent = MutableStateFlow<Intent?>(null)
    val shareIntent: StateFlow<Intent?> = _shareIntent.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    fun setSampleInterval(seconds: Int) = viewModelScope.launch { prefs.setSampleInterval(seconds) }
    fun setRetentionDays(days: Int) = viewModelScope.launch { prefs.setRetentionDays(days) }
    fun setActiveProbeEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setActiveProbeEnabled(enabled) }
    fun setProbeIntervalMin(minutes: Int) = viewModelScope.launch { prefs.setProbeIntervalMin(minutes) }
    fun setPingHost(host: String) = viewModelScope.launch { prefs.setPingHost(host) }
    fun setAlertEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setAlertEnabled(enabled) }
    fun setAlertRsrpThreshold(dbm: Int) = viewModelScope.launch { prefs.setAlertRsrpThreshold(dbm) }
    fun setAlertConsecutiveSamples(n: Int) = viewModelScope.launch { prefs.setAlertConsecutiveSamples(n) }

    fun exportCsv() {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting
            try {
                _shareIntent.value = csvExporter.buildShareIntent()
                _exportState.value = ExportState.Done
            } catch (e: Exception) {
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun clearShareIntent() { _shareIntent.value = null }
    fun clearExportState() { _exportState.value = ExportState.Idle }

    private val _deleteAllState = MutableStateFlow<DeleteAllState>(DeleteAllState.Idle)
    val deleteAllState: StateFlow<DeleteAllState> = _deleteAllState.asStateFlow()

    fun deleteAllData() {
        viewModelScope.launch {
            _deleteAllState.value = DeleteAllState.Deleting
            try {
                repository.deleteAllData()
                _deleteAllState.value = DeleteAllState.Done
            } catch (e: Exception) {
                _deleteAllState.value = DeleteAllState.Error(e.message ?: "Delete failed")
            }
        }
    }

    fun clearDeleteAllState() { _deleteAllState.value = DeleteAllState.Idle }
}

sealed class DeleteAllState {
    object Idle     : DeleteAllState()
    object Deleting : DeleteAllState()
    object Done     : DeleteAllState()
    data class Error(val message: String) : DeleteAllState()
}

sealed class ExportState {
    object Idle      : ExportState()
    object Exporting : ExportState()
    object Done      : ExportState()
    data class Error(val message: String) : ExportState()
}
