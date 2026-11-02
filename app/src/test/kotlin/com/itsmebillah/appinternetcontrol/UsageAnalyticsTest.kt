package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UsageAnalyticsTest {
    private val today = LocalDate.parse("2026-11-02")

    @Test
    fun ranksTopAppsBySelectedPeriodAndAppliesLimit() {
        val records = listOf(
            UsageRecord(today, setOf("com.example.one"), 500, 0),
            UsageRecord(today, setOf("com.example.two"), 900, 100),
            UsageRecord(today.minusDays(1), setOf("com.example.one"), 4_000, 0),
        )

        val daily = UsageAnalyticsCalculator.topApps(records, UsagePeriod.DAY, today, limit = 1)
        val lifetime = UsageAnalyticsCalculator.topApps(records, UsagePeriod.LIFETIME, today, limit = 2)

        assertEquals(setOf("com.example.two"), daily.single().packageNames)
        assertEquals(1_000, daily.single().totalBytes)
        assertEquals(setOf("com.example.one"), lifetime.first().packageNames)
        assertEquals(2, lifetime.size)
    }

    @Test
    fun trendIncludesOnlyDaysWithCapturedCoverage() {
        val records = listOf(
            UsageRecord(today, setOf("com.example.one"), 700, 300),
            UsageRecord(today.minusDays(2), setOf("com.example.one"), 1_000, 0),
        )
        val coverage = mapOf(today to 60_000L, today.minusDays(1) to 0L, today.minusDays(2) to 30_000L)

        val trend = UsageAnalyticsCalculator.recentDays(records, coverage, today)

        assertEquals(listOf(today.minusDays(2), today), trend.map { it.date })
        assertEquals(1_000, trend[0].receivedBytes + trend[0].transmittedBytes)
        assertEquals(1_000, trend[1].receivedBytes + trend[1].transmittedBytes)
    }

    @Test
    fun coveredDayWithoutAppUsageIsExplicitZeroAndNotMissingDay() {
        val point = UsageAnalyticsCalculator.recentDays(
            emptyList(), mapOf(today to 10_000L), today,
        ).single()

        assertEquals(0, point.receivedBytes + point.transmittedBytes)
        assertEquals(10_000, point.coveredMillis)
    }

    @Test
    fun zeroDayTrendLimitReturnsNoPoints() {
        assertTrue(UsageAnalyticsCalculator.recentDays(emptyList(), emptyMap(), today, dayCount = 0).isEmpty())
    }
}
