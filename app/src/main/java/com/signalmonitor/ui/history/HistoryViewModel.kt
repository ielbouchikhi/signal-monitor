package com.signalmonitor.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.db.MetricStats
import com.signalmonitor.data.repository.MonitoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TimeRange(val label: String, val durationMs: Long) {
    ONE_HOUR("1h", 3_600_000L),
    SIX_HOURS("6h", 21_600_000L),
    TWENTY_FOUR_HOURS("24h", 86_400_000L),
    SEVEN_DAYS("7d", 604_800_000L),
}

data class HistoryUiState(
    val selectedRange: TimeRange = TimeRange.ONE_HOUR,
    val samples: List<MetricSample> = emptyList(),
    val stats: MetricStats? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: MonitoringRepository,
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(TimeRange.ONE_HOUR)
    val selectedRange: StateFlow<TimeRange> = _selectedRange

    val samples: StateFlow<List<MetricSample>> = _selectedRange
        .flatMapLatest { range ->
            // Use a far-future toMs so newly inserted samples always fall inside the window
            repository.observeRange(
                fromMs = System.currentTimeMillis() - range.durationMs,
                toMs = Long.MAX_VALUE,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stats = MutableStateFlow<MetricStats?>(null)
    val stats: StateFlow<MetricStats?> = _stats

    fun selectRange(range: TimeRange) {
        _selectedRange.value = range
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _stats.value = repository.getStats(now - range.durationMs, Long.MAX_VALUE)
        }
    }

    init {
        selectRange(TimeRange.ONE_HOUR)
    }
}
