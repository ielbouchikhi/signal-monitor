package com.signalmonitor.probe

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures network latency by timing an HTTP HEAD request.
 * Uses HEAD (no body download) to isolate connection + TTFB time.
 */
@Singleton
class LatencyProbe @Inject constructor(private val httpClient: OkHttpClient) {

    /**
     * Returns round-trip time in milliseconds, or null if unreachable.
     */
    fun measure(host: String): Int? {
        val request = Request.Builder()
            .url(host)
            .head()
            .build()
        return try {
            val start = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code in 200..499) {
                    (System.currentTimeMillis() - start).toInt()
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
