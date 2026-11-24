package com.Johnny.wcx.features.items.ai

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.Johnny.wcx.utils.WeLogger

/** An inexact alarm wakes the resident process for a scheduler to check its persisted state. */
internal class AiScheduleWakeSignal(
    private val app: Application,
    name: String,
    private val onWake: () -> Unit,
) {
    private val action = "${app.packageName}.wcx.$name"
    private val tag = "AiScheduleWakeSignal"
    private val alarm = app.getSystemService(AlarmManager::class.java)
    private var pending: PendingIntent? = null
    private var scheduledAt: Long? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == action) onWake()
        }
    }

    @Synchronized
    fun register() {
        if (alarm == null || pending != null) return
        runCatching {
            val signal = PendingIntent.getBroadcast(
                app, 0, Intent(action).setPackage(app.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            signal
        }.onSuccess { pending = it }
            .onFailure { WeLogger.w(tag, "Could not register alarm receiver", it) }
    }

    @Synchronized
    fun schedule(at: Long?) {
        val intent = pending ?: return
        val manager = alarm ?: return
        if (scheduledAt == at) return
        runCatching {
            if (at == null) {
                manager.cancel(intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }.onSuccess { scheduledAt = at }
            .onFailure { WeLogger.w(tag, "Could not update alarm", it) }
    }

    @Synchronized
    fun unregister() {
        val intent = pending ?: return
        pending = null
        scheduledAt = null
        runCatching { alarm?.cancel(intent) }
            .onFailure { WeLogger.w(tag, "Could not clear alarm", it) }
        runCatching { app.unregisterReceiver(receiver) }
            .onFailure { WeLogger.w(tag, "Could not unregister alarm receiver", it) }
    }
}
