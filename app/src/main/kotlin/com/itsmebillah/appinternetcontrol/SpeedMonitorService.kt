package com.itsmebillah.appinternetcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.content.pm.ServiceInfo

class SpeedMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SpeedMonitorStore
    private var previousCounters: NetworkCounters? = null
    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!store.isRunning) {
                stopMonitoring()
                return
            }
            val current = NetworkCounters(
                receivedBytes = TrafficStats.getTotalRxBytes(),
                transmittedBytes = TrafficStats.getTotalTxBytes(),
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            )
            val rates = previousCounters?.let { NetworkSpeedCalculator.calculate(it, current) }
            previousCounters = current.takeIf { it.receivedBytes >= 0 && it.transmittedBytes >= 0 }
            store.latestRates = rates
            broadcastRates(rates)
            updateNotification(rates)
            handler.postDelayed(this, store.intervalMillis)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SpeedMonitorStore(this)
        createNotificationChannel()
        startAsForeground(createNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.isRunning = false
            stopMonitoring()
            return START_NOT_STICKY
        }
        store.isRunning = true
        handler.removeCallbacks(sampleRunnable)
        previousCounters = null
        handler.post(sampleRunnable)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampleRunnable)
        if (::store.isInitialized) store.isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopMonitoring() {
        handler.removeCallbacks(sampleRunnable)
        store.latestRates = null
        broadcastRates(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            getString(R.string.speed_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ))
    }

    private fun createNotification(rates: NetworkRates?): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, SpeedMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = rates?.let {
            getString(R.string.speed_notification_rates,
                NetworkRateFormatter.format(it.downloadBytesPerSecond),
                NetworkRateFormatter.format(it.uploadBytesPerSecond))
        } ?: getString(R.string.speed_notification_waiting)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.speed_notification_title))
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.speed_stop),
                stopIntent,
            ).build())
            .build()
    }

    private fun updateNotification(rates: NetworkRates?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(rates))
    }

    private fun broadcastRates(rates: NetworkRates?) {
        sendBroadcast(Intent(ACTION_SPEED_UPDATED)
            .setPackage(packageName)
            .putExtra(EXTRA_AVAILABLE, rates != null)
            .putExtra(EXTRA_DOWNLOAD, rates?.downloadBytesPerSecond ?: 0.0)
            .putExtra(EXTRA_UPLOAD, rates?.uploadBytesPerSecond ?: 0.0))
    }

    companion object {
        const val ACTION_SPEED_UPDATED = "com.itsmebillah.appinternetcontrol.SPEED_UPDATED"
        const val EXTRA_AVAILABLE = "available"
        const val EXTRA_DOWNLOAD = "download_bps"
        const val EXTRA_UPLOAD = "upload_bps"
        const val ACTION_STOP = "com.itsmebillah.appinternetcontrol.STOP_SPEED_MONITOR"
        private const val CHANNEL_ID = "live_network_speed"
        private const val NOTIFICATION_ID = 2401
        private const val STOP_REQUEST_CODE = 2402
    }
}
