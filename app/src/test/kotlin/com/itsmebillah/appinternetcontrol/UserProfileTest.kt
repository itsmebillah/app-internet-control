package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class UserProfileTest {
    private val now = Instant.parse("2026-11-02T09:30:00Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun profileCreateEditActivateDeletePersistsAndDoesNotTouchOtherRuleSources() {
        val persistence = FakeProfilePersistence()
        val store = UserProfileStore(persistence)
        val original = UserProfile("work", "Work", setOf("blocked.app"), setOf("allowed.app"))
        store.save(original)
        store.activate(original.id)

        // A new store instance represents an app restart.
        val restarted = UserProfileStore(persistence)
        assertEquals(original, restarted.activeProfile())
        val edited = original.copy(name = "Work v2", allowedPackageNames = setOf("manual.app"))
        restarted.save(edited)
        assertEquals(listOf(edited), restarted.read())
        assertEquals(edited, restarted.activeProfile())

        val schedule = BlockSchedule("schedule", "Scheduled", setOf("scheduled.app"), ScheduleAction.BLOCK,
            LocalTime.of(9, 0), LocalTime.of(10, 0), setOf(DayOfWeek.MONDAY))
        val timer = TemporaryBlock(packageNames = setOf("temporary.app"), startedAt = now.minusSeconds(1), expiresAt = now.plusSeconds(30))
        val effective = EffectiveBlockResolver.resolve(
            manualBlockedPackages = setOf("manual.app", "other.manual"),
            activeProfile = restarted.activeProfile(),
            schedules = listOf(schedule),
            temporaryBlocks = listOf(timer),
            now = now,
            zone = zone,
        )
        assertEquals(setOf("blocked.app", "other.manual", "scheduled.app", "temporary.app"), effective)

        restarted.delete(edited.id)
        assertNull(restarted.activeProfile())
        assertEquals(emptyList<UserProfile>(), restarted.read())
        val afterDelete = EffectiveBlockResolver.resolve(
            manualBlockedPackages = setOf("manual.app", "other.manual"),
            schedules = listOf(schedule),
            temporaryBlocks = listOf(timer),
            now = now,
            zone = zone,
        )
        assertEquals(setOf("manual.app", "other.manual", "scheduled.app", "temporary.app"), afterDelete)
    }

    @Test
    fun activatingAnotherProfileLeavesExistingSchedulesAndTemporaryRecordsIndependent() {
        val store = UserProfileStore(FakeProfilePersistence())
        store.save(UserProfile("one", "One", setOf("a.app"), emptySet()))
        store.save(UserProfile("two", "Two", setOf("b.app"), emptySet()))
        store.activate("one")
        store.activate("two")
        assertEquals("two", store.activeProfile()?.id)
        assertNotNull(store.read().firstOrNull { it.id == "one" })
    }

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

    private class FakeProfilePersistence : ProfilePersistence {
        private var profiles: String? = null
        private var active: String? = null
        override fun readProfiles() = profiles
        override fun writeProfiles(value: String) { profiles = value }
        override fun readActiveId() = active
        override fun writeActiveId(value: String?) { active = value }
    }
}
