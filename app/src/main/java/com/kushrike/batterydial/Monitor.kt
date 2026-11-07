package com.kushrike.batterydial

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Records history and keeps widgets current. Battery broadcasts only reach a running process, and only a
 * foreground service stays running on Android 15, hence the (silent, hideable) notification.
 */
class BatteryService : Service() {
    private var last: BatteryInfo? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(BatteryInfo.from(i))
                // Time left counts down between level changes; nobody sees it with the screen off.
                Intent.ACTION_TIME_TICK -> if (getSystemService(PowerManager::class.java).isInteractive) refresh()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Battery status", NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            stopSelf()
            return
        }
        History.prune(this, Prefs(this).historyDays)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK) }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun onBattery(info: BatteryInfo) {
        val prev = last
        last = info
        val tail = History.last(this)
        val moved = tail == null || tail.level != info.level || tail.plugged != info.plugged
        if (moved) {
            if (tail != null && tail.plugged != info.plugged) History.prune(this, Prefs(this).historyDays)
            History.add(this, Sample(System.currentTimeMillis(), info.level, info.plugged))
        }
        if (moved || prev?.status != info.status) refresh()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        DialWidget.update(this)
    }

    private fun notification(): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        BatteryInfo.read(this)?.let {
            b.setContentTitle("${it.level}% · ${it.statusText}")
            b.setContentText("Time left: " + timeLeftText(it, estimate(this, it, History.samples(this))))
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIFICATION = 1

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, BatteryService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Refused from the background (widget broadcasts, mostly). The next app launch or boot starts it,
                // and an unrestricted battery setting lifts the limit.
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = BatteryService.start(ctx)
}
