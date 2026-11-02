package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class TemporaryBlockTest {
    private val now = Instant.parse("2026-11-02T09:30:00Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun temporaryBlockOverridesManualAndScheduledAllowOnlyUntilItsExpiry() {
        val scheduledAllow = BlockSchedule(
            id = "allow",
            name = "Allow window",
            packageNames = setOf("com.example.app"),
            action = ScheduleAction.ALLOW,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            weekdays = setOf(DayOfWeek.MONDAY),
        )
        val temporary = TemporaryBlock(
            id = "temporary",
            packageNames = setOf("com.example.app"),
            startedAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(60),
        )

        assertEquals(
            setOf("com.example.app"),
            EffectiveBlockResolver.resolve(
                manualBlockedPackages = setOf("com.example.app"),
                schedules = listOf(scheduledAllow),
                temporaryBlocks = listOf(temporary),
                now = now,
                zone = zone,
            ),
        )
        assertTrue(
            EffectiveBlockResolver.resolve(
                manualBlockedPackages = setOf("com.example.app"),
                schedules = listOf(scheduledAllow),
                temporaryBlocks = listOf(temporary),
                now = temporary.expiresAt,
                zone = zone,
            ).isEmpty(),
        )
    }

    @Test
    fun overlappingTemporaryBlocksExpireIndependently() {
        val earlier = TemporaryBlock(
            id = "earlier",
            packageNames = setOf("com.example.app"),
            startedAt = now.minusSeconds(30),
            expiresAt = now.plusSeconds(30),
        )
        val later = earlier.copy(id = "later", expiresAt = now.plusSeconds(90))

        assertEquals(
            setOf("com.example.app"),
            TemporaryBlockEvaluator.activePackageNames(listOf(earlier, later), earlier.expiresAt),
        )
        assertEquals(later.expiresAt, TemporaryBlockEvaluator.nextExpiry(listOf(earlier, later), earlier.expiresAt))
        assertTrue(TemporaryBlockEvaluator.activePackageNames(listOf(later), later.expiresAt).isEmpty())
    }

    @Test
    fun notYetStartedTemporaryBlockDoesNotApply() {
        val future = TemporaryBlock(
            packageNames = setOf("com.example.app"),
            startedAt = now.plusSeconds(10),
            expiresAt = now.plusSeconds(70),
        )

        assertFalse("com.example.app" in TemporaryBlockEvaluator.activePackageNames(listOf(future), now))
        assertEquals(null, TemporaryBlockEvaluator.nextExpiry(listOf(future), now))
    }
}
