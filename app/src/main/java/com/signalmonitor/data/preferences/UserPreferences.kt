package com.signalmonitor.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class AppSettings(
    /** How often to collect a signal sample, in seconds. */
    val sampleIntervalSec: Int = 5,
    /** How many days of data to retain locally. */
    val retentionDays: Int = 365,
    /** Whether the active speed probe is enabled. */
    val activeProbeEnabled: Boolean = false,
    /** How often to run the active speed probe, in minutes. */
    val probeIntervalMin: Int = 15,
    /** HTTP host used for latency measurement. */
    val pingHost: String = "https://1.1.1.1",
    /** URL for active speed-probe download. */
    val probeUrl: String = "https://speed.cloudflare.com/__down?bytes=524288",
    /** Whether monitoring is currently enabled (persisted so it survives process death). */
    val monitoringEnabled: Boolean = false,
    /** Whether data collection is paused (service stays alive, sampling stops). */
    val loggingPaused: Boolean = false,
    val alertEnabled: Boolean = false,
    val alertRsrpThreshold: Int = -105,
    val alertConsecutiveSamples: Int = 3,
)

@Singleton
class UserPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val store = context.dataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            sampleIntervalSec = prefs[Keys.SAMPLE_INTERVAL] ?: 5,
            retentionDays = prefs[Keys.RETENTION_DAYS] ?: 365,
            activeProbeEnabled = prefs[Keys.ACTIVE_PROBE_ENABLED] ?: false,
            probeIntervalMin = prefs[Keys.PROBE_INTERVAL_MIN] ?: 15,
            pingHost = prefs[Keys.PING_HOST] ?: "https://1.1.1.1",
            probeUrl = prefs[Keys.PROBE_URL] ?: "https://speed.cloudflare.com/__down?bytes=524288",
            monitoringEnabled = prefs[Keys.MONITORING_ENABLED] ?: false,
            loggingPaused = prefs[Keys.LOGGING_PAUSED] ?: false,
            alertEnabled = prefs[Keys.ALERT_ENABLED] ?: false,
            alertRsrpThreshold = prefs[Keys.ALERT_RSRP_THRESHOLD] ?: -105,
            alertConsecutiveSamples = prefs[Keys.ALERT_CONSECUTIVE] ?: 3,
        )
    }

    suspend fun setSampleInterval(seconds: Int) =
        store.edit { it[Keys.SAMPLE_INTERVAL] = seconds }

    suspend fun setRetentionDays(days: Int) =
        store.edit { it[Keys.RETENTION_DAYS] = days }

    suspend fun setActiveProbeEnabled(enabled: Boolean) =
        store.edit { it[Keys.ACTIVE_PROBE_ENABLED] = enabled }

    suspend fun setProbeIntervalMin(minutes: Int) =
        store.edit { it[Keys.PROBE_INTERVAL_MIN] = minutes }

    suspend fun setPingHost(host: String) =
        store.edit { it[Keys.PING_HOST] = host }

    suspend fun setProbeUrl(url: String) =
        store.edit { it[Keys.PROBE_URL] = url }

    suspend fun setMonitoringEnabled(enabled: Boolean) =
        store.edit { it[Keys.MONITORING_ENABLED] = enabled }

    suspend fun setLoggingPaused(paused: Boolean) =
        store.edit { it[Keys.LOGGING_PAUSED] = paused }

    suspend fun setAlertEnabled(enabled: Boolean) =
        store.edit { it[Keys.ALERT_ENABLED] = enabled }

    suspend fun setAlertRsrpThreshold(dbm: Int) =
        store.edit { it[Keys.ALERT_RSRP_THRESHOLD] = dbm }

    suspend fun setAlertConsecutiveSamples(n: Int) =
        store.edit { it[Keys.ALERT_CONSECUTIVE] = n }

    private object Keys {
        val SAMPLE_INTERVAL = intPreferencesKey("sample_interval_sec")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val ACTIVE_PROBE_ENABLED = booleanPreferencesKey("active_probe_enabled")
        val PROBE_INTERVAL_MIN = intPreferencesKey("probe_interval_min")
        val PING_HOST = stringPreferencesKey("ping_host")
        val PROBE_URL = stringPreferencesKey("probe_url")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val LOGGING_PAUSED = booleanPreferencesKey("logging_paused")
        val ALERT_ENABLED = booleanPreferencesKey("alert_enabled")
        val ALERT_RSRP_THRESHOLD = intPreferencesKey("alert_rsrp_threshold")
        val ALERT_CONSECUTIVE = intPreferencesKey("alert_consecutive_samples")
    }
}
