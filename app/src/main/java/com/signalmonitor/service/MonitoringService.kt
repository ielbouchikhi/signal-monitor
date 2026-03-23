package com.signalmonitor.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_NR
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.signalmonitor.MainActivity
import com.signalmonitor.R
import com.signalmonitor.data.db.MetricSample
import com.signalmonitor.data.preferences.AppSettings
import com.signalmonitor.data.repository.MonitoringRepository
import com.signalmonitor.probe.LatencyProbe
import com.signalmonitor.probe.SpeedProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject lateinit var repository: MonitoringRepository
    @Inject lateinit var latencyProbe: LatencyProbe
    @Inject lateinit var speedProbe: SpeedProbe
    @Inject lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var telephonyManager: TelephonyManager
    private val executor = Executors.newSingleThreadExecutor()

    // Latest location snapshot (written from location callback, read from sampling loop)
    @Volatile private var latestLocation: android.location.Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            latestLocation = result.lastLocation
            Log.d(TAG, "Location updated: ${result.lastLocation?.latitude}, ${result.lastLocation?.longitude}")
        }
    }

    // Latest signal snapshot (written from telephony callback, read from sampling loop)
    @Volatile private var latestCellInfos: List<CellInfo> = emptyList()

    private var samplingJob: Job? = null
    private var probeJob: Job? = null
    private var consecutivePoorSamples = 0
    private var alertFired = false

    // TrafficStats baseline for passive throughput delta
    private var lastRxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastRxTime = 0L

    // TelephonyCallback handle (API 31+)
    private var telephonyCallback: TelephonyCallback? = null

    // Legacy listener (API 29-30)
    @Suppress("DEPRECATION")
    private var phoneStateListener: android.telephony.PhoneStateListener? = null

    companion object {
        const val TAG = "SignalMonitor"
        const val CHANNEL_ID = "signal_monitor_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.signalmonitor.START"
        const val ACTION_STOP = "com.signalmonitor.STOP"
        const val ALERT_CHANNEL_ID = "signal_alert_channel"
        const val ALERT_NOTIFICATION_ID = 2

        fun startIntent(context: Context) =
            Intent(context, MonitoringService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, MonitoringService::class.java).apply { action = ACTION_STOP }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------

    private fun startMonitoring() {
        Log.d(TAG, "startMonitoring")
        startForeground(NOTIFICATION_ID, buildNotification())
        registerTelephonyCallback()
        startLocationUpdates()
        lastRxBytes = TrafficStats.getMobileRxBytes()
        lastRxTime = System.currentTimeMillis()

        lifecycleScope.launch {
            val settings = repository.settings.first()
            Log.d(TAG, "Settings loaded: interval=${settings.sampleIntervalSec}s")
            startSamplingLoop(settings)
            if (settings.activeProbeEnabled) startProbeLoop(settings)
        }
    }

    private fun stopMonitoring() {
        unregisterTelephonyCallback()
        stopLocationUpdates()
        samplingJob?.cancel()
        probeJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            10_000L  // update every 10 seconds
        ).setMinUpdateDistanceMeters(10f).build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // -------------------------------------------------------------------------
    // Telephony callback registration (API-level split)
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerCallbackApi31()
        } else {
            registerListenerApi29()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun registerCallbackApi31() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                Log.d(TAG, "CellInfo updated: ${cellInfo.size} cells")
                latestCellInfos = cellInfo
            }
        }
        telephonyCallback = cb
        telephonyManager.registerTelephonyCallback(executor, cb)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun registerListenerApi29() {
        val listener = object : android.telephony.PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                latestCellInfos = cellInfo ?: emptyList()
            }
        }
        phoneStateListener = listener
        telephonyManager.listen(
            listener,
            android.telephony.PhoneStateListener.LISTEN_CELL_INFO
        )
    }

    private fun unregisterTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyCallback = null
        phoneStateListener = null
    }

    // -------------------------------------------------------------------------
    // Sampling loop
    // -------------------------------------------------------------------------

    private fun startSamplingLoop(settings: AppSettings) {
        Log.d(TAG, "Starting sampling loop, interval=${settings.sampleIntervalSec}s")
        samplingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val sample = collectSample()
                    Log.d(TAG, "Sample: net=${sample.networkType} rsrp=${sample.rsrp} sinr=${sample.sinr} latency=${sample.latencyMs}ms dl=${sample.downloadMbps}")
                    repository.insert(sample)
                    checkAlert(sample, repository.settings.first())
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting sample", e)
                }
                delay(settings.sampleIntervalSec * 1000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectSample(): MetricSample {
        val now = System.currentTimeMillis()

        // 1. Signal metrics from latest cell info snapshot
        val (networkType, band, rsrp, sinr, rsrq, cqi) = extractSignalMetrics()

        // 2. Passive throughput
        val downloadMbps = computePassiveThroughput(now)

        // 3. Latency
        val pingHost = repository.settings.first().pingHost
        val latencyMs = latencyProbe.measure(pingHost)

        val location = latestLocation
        return MetricSample(
            timestamp = now,
            networkType = networkType,
            band = band,
            rsrp = rsrp,
            sinr = sinr,
            rsrq = rsrq,
            cqi = cqi,
            downloadMbps = downloadMbps,
            probeDownloadMbps = null, // filled by probe loop when triggered
            latencyMs = latencyMs,
            latitude = location?.latitude,
            longitude = location?.longitude,
        )
    }

    // -------------------------------------------------------------------------
    // Signal extraction
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun extractSignalMetrics(): SignalSnapshot {
        val cellInfos = latestCellInfos

        // Prefer a registered (serving) cell
        val serving = cellInfos.firstOrNull { it.isRegistered } ?: cellInfos.firstOrNull()

        // Detect network type from TelephonyManager
        val dataNetworkType = try {
            telephonyManager.dataNetworkType
        } catch (e: SecurityException) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }

        // Check for 5G NSA: LTE as data network but NR cell visible
        val hasNrCell = cellInfos.any { it is CellInfoNr }

        val networkType = when {
            dataNetworkType == NETWORK_TYPE_NR -> "5G_SA"
            dataNetworkType == NETWORK_TYPE_LTE && hasNrCell -> "5G_NSA"
            dataNetworkType == NETWORK_TYPE_LTE -> "LTE"
            else -> "UNKNOWN"
        }

        return when (serving) {
            is CellInfoNr -> extractNr(serving, networkType)
            is CellInfoLte -> extractLte(serving, networkType)
            else -> SignalSnapshot(networkType, null, null, null, null, null)
        }
    }

    private fun extractNr(cell: CellInfoNr, networkType: String): SignalSnapshot {
        val sig = cell.cellSignalStrength as android.telephony.CellSignalStrengthNr
        val identity = cell.cellIdentity as android.telephony.CellIdentityNr
        return SignalSnapshot(
            networkType = networkType,
            band = identity.nrarfcn.takeIf { it != CellInfo.UNAVAILABLE },
            rsrp = sig.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE },
            sinr = sig.ssSinr.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = sig.ssRsrq.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = null, // not exposed for NR in public API
        )
    }

    private fun extractLte(cell: CellInfoLte, networkType: String): SignalSnapshot {
        val sig = cell.cellSignalStrength
        val identity = cell.cellIdentity
        return SignalSnapshot(
            networkType = networkType,
            band = identity.earfcn.takeIf { it != CellInfo.UNAVAILABLE },
            rsrp = sig.rsrp.takeIf { it != CellInfo.UNAVAILABLE },
            sinr = sig.rssnr.takeIf { it != CellInfo.UNAVAILABLE },
            rsrq = sig.rsrq.takeIf { it != CellInfo.UNAVAILABLE },
            cqi = sig.cqi.takeIf { it != CellInfo.UNAVAILABLE },
        )
    }

    // -------------------------------------------------------------------------
    // Passive throughput
    // -------------------------------------------------------------------------

    private fun computePassiveThroughput(nowMs: Long): Float? {
        val rx = TrafficStats.getMobileRxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong()) return null

        val deltaBytes = rx - lastRxBytes
        val deltaSec = (nowMs - lastRxTime) / 1000f

        lastRxBytes = rx
        lastRxTime = nowMs

        // Ignore near-zero traffic (idle phone)
        val minThresholdBytes = 10 * 1024 // 10 KB
        if (deltaBytes < minThresholdBytes || deltaSec <= 0) return null

        return (deltaBytes * 8f) / (deltaSec * 1_000_000f) // Mbps
    }

    // -------------------------------------------------------------------------
    // Active speed probe loop
    // -------------------------------------------------------------------------

    private fun startProbeLoop(settings: AppSettings) {
        probeJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(settings.probeIntervalMin * 60_000L)
                val probeUrl = repository.settings.first().probeUrl
                val mbps = speedProbe.measure(probeUrl)
                if (mbps != null) {
                    // Insert a dedicated probe-only record
                    repository.insert(
                        MetricSample(
                            timestamp = System.currentTimeMillis(),
                            networkType = extractSignalMetrics().networkType,
                            band = null,
                            rsrp = null, sinr = null, rsrq = null, cqi = null,
                            downloadMbps = null,
                            probeDownloadMbps = mbps,
                            latencyMs = null,
                        )
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Signal Quality Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Alerts when signal drops below threshold" }
        nm.createNotificationChannel(alertChannel)
    }

    private fun checkAlert(sample: com.signalmonitor.data.db.MetricSample, settings: com.signalmonitor.data.preferences.AppSettings) {
        if (!settings.alertEnabled) { consecutivePoorSamples = 0; alertFired = false; return }
        val rsrp = sample.rsrp
        if (rsrp != null && rsrp < settings.alertRsrpThreshold) {
            consecutivePoorSamples++
        } else {
            consecutivePoorSamples = 0
            alertFired = false
        }
        if (consecutivePoorSamples >= settings.alertConsecutiveSamples && !alertFired) {
            alertFired = true
            fireAlertNotification(rsrp ?: settings.alertRsrpThreshold)
        }
    }

    private fun fireAlertNotification(rsrp: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Poor signal detected")
            .setContentText("RSRP $rsrp dBm — signal has been poor for several consecutive samples.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterTelephonyCallback()
        executor.shutdown()
    }
}

/** Intermediate holder to allow destructuring from extractSignalMetrics(). */
private data class SignalSnapshot(
    val networkType: String,
    val band: Int?,
    val rsrp: Int?,
    val sinr: Int?,
    val rsrq: Int?,
    val cqi: Int?,
)
