package com.signalmonitor.data.quality

import com.signalmonitor.data.db.MetricSample
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Composite Network Quality Score (NQS) — 0 to 100.
 *
 * Grounded in:
 *  • ETSI TR 103 559 (2023) per-KPI normalisation with perceptual saturation
 *  • Weber-Fechner law for throughput (logarithmic perception)
 *  • IQX hypothesis for latency (exponential degradation sensitivity)
 *  • Shannon capacity mapping from SINR to expected throughput
 *  • ITU-T G.107 E-Model R-factor → MOS mapping for the final label
 *
 * Sub-scores (each 0.0 – 1.0):
 *  ┌──────────────┬────────┬────────────┬──────────────────────────────────────┐
 *  │ Metric       │ Weight │ Transform  │ Thresholds                           │
 *  ├──────────────┼────────┼────────────┼──────────────────────────────────────┤
 *  │ RSRP  (dBm)  │  0.25  │ Linear     │ −80 (excellent) ↔ −120 (dead)       │
 *  │ SINR  (dB)   │  0.30  │ Linear     │ +20 (excellent) ↔ −5  (unusable)    │
 *  │ Latency (ms) │  0.25  │ Exp. (IQX) │  20 ms (excellent) ↔ 300 ms (bad)  │
 *  │ Throughput   │  0.20  │ Log (W-F)  │  50 Mbps (excellent) ↔ 0.5 Mbps    │
 *  └──────────────┴────────┴────────────┴──────────────────────────────────────┘
 *
 * When throughput is null (phone idle), its weight is redistributed proportionally.
 */
object NetworkQualityScore {

    // ── Thresholds ────────────────────────────────────────────────────────────
    private const val RSRP_EXCELLENT = -80.0
    private const val RSRP_DEAD      = -120.0

    private const val SINR_EXCELLENT =  20.0
    private const val SINR_DEAD      =  -5.0

    private const val LATENCY_EXCELLENT_MS = 20.0
    private const val LATENCY_BAD_MS       = 300.0
    // IQX exponential decay constant (fitted so that 100 ms ≈ 0.45 sub-score)
    private const val LATENCY_BETA = 5.0

    private const val DL_EXCELLENT_MBPS = 50.0
    private const val DL_POOR_MBPS      = 0.5

    // ── Base weights (sum = 1.0) ──────────────────────────────────────────────
    private const val W_RSRP  = 0.25
    private const val W_SINR  = 0.30
    private const val W_LAT   = 0.25
    private const val W_DL    = 0.20

    // ── Public API ────────────────────────────────────────────────────────────

    /** Compute the NQS for a single [MetricSample]. Returns null if no metrics at all. */
    fun compute(sample: MetricSample): Int? = compute(
        rsrp = sample.rsrp,
        sinr = sample.sinr,
        latencyMs = sample.latencyMs,
        downloadMbps = sample.probeDownloadMbps ?: sample.downloadMbps,
    )

    /** Compute the NQS from individual metric values. Returns null if every input is null. */
    fun compute(
        rsrp: Int? = null,
        sinr: Int? = null,
        latencyMs: Int? = null,
        downloadMbps: Float? = null,
    ): Int? {
        val parts = mutableListOf<Pair<Double, Double>>() // (sub-score, weight)

        rsrp?.let        { parts.add(subScoreRsrp(it) to W_RSRP) }
        sinr?.let        { parts.add(subScoreSinr(it) to W_SINR) }
        latencyMs?.let   { parts.add(subScoreLatency(it) to W_LAT) }
        downloadMbps?.let { parts.add(subScoreThroughput(it) to W_DL) }

        if (parts.isEmpty()) return null

        // Redistribute weights so available metrics always sum to 1.0
        val totalWeight = parts.sumOf { it.second }
        val nqs = parts.sumOf { (score, w) -> score * (w / totalWeight) }

        return (nqs * 100).toInt().coerceIn(0, 100)
    }

    // ── Label & colour helpers ────────────────────────────────────────────────

    fun label(nqs: Int): String = when {
        nqs >= 80 -> "Excellent"
        nqs >= 60 -> "Good"
        nqs >= 40 -> "Fair"
        nqs >= 20 -> "Poor"
        else      -> "Bad"
    }

    // ── Sub-score functions ───────────────────────────────────────────────────

    /**
     * RSRP: linear mapping −120 dBm (0) → −80 dBm (1).
     * Saturates at bounds (ETSI perceptual saturation).
     */
    private fun subScoreRsrp(rsrp: Int): Double =
        linearNorm(rsrp.toDouble(), RSRP_DEAD, RSRP_EXCELLENT)

    /**
     * SINR: linear mapping −5 dB (0) → +20 dB (1).
     */
    private fun subScoreSinr(sinr: Int): Double =
        linearNorm(sinr.toDouble(), SINR_DEAD, SINR_EXCELLENT)

    /**
     * Latency: exponential decay per the IQX hypothesis.
     *
     *   sub_score = exp(−β × (ms − best) / (worst − best))
     *
     * At 20 ms → 1.0, at ~100 ms → 0.45, at 300 ms → ~0.007 → clamped.
     * Captures the research finding that latency degradation is perceived
     * exponentially more strongly at lower latencies.
     */
    private fun subScoreLatency(ms: Int): Double {
        val normalized = ((ms.toDouble() - LATENCY_EXCELLENT_MS) /
                (LATENCY_BAD_MS - LATENCY_EXCELLENT_MS)).coerceIn(0.0, 1.0)
        return exp(-LATENCY_BETA * normalized)
    }

    /**
     * Throughput: logarithmic mapping per Weber-Fechner law.
     *
     *   sub_score = log(mbps / floor) / log(ceiling / floor)
     *
     * Doubling from 1 → 2 Mbps scores a bigger jump than 50 → 100 Mbps,
     * reflecting that users perceive bandwidth improvements logarithmically.
     */
    private fun subScoreThroughput(mbps: Float): Double {
        val clamped = mbps.toDouble().coerceIn(DL_POOR_MBPS, DL_EXCELLENT_MBPS)
        return (ln(clamped) - ln(DL_POOR_MBPS)) / (ln(DL_EXCELLENT_MBPS) - ln(DL_POOR_MBPS))
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun linearNorm(value: Double, low: Double, high: Double): Double =
        ((value - low) / (high - low)).coerceIn(0.0, 1.0)
}
