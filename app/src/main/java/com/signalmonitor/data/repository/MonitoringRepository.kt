package com.signalmonitor.data.repository

import com.signalmonitor.data.db.AppDatabase
import com.signalmonitor.data.db.DeadZoneRow
import com.signalmonitor.data.db.HourlyRsrpRow
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.db.MetricStats
import com.signalmonitor.data.db.NetworkTypeCount
import com.signalmonitor.data.preferences.AppSettings
import com.signalmonitor.data.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonitoringRepository @Inject constructor(
    private val db: AppDatabase,
    val prefs: UserPreferences,
) {
    private val dao = db.metricSampleDao()

    val settings: Flow<AppSettings> = prefs.settings

    /** Latest [limit] samples for the live dashboard. */
    fun observeLatest(limit: Int = 120): Flow<List<MetricSample>> =
        dao.observeLatest(limit)

    /** Samples in [fromMs]..[toMs] for history charts. */
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<MetricSample>> =
        dao.observeRange(fromMs, toMs)

    /** Samples with location since [fromMs] for the map screen. */
    fun observeRangeWithLocation(fromMs: Long): Flow<List<MetricSample>> =
        dao.observeRangeWithLocation(fromMs)

    suspend fun insert(sample: MetricSample) = dao.insert(sample)

    suspend fun insertAll(samples: List<MetricSample>) = dao.insertAll(samples)

    suspend fun getStats(fromMs: Long, toMs: Long): MetricStats? =
        dao.getStats(fromMs, toMs)

    // ── Stats-screen helpers ──────────────────────────────────────────────────

    fun observeCount(fromMs: Long): Flow<Long> = dao.observeCount(fromMs)

    suspend fun getSamplesForNqs(fromMs: Long, limit: Int = 1_000): List<MetricSample> =
        dao.getSamplesForNqs(fromMs, limit)

    suspend fun getNetworkBreakdown(fromMs: Long): List<NetworkTypeCount> =
        dao.getNetworkBreakdown(fromMs)

    suspend fun getHourlyRsrp(fromMs: Long, tzOffsetMs: Long): List<HourlyRsrpRow> =
        dao.getHourlyRsrp(fromMs, tzOffsetMs)

    suspend fun getDeadZones(fromMs: Long): List<DeadZoneRow> =
        dao.getDeadZones(fromMs)

    /** Called by the daily retention WorkManager job. */
    suspend fun pruneOldData(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600 * 1000
        dao.deleteOlderThan(cutoff)
    }

    /** Permanently deletes every stored sample. */
    suspend fun deleteAllData() = dao.deleteAll()
}
