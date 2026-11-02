package com.itsmebillah.appinternetcontrol

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

data class UsageRecord(
    val date: LocalDate,
    val packageNames: Set<String>,
    val receivedBytes: Long,
    val transmittedBytes: Long,
) {
    init {
        require(packageNames.isNotEmpty())
        require(receivedBytes >= 0 && transmittedBytes >= 0)
    }
}

enum class UsagePeriod { DAY, MONTH, LIFETIME }

data class UsageSlice(val date: LocalDate, val startMillis: Long, val endMillis: Long)

object UsageWindowPlanner {
    fun split(startMillis: Long, endMillis: Long, zone: ZoneId): List<UsageSlice> {
        if (endMillis <= startMillis) return emptyList()
        val slices = mutableListOf<UsageSlice>()
        var cursor = startMillis
        while (cursor < endMillis) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val boundary = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(boundary, endMillis)
            slices += UsageSlice(date, cursor, sliceEnd)
            cursor = sliceEnd
        }
        return slices
    }
}

data class AppUsageTotal(
    val packageNames: Set<String>,
    val receivedBytes: Long,
    val transmittedBytes: Long,
) {
    val totalBytes: Long get() = receivedBytes + transmittedBytes
}

object UsageSummaryCalculator {
    fun aggregate(records: List<UsageRecord>, period: UsagePeriod, today: LocalDate): List<AppUsageTotal> {
        val month = YearMonth.from(today)
        val selected = records.filter { record ->
            when (period) {
                UsagePeriod.DAY -> record.date == today
                UsagePeriod.MONTH -> YearMonth.from(record.date) == month
                UsagePeriod.LIFETIME -> true
            }
        }
        return selected.groupBy { it.packageNames.toSortedSet().joinToString("|") }
            .map { (_, entries) ->
                AppUsageTotal(
                    packageNames = entries.first().packageNames,
                    receivedBytes = entries.sumOf { it.receivedBytes },
                    transmittedBytes = entries.sumOf { it.transmittedBytes },
                )
            }
            .sortedByDescending { it.totalBytes }
    }
}

object UsageBytesFormatter {
    fun format(bytes: Long): String {
        if (bytes < 0) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes ${units[unit]}" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
