package com.itsmebillah.appinternetcontrol

import android.content.Context

class SpeedMonitorStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var intervalMillis: Long
        get() = preferences.getLong(INTERVAL_KEY, DEFAULT_INTERVAL_MILLIS).coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
        set(value) = preferences.edit().putLong(INTERVAL_KEY, value.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)).apply()

    var isRunning: Boolean
        get() = preferences.getBoolean(RUNNING_KEY, false)
        set(value) = preferences.edit().putBoolean(RUNNING_KEY, value).apply()

    var latestRates: NetworkRates?
        get() = if (!preferences.getBoolean(AVAILABLE_KEY, false)) null else NetworkRates(
            preferences.getFloat(DOWNLOAD_KEY, 0f).toDouble(),
            preferences.getFloat(UPLOAD_KEY, 0f).toDouble(),
        )
        set(value) {
            preferences.edit()
                .putBoolean(AVAILABLE_KEY, value != null)
                .putFloat(DOWNLOAD_KEY, value?.downloadBytesPerSecond?.toFloat() ?: 0f)
                .putFloat(UPLOAD_KEY, value?.uploadBytesPerSecond?.toFloat() ?: 0f)
                .apply()
        }

    companion object {
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val MAX_INTERVAL_MILLIS = 10_000L
        const val DEFAULT_INTERVAL_MILLIS = 2_000L
        private const val PREFERENCES_NAME = "speed_monitor"
        private const val INTERVAL_KEY = "interval_ms"
        private const val RUNNING_KEY = "running"
        private const val AVAILABLE_KEY = "available"
        private const val DOWNLOAD_KEY = "download_bps"
        private const val UPLOAD_KEY = "upload_bps"
    }
}
