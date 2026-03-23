package com.signalmonitor.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalmonitor.data.quality.NetworkQualityScore
import com.signalmonitor.data.repository.MonitoringRepository
import com.signalmonitor.ui.map.DeadZone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.util.TimeZone
import javax.inject.Inject

enum class StatsRange(val label: String, val durationMs: Long) {
    H24("24h",  86_400_000L),
    D7("7d",   604_800_000L),
    D30("30d", 2_592_000_000L),
}

data class NetworkTypeBreakdown(val type: String, val count: Int, val pct: Float)

data class HourlyAvg(val hour: Int, val avgRsrp: Int?)

data class StatsUiState(
    val isLoading: Boolean = false,
    val totalSamples: Int = 0,
    val avgNqs: Int? = null,
    val networkBreakdown: List<NetworkTypeBreakdown> = emptyList(),
    val avgRsrp: Int? = null,
    val avgSinr: Int? = null,
    val avgLatencyMs: Int? = null,
    val hourlyAvg: List<HourlyAvg> = emptyList(),
    val deadZones: List<DeadZone> = emptyList(),
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: MonitoringRepository,
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(StatsRange.H24)
    val selectedRange: StateFlow<StatsRange> = _selectedRange.asStateFlow()

    /**
     * Stats are computed entirely in SQL aggregates — no full row loads.
     *
     * Architecture:
     *  - [observeCount] is a tiny Flow<Long> that fires whenever the DB changes.
     *  - [debounce] collapses rapid inserts (e.g. every 5 s from the service) into
     *    a single recomputation no more often than every 500 ms.
     *  - [loadStats] runs five parallel SQL queries on Dispatchers.IO (via Room)
     *    and assembles the result on Dispatchers.Default ([flowOn]).
     *  - Only NQS requires loading rows (no SQL formula); capped at 1 000 recent
     *    samples so it never scans the full table.
     */
    val uiState: StateFlow<StatsUiState> = _selectedRange
        .flatMapLatest { range ->
            val fromMs = System.currentTimeMillis() - range.durationMs
            flow {
                emit(StatsUiState(isLoading = true))
                repository.observeCount(fromMs)
                    .debounce(500)
                    .collect { count ->
                        emit(
                            if (count == 0L) StatsUiState()
                            else loadStats(fromMs, count)
                        )
                    }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StatsUiState(isLoading = true),
        )

    fun selectRange(range: StatsRange) { _selectedRange.value = range }

    /** Runs five aggregate queries concurrently; returns when all have completed. */
    private suspend fun loadStats(fromMs: Long, totalCount: Long): StatsUiState =
        coroutineScope {
            val tzOffsetMs = TimeZone.getDefault()
                .getOffset(System.currentTimeMillis()).toLong()

            // Kick off all queries in parallel
            val dbStatsD    = async { repository.getStats(fromMs, Long.MAX_VALUE) }
            val breakdownD  = async { repository.getNetworkBreakdown(fromMs) }
            val hourlyD     = async { repository.getHourlyRsrp(fromMs, tzOffsetMs) }
            val deadZonesD  = async { repository.getDeadZones(fromMs) }
            val nqsSamplesD = async { repository.getSamplesForNqs(fromMs, limit = 1_000) }

            val dbStats    = dbStatsD.await()
            val breakdown  = breakdownD.await()
            val hourlyRsrp = hourlyD.await()
            val deadZones  = deadZonesD.await()
            val nqsSamples = nqsSamplesD.await()

            val avgNqs = nqsSamples
                .mapNotNull { NetworkQualityScore.compute(it) }
                .average()
                .takeIf { !it.isNaN() }
                ?.toInt()

            StatsUiState(
                isLoading        = false,
                totalSamples     = totalCount.toInt(),
                avgNqs           = avgNqs,
                networkBreakdown = breakdown
                    .map { row ->
                        NetworkTypeBreakdown(
                            row.networkType,
                            row.cnt,
                            row.cnt.toFloat() / totalCount * 100f,
                        )
                    }
                    .sortedByDescending { it.count },
                avgRsrp          = dbStats?.avgRsrp?.toInt(),
                avgSinr          = dbStats?.avgSinr?.toInt(),
                avgLatencyMs     = dbStats?.avgLatency?.toInt(),
                hourlyAvg        = hourlyRsrp.map { HourlyAvg(it.hour, it.avgRsrp?.toInt()) },
                deadZones        = deadZones.map {
                    DeadZone(
                        lat         = it.gridLatI * 0.001,
                        lng         = it.gridLngI * 0.001,
                        avgRsrp     = it.avgRsrp.toInt(),
                        sampleCount = it.sampleCount,
                    )
                },
            )
        }
}
