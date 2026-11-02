package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageHistoryTest {
    private val today = LocalDate.parse("2026-11-02")
    private val records = listOf(
        UsageRecord(today, setOf("com.example.a"), 1_000, 500),
        UsageRecord(today.minusDays(1), setOf("com.example.a"), 2_000, 1_000),
        UsageRecord(today.minusMonths(2), setOf("com.example.b"), 8_000, 2_000),
    )

    @Test
    fun totalsFilterByDayMonthAndLifetimeCoverage() {
        val daily = UsageSummaryCalculator.aggregate(records, UsagePeriod.DAY, today)
        val monthly = UsageSummaryCalculator.aggregate(records, UsagePeriod.MONTH, today)
        val lifetime = UsageSummaryCalculator.aggregate(records, UsagePeriod.LIFETIME, today)

        assertEquals(1_500, daily.single().totalBytes)
        assertEquals(4_500, monthly.single().totalBytes)
        assertEquals(14_500, lifetime.sumOf { it.totalBytes })
    }

    @Test
    fun sharedUidPackageGroupRemainsOneUsageRow() {
        val shared = setOf("com.example.a", "com.example.companion")
        val totals = UsageSummaryCalculator.aggregate(
            listOf(UsageRecord(today, shared, 20, 30), UsageRecord(today, shared, 5, 5)),
            UsagePeriod.DAY,
            today,
        )

        assertEquals(1, totals.size)
        assertEquals(60, totals.single().totalBytes)
        assertEquals(shared, totals.single().packageNames)
    }

    @Test
    fun splitsCollectionIntervalsAtLocalDayBoundariesIncludingDst() {
        val zone = ZoneId.of("America/New_York")
        val dayStart = LocalDate.parse("2026-03-08").atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = LocalDate.parse("2026-03-09").atStartOfDay(zone).toInstant().toEpochMilli()
        val slices = UsageWindowPlanner.split(dayStart, dayEnd, zone)

        assertEquals(1, slices.size)
        assertEquals(23 * 60 * 60 * 1000L, slices.single().endMillis - slices.single().startMillis)
        assertTrue(UsageWindowPlanner.split(dayEnd, dayStart, zone).isEmpty())
    }

    @Test
    fun formatterUsesBinaryUnitsAndPreservesZero() {
        assertEquals("0 B", UsageBytesFormatter.format(0))
        assertEquals("1.0 KB", UsageBytesFormatter.format(1_024))
        assertEquals("1.0 MB", UsageBytesFormatter.format(1_048_576))
    }
}
