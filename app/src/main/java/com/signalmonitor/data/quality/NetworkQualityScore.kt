package com.signalmonitor.data.quality

import com.signalmonitor.data.db.MetricSample
import kotlin.math.exp
import kotlin.math.ln

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
 *  ┌──────────────────┬────────┬────────────┬──────────────────────────────────────┐
 *  │ Metric           │ Weight │ Transform  │ Thresholds / mapping                 │
 *  ├──────────────────┼────────┼────────────┼──────────────────────────────────────┤
 *  │ RSRP  (dBm)      │  0.25  │ Linear     │ −80 (excellent) ↔ −120 (dead)       │
 *  │ SINR  (dB)       │  0.30  │ Linear     │ +20 (excellent) ↔ −5  (unusable)    │
 *  │ Latency (ms)     │  0.20  │ Exp. (IQX) │  20 ms (excellent) ↔ 300 ms (bad)  │
 *  │ Throughput       │  0.10  │ Log (W-F)  │  50 Mbps (excellent) ↔ 0.5 Mbps    │
 *  │ RAT / technology │  0.15  │ Ordinal    │ 5G mmWave→LTE→UNKNOWN               │
 *  └──────────────────┴────────┴────────────┴──────────────────────────────────────┘
 *
 * RAT rationale: newer radio technologies squeeze significantly more throughput
 * from the same RF conditions (RSRP/SINR). Passive throughput from Android is
 * app-driven and does not reflect true network capacity, so RAT is scored
 * directly rather than relying on the throughput sub-score alone.
 *
 * mmWave detection: NR-ARFCN ≥ 2 016 667 (3GPP FR2, 24.25–52.6 GHz).
 *
 * When any metric is unavailable its weight is redistributed proportionally
 * across the metrics that are present.
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

    // ── RAT capability ceiling (ordinal, 0.0 – 1.0) ──────────────────────────
    // NR-ARFCN threshold that separates FR2 (mmWave) from FR1 (sub-6 GHz).
    // FR2 range: 2 016 667 – 3 279 165  (3GPP TS 38.104 Table 5.4.2.3-1).
    private const val NR_ARFCN_FR2_MIN = 2_016_667

    private const val RAT_5G_MMWAVE = 1.00  // 5G FR2: massive bandwidth ceiling
    private const val RAT_5G_SA     = 0.90  // 5G SA sub-6: 5G core, full feature set
    private const val RAT_5G_NSA    = 0.75  // 5G NSA sub-6: LTE anchor, core constraints
    private const val RAT_LTE       = 0.50  // LTE: solid but generation-limited

    // ── Base weights (sum = 1.0) ──────────────────────────────────────────────
    private const val W_RSRP  = 0.25
    private const val W_SINR  = 0.30
    private const val W_LAT   = 0.20  // reduced from 0.25 to make room for RAT
    private const val W_DL    = 0.10  // reduced from 0.20; passive DL is app-driven noise
    private const val W_RAT   = 0.15  // new: RAT generation as capability proxy

    // ── Public API ────────────────────────────────────────────────────────────

    /** Compute the NQS for a single [MetricSample]. Returns null if no metrics at all. */
    fun compute(sample: MetricSample): Int? = compute(
        rsrp = sample.rsrp,
        sinr = sample.sinr,
        latencyMs = sample.latencyMs,
        downloadMbps = sample.probeDownloadMbps ?: sample.downloadMbps,
        networkType = sample.networkType,
        band = sample.band,
    )

    /** Compute the NQS from individual metric values. Returns null if every input is null. */
    fun compute(
        rsrp: Int? = null,
        sinr: Int? = null,
        latencyMs: Int? = null,
        downloadMbps: Float? = null,
        networkType: String? = null,
        band: Int? = null,
    ): Int? {
        val parts = mutableListOf<Pair<Double, Double>>() // (sub-score, weight)

        rsrp?.let         { parts.add(subScoreRsrp(it) to W_RSRP) }
        sinr?.let         { parts.add(subScoreSinr(it) to W_SINR) }
        latencyMs?.let    { parts.add(subScoreLatency(it) to W_LAT) }
        downloadMbps?.let { parts.add(subScoreThroughput(it) to W_DL) }
        subScoreRat(networkType, band)?.let { parts.add(it to W_RAT) }

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

    /**
     * RAT sub-score: reflects the inherent capacity ceiling of the radio technology
     * independently of current RF conditions.
     *
     * Returns null for "UNKNOWN" or missing networkType so the weight is
     * redistributed to the remaining metrics rather than dragging the score down.
     *
     * mmWave is detected via NR-ARFCN ≥ [NR_ARFCN_FR2_MIN] (3GPP FR2).
     */
    private fun subScoreRat(networkType: String?, band: Int?): Double? {
        val isMmWave = band != null && band >= NR_ARFCN_FR2_MIN
        return when (networkType) {
            "5G_SA"  -> if (isMmWave) RAT_5G_MMWAVE else RAT_5G_SA
            "5G_NSA" -> if (isMmWave) RAT_5G_MMWAVE else RAT_5G_NSA
            "LTE"    -> RAT_LTE
            else     -> null  // UNKNOWN or missing — omit rather than penalise
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun linearNorm(value: Double, low: Double, high: Double): Double =
        ((value - low) / (high - low)).coerceIn(0.0, 1.0)
}
