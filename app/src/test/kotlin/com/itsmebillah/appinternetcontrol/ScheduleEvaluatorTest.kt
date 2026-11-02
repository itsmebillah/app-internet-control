package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ScheduleEvaluatorTest {
    private val zone = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 11, 2)

    @Test
    fun recurringScheduleIncludesStartAndExcludesEnd() {
        val schedule = recurring(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0))

        assertTrue(ScheduleEvaluator.isActive(schedule, monday.atTime(9, 0).atZone(zone).toInstant(), zone))
        assertTrue(ScheduleEvaluator.isActive(schedule, monday.atTime(9, 59).atZone(zone).toInstant(), zone))
        assertFalse(ScheduleEvaluator.isActive(schedule, monday.atTime(10, 0).atZone(zone).toInstant(), zone))
    }

    @Test
    fun overnightScheduleContinuesAfterMidnightFromSelectedStartDay() {
        val schedule = recurring(DayOfWeek.MONDAY, LocalTime.of(22, 0), LocalTime.of(6, 0))

        assertTrue(ScheduleEvaluator.isActive(schedule, monday.plusDays(1).atTime(1, 0).atZone(zone).toInstant(), zone))
        assertFalse(ScheduleEvaluator.isActive(schedule, monday.plusDays(1).atTime(6, 0).atZone(zone).toInstant(), zone))
    }

    @Test
    fun oneTimeScheduleOnlyAppliesOnItsDateAndEndIsExclusive() {
        val schedule = BlockSchedule(
            name = "One time",
            packageNames = setOf("com.example.app"),
            action = ScheduleAction.BLOCK,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            oneTimeDate = monday,
        )

        assertTrue(ScheduleEvaluator.isActive(schedule, monday.atTime(9, 30).atZone(zone).toInstant(), zone))
        assertFalse(ScheduleEvaluator.isActive(schedule, monday.plusDays(1).atTime(9, 30).atZone(zone).toInstant(), zone))
        assertFalse(ScheduleEvaluator.isActive(schedule, monday.atTime(10, 0).atZone(zone).toInstant(), zone))
    }

    @Test
    fun activeScheduleOverridesManualStateAndBlockWinsScheduleConflicts() {
        val block = recurring(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0))
        val allow = block.copy(id = "allow", action = ScheduleAction.ALLOW)
        val allowOnly = allow.copy(packageNames = setOf("com.example.manual"))
        val now = monday.atTime(9, 30).atZone(zone).toInstant()

        assertEquals(
            setOf("com.example.scheduled"),
            ScheduleEvaluator.effectiveBlockedPackages(
                manualBlockedPackages = setOf("com.example.manual"),
                schedules = listOf(block, allow, allowOnly),
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun daylightSavingGapMovesLocalBoundaryForwardUsingZoneRules() {
        val newYork = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val schedule = BlockSchedule(
            name = "DST",
            packageNames = setOf("com.example.app"),
            action = ScheduleAction.BLOCK,
            startTime = LocalTime.of(2, 30),
            endTime = LocalTime.of(4, 0),
            oneTimeDate = date,
        )

        assertTrue(ScheduleEvaluator.isActive(schedule, date.atTime(3, 35).atZone(newYork).toInstant(), newYork))
    }

    @Test
    fun nextBoundaryFindsNextStartInDeviceLocalTime() {
        val schedule = recurring(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0))
        val now = monday.atTime(8, 0).atZone(zone).toInstant()

        assertEquals(monday.atTime(9, 0).atZone(zone).toInstant(), ScheduleEvaluator.nextBoundary(listOf(schedule), now, zone))
    }

    private fun recurring(day: DayOfWeek, start: LocalTime, end: LocalTime) = BlockSchedule(
        name = "Test schedule",
        packageNames = setOf("com.example.scheduled"),
        action = ScheduleAction.BLOCK,
        startTime = start,
        endTime = end,
        weekdays = setOf(day),
    )
}
