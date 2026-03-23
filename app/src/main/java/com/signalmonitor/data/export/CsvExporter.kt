package com.signalmonitor.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.signalmonitor.data.db.MetricSampleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CsvExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MetricSampleDao,
) {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun buildShareIntent(): Intent {
        val samples = dao.getAllSamples()
        val file = File(context.cacheDir, "signal_export_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { w ->
            w.write("timestamp,datetime,networkType,band,rsrp_dBm,sinr_dB,rsrq_dB,cqi,downloadMbps,probeDownloadMbps,latencyMs,latitude,longitude\n")
            samples.forEach { s ->
                w.write("${s.timestamp},${sdf.format(Date(s.timestamp))},${s.networkType},${s.band ?: ""},${s.rsrp ?: ""},${s.sinr ?: ""},${s.rsrq ?: ""},${s.cqi ?: ""},${s.downloadMbps ?: ""},${s.probeDownloadMbps ?: ""},${s.latencyMs ?: ""},${s.latitude ?: ""},${s.longitude ?: ""}\n")
            }
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Signal Monitor Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
