package com.signalmonitor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row = one sample collected at [timestamp].
 *
 * Passive throughput ([downloadMbps]) is null when the phone was idle (< 10 KB moved).
 * Active probe throughput ([probeDownloadMbps]) is null unless the active-probe feature is
 * enabled and a probe was completed for this sample window.
 */
@Entity(
    tableName = "metric_samples",
    indices = [Index("timestamp")]
)
data class MetricSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,                // epoch ms

    // --- Network generation ---
    // "5G_SA" | "5G_NSA" | "LTE" | "UNKNOWN"
    val networkType: String,
    val band: Int?,                     // EARFCN (LTE) or NR-ARFCN band number

    // --- Signal (from TelephonyManager / CellInfo) ---
    val rsrp: Int?,                     // dBm  (-140 to -44);  primary metric
    val sinr: Int?,                     // dB   (-23 to +40);   primary metric
    val rsrq: Int?,                     // dB   (-19.5 to -3);  bonus / diagnostics
    val cqi: Int?,                      // 0-15;               bonus / diagnostics

    // --- Throughput ---
    val downloadMbps: Float?,           // passive (TrafficStats delta); null when idle
    val probeDownloadMbps: Float?,      // active speed-probe result; null when probe is off

    // --- Latency ---
    val latencyMs: Int?,                // HTTP round-trip to configured host

    // --- Location (from FusedLocationProviderClient) ---
    val latitude: Double? = null,
    val longitude: Double? = null,
)
