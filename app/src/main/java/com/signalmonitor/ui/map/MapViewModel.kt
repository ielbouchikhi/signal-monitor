package com.signalmonitor.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.repository.MonitoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class MapRange(val label: String, val durationMs: Long) {
    H1("1h",  3_600_000L),
    H6("6h",  21_600_000L),
    H24("24h", 86_400_000L),
    D7("7d",  604_800_000L),
}

enum class MapViewMode(val label: String) {
    CIRCLES("Circles"),
    HEATMAP("Heatmap"),
    CLUSTERS("Clusters"),
}

data class DeadZone(
    val lat: Double,
    val lng: Double,
    val avgRsrp: Int,
    val sampleCount: Int,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: MonitoringRepository,
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(MapRange.H24)
    val selectedRange: StateFlow<MapRange> = _selectedRange.asStateFlow()

    private val _viewMode = MutableStateFlow(MapViewMode.CIRCLES)
    val viewMode: StateFlow<MapViewMode> = _viewMode.asStateFlow()

    private val _showDeadZones = MutableStateFlow(false)
    val showDeadZones: StateFlow<Boolean> = _showDeadZones.asStateFlow()

    private val _selectedSample = MutableStateFlow<MetricSample?>(null)
    val selectedSample: StateFlow<MetricSample?> = _selectedSample.asStateFlow()

    val samples: StateFlow<List<MetricSample>> = _selectedRange
        .flatMapLatest { range ->
            val fromMs = System.currentTimeMillis() - range.durationMs
            repository.observeRangeWithLocation(fromMs)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Deduplicated samples for circle/cluster rendering: one point per ~100 m grid cell
     * (most recent sample wins). Keeps the map fast regardless of raw sample count.
     */
    val displaySamples: StateFlow<List<MetricSample>> = samples
        .map { deduplicateForDisplay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val deadZones: StateFlow<List<DeadZone>> = samples
        .map { computeDeadZones(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectRange(range: MapRange) { _selectedRange.value = range }
    fun selectViewMode(mode: MapViewMode) { _viewMode.value = mode }
    fun toggleDeadZones() { _showDeadZones.value = !_showDeadZones.value }
    fun selectSample(sample: MetricSample?) { _selectedSample.value = sample }

    /** Find the nearest sample within [thresholdMeters] of [lat]/[lng]. */
    fun findNearest(lat: Double, lng: Double, thresholdMeters: Double = 200.0): MetricSample? =
        samples.value
            .filter { it.latitude != null && it.longitude != null }
            .minByOrNull { haversineMeters(lat, lng, it.latitude!!, it.longitude!!) }
            ?.takeIf { haversineMeters(lat, lng, it.latitude!!, it.longitude!!) <= thresholdMeters }

    private fun deduplicateForDisplay(samples: List<MetricSample>): List<MetricSample> {
        val gridSize = 0.001 // ~100 m
        return samples
            .groupBy { s ->
                Pair(
                    Math.round(s.latitude!! / gridSize) * gridSize,
                    Math.round(s.longitude!! / gridSize) * gridSize,
                )
            }
            .map { (_, group) -> group.maxByOrNull { it.timestamp }!! }
    }

    private fun computeDeadZones(samples: List<MetricSample>): List<DeadZone> {
        val gridSize = 0.001 // ~100 m
        return samples
            .filter { it.latitude != null && it.longitude != null && it.rsrp != null }
            .groupBy { s ->
                Pair(
                    Math.round(s.latitude!! / gridSize) * gridSize,
                    Math.round(s.longitude!! / gridSize) * gridSize,
                )
            }
            .filter { (_, g) -> g.size >= 3 }
            .mapNotNull { (coords, g) ->
                val avg = g.mapNotNull { it.rsrp }.average().toInt()
                if (avg < -100) DeadZone(coords.first, coords.second, avg, g.size) else null
            }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
