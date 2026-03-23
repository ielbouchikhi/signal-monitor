package com.signalmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.signalmonitor.data.preferences.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var userPreferences: UserPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            val wasEnabled = userPreferences.settings.first().monitoringEnabled
            if (wasEnabled) {
                context.startForegroundService(MonitoringService.startIntent(context))
            }
        }
    }
}
