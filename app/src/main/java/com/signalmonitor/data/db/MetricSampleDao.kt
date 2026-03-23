package com.signalmonitor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: MetricSample)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<MetricSample>)

    /** Latest N samples in descending order (for live dashboard). */
    @Query("SELECT * FROM metric_samples ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(limit: Int = 60): Flow<List<MetricSample>>

    /** All samples in the given time window, ascending (for charts). */
    @Query(
        "SELECT * FROM metric_samples " +
        "WHERE timestamp >= :fromMs AND timestamp <= :toMs " +
        "ORDER BY timestamp ASC"
    )
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<MetricSample>>

    /** Aggregated stats for a time range (used in chart summary cards). */
    @Query(
        "SELECT " +
        "  MIN(rsrp) AS minRsrp, AVG(rsrp) AS avgRsrp, MAX(rsrp) AS maxRsrp, " +
        "  MIN(sinr) AS minSinr, AVG(sinr) AS avgSinr, MAX(sinr) AS maxSinr, " +
        "  MIN(latencyMs) AS minLatency, AVG(latencyMs) AS avgLatency, MAX(latencyMs) AS maxLatency, " +
        "  MIN(downloadMbps) AS minDl, AVG(downloadMbps) AS avgDl, MAX(downloadMbps) AS maxDl " +
        "FROM metric_samples " +
        "WHERE timestamp >= :fromMs AND timestamp <= :toMs"
    )
    suspend fun getStats(fromMs: Long, toMs: Long): MetricStats?

    /** Samples with location data since [fromMs] — used by the map screen. */
    @Query(
        "SELECT * FROM metric_samples " +
        "WHERE timestamp >= :fromMs AND latitude IS NOT NULL AND longitude IS NOT NULL " +
        "ORDER BY timestamp ASC"
    )
    fun observeRangeWithLocation(fromMs: Long): Flow<List<MetricSample>>

    /** Delete samples older than [beforeMs] (used by retention job). */
    @Query("DELETE FROM metric_samples WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM metric_samples")
    suspend fun count(): Int

    /** All samples ordered by timestamp (for CSV export). */
    @Query("SELECT * FROM metric_samples ORDER BY timestamp ASC")
    suspend fun getAllSamples(): List<MetricSample>

    // ── Stats-screen aggregate queries ────────────────────────────────────────

    /** Lightweight count flow — triggers stats refresh without loading rows. */
    @Query("SELECT COUNT(*) FROM metric_samples WHERE timestamp >= :fromMs")
    fun observeCount(fromMs: Long): Flow<Long>

    /** Capped sample set for NQS average computation (most-recent [limit] rows). */
    @Query(
        "SELECT * FROM metric_samples " +
        "WHERE timestamp >= :fromMs " +
        "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getSamplesForNqs(fromMs: Long, limit: Int): List<MetricSample>

    /** Network-type sample counts for the breakdown card. */
    @Query(
        "SELECT networkType, COUNT(*) AS cnt " +
        "FROM metric_samples WHERE timestamp >= :fromMs " +
        "GROUP BY networkType"
    )
    suspend fun getNetworkBreakdown(fromMs: Long): List<NetworkTypeCount>

    /**
     * Average RSRP per local hour-of-day.
     * [tzOffsetMs] shifts UTC epoch millis to the device's local timezone so
     * hour-of-day buckets reflect wall-clock time, not UTC time.
     */
    @Query(
        "SELECT CAST(((timestamp + :tzOffsetMs) / 3600000 % 24) AS INTEGER) AS hour, " +
        "       AVG(rsrp) AS avgRsrp " +
        "FROM metric_samples " +
        "WHERE timestamp >= :fromMs AND rsrp IS NOT NULL " +
        "GROUP BY hour ORDER BY hour"
    )
    suspend fun getHourlyRsrp(fromMs: Long, tzOffsetMs: Long): List<HourlyRsrpRow>

    /**
     * Dead-zone grid cells: groups ~100 m cells, returns those with
     * ≥ 3 samples and average RSRP below −100 dBm.
     */
    @Query(
        "SELECT CAST(ROUND(latitude  / 0.001) AS INTEGER) AS gridLatI, " +
        "       CAST(ROUND(longitude / 0.001) AS INTEGER) AS gridLngI, " +
        "       AVG(rsrp)   AS avgRsrp, " +
        "       COUNT(*)    AS sampleCount " +
        "FROM metric_samples " +
        "WHERE timestamp >= :fromMs " +
        "  AND latitude IS NOT NULL AND longitude IS NOT NULL AND rsrp IS NOT NULL " +
        "GROUP BY gridLatI, gridLngI " +
        "HAVING sampleCount >= 3 AND avgRsrp < -100"
    )
    suspend fun getDeadZones(fromMs: Long): List<DeadZoneRow>
}

/** Flat result from the aggregation query above. */
data class MetricStats(
    val minRsrp: Int?, val avgRsrp: Double?, val maxRsrp: Int?,
    val minSinr: Int?, val avgSinr: Double?, val maxSinr: Int?,
    val minLatency: Int?, val avgLatency: Double?, val maxLatency: Int?,
    val minDl: Float?, val avgDl: Double?, val maxDl: Float?,
)

/** Result row for network-type breakdown. */
data class NetworkTypeCount(val networkType: String, val cnt: Int)

/** Result row for hourly-RSRP query. */
data class HourlyRsrpRow(val hour: Int, val avgRsrp: Double?)

/** Result row for dead-zone SQL aggregation. */
data class DeadZoneRow(
    val gridLatI: Int,
    val gridLngI: Int,
    val avgRsrp: Double,
    val sampleCount: Int,
)
