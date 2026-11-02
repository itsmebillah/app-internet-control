package com.itsmebillah.appinternetcontrol

import java.util.Locale

data class NetworkCounters(
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val elapsedRealtimeMillis: Long,
)

data class NetworkRates(val downloadBytesPerSecond: Double, val uploadBytesPerSecond: Double)

object NetworkSpeedCalculator {
    fun calculate(previous: NetworkCounters, current: NetworkCounters): NetworkRates? {
        val elapsedMillis = current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        val receivedDelta = current.receivedBytes - previous.receivedBytes
        val transmittedDelta = current.transmittedBytes - previous.transmittedBytes
        if (previous.receivedBytes < 0 || previous.transmittedBytes < 0 ||
            current.receivedBytes < 0 || current.transmittedBytes < 0 ||
            elapsedMillis <= 0 || receivedDelta < 0 || transmittedDelta < 0
        ) return null
        val seconds = elapsedMillis / 1000.0
        return NetworkRates(receivedDelta / seconds, transmittedDelta / seconds)
    }
}

object NetworkRateFormatter {
    fun format(bytesPerSecond: Double): String = when {
        !bytesPerSecond.isFinite() || bytesPerSecond < 0 -> "—"
        bytesPerSecond >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024))
        bytesPerSecond >= 1024 -> String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1024)
        else -> "%.0f B/s".format(bytesPerSecond)
    }
}
