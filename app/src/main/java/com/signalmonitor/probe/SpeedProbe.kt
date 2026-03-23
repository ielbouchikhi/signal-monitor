package com.signalmonitor.probe

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Active download speed probe.
 *
 * Downloads a fixed-size payload and measures throughput.
 * Default URL: Cloudflare speed test endpoint (~512 KB).
 *
 * Only runs when the user enables the active-probe feature in Settings.
 * Uses a dedicated OkHttpClient with a longer timeout.
 */
@Singleton
class SpeedProbe @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns download speed in Mbps, or null on failure.
     */
    fun measure(url: String): Float? {
        val request = Request.Builder().url(url).get().build()
        return try {
            val start = System.currentTimeMillis()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()?.size?.toLong() ?: return null
            }
            val durationSec = (System.currentTimeMillis() - start) / 1000f
            if (durationSec <= 0) return null
            (bytes * 8f) / (durationSec * 1_000_000f) // Mbps
        } catch (e: Exception) {
            null
        }
    }
}
