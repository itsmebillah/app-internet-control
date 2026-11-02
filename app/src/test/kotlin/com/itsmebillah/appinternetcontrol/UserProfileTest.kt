package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class UserProfileTest {
    private val now = Instant.parse("2026-11-02T09:30:00Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun activeProfileOverridesFocusAndManualForItsAllowList() {
        val focus = FocusMode("focus", "Focus", setOf("com.example.app"))
        val profile = UserProfile(
            id = "profile",
            name = "Work",
            blockedPackageNames = emptySet(),
            allowedPackageNames = setOf("com.example.app"),
        )

        assertEquals(
            emptySet<String>(),
            EffectiveBlockResolver.resolve(
                manualBlockedPackages = setOf("com.example.app"),
                activeFocusMode = focus,
                activeProfile = profile,
                schedules = emptyList(),
                temporaryBlocks = emptyList(),
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun activeScheduleOverridesProfileDecision() {
        val profile = UserProfile(
            id = "profile",
            name = "Study",
            blockedPackageNames = setOf("com.example.app"),
            allowedPackageNames = emptySet(),
        )
        val allow = BlockSchedule(
            id = "allow",
            name = "Break",
            packageNames = setOf("com.example.app"),
            action = ScheduleAction.ALLOW,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            weekdays = setOf(DayOfWeek.MONDAY),
        )

        assertEquals(
            emptySet<String>(),
            EffectiveBlockResolver.resolve(
                manualBlockedPackages = emptySet(),
                activeProfile = profile,
                schedules = listOf(allow),
                temporaryBlocks = emptyList(),
                now = now,
                zone = zone,
            ),
        )
    }

    @Test
    fun temporaryBlockOverridesProfileAllow() {
        val profile = UserProfile(
            id = "profile",
            name = "Work",
            blockedPackageNames = emptySet(),
            allowedPackageNames = setOf("com.example.app"),
        )
        val temporary = TemporaryBlock(
            packageNames = setOf("com.example.app"),
            startedAt = now.minusSeconds(1),
            expiresAt = now.plusSeconds(10),
        )

        assertEquals(
            setOf("com.example.app"),
            EffectiveBlockResolver.resolve(
                manualBlockedPackages = emptySet(),
                activeProfile = profile,
                schedules = emptyList(),
                temporaryBlocks = listOf(temporary),
                now = now,
                zone = zone,
            ),
        )
    }
}
