package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class WifiAccessRuleTest {
    private val home = WifiNetworkIdentity(networkId = 3, ssid = "Home", securityType = 2)
    private val office = WifiNetworkIdentity(networkId = 8, ssid = "Office", securityType = 4)
    private val rule = WifiAccessRule("home-rule", home, setOf("com.example.browser"))

    @Test
    fun appIsNotBlockedWhileConnectedToExactSavedNetworkIdentity() {
        assertEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(listOf(rule), home))
    }

    @Test
    fun appIsBlockedOnOtherNetworkOrWhenNetworkIdentityIsUnavailable() {
        assertEquals(setOf("com.example.browser"), WifiAccessRuleEvaluator.blockedPackages(listOf(rule), office))
        assertEquals(setOf("com.example.browser"), WifiAccessRuleEvaluator.blockedPackages(listOf(rule), null))
    }

    @Test
    fun appMayUseAnyOfMultipleExplicitlySelectedNetworks() {
        val second = rule.copy(id = "office-rule", network = office)
        assertEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(listOf(rule, second), office))
        assertEquals(setOf("com.example.browser"), WifiAccessRuleEvaluator.blockedPackages(listOf(rule, second), null))
    }

    @Test
    fun disabledRulesDoNotContributeAndEmptyRulesAreNeutral() {
        assertEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(emptyList(), null))
        assertEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(listOf(rule.copy(enabled = false)), office))
    }

    @Test
    fun enableAndDisablePersistExplicitStateWithoutChangingNetworkOrApps() {
        val disabled = WifiAccessRuleMutations.setEnabled(listOf(rule), rule.id, enabled = false).single()
        assertEquals(rule.id, disabled.id)
        assertEquals(rule.network, disabled.network)
        assertEquals(rule.packageNames, disabled.packageNames)
        assertEquals(false, disabled.enabled)
        assertEquals(disabled, WifiAccessRuleCodec.decode(WifiAccessRuleCodec.encode(disabled)))

        val enabled = WifiAccessRuleMutations.setEnabled(listOf(disabled), rule.id, enabled = true).single()
        assertEquals(rule, WifiAccessRuleCodec.decode(WifiAccessRuleCodec.encode(enabled)))
    }

    @Test
    fun repeatedAndRapidExplicitUpdatesDoNotUseStaleRuleSnapshots() {
        val other = rule.copy(id = "office-rule", network = office, packageNames = setOf("com.example.other"))
        val afterDisable = WifiAccessRuleMutations.setEnabled(listOf(rule, other), rule.id, enabled = false)
        val afterRepeatedDisable = WifiAccessRuleMutations.setEnabled(afterDisable, rule.id, enabled = false)
        val afterOtherRuleUpdate = WifiAccessRuleMutations.setEnabled(afterRepeatedDisable, other.id, enabled = false)

        assertEquals(false, afterOtherRuleUpdate.first { it.id == rule.id }.enabled)
        assertEquals(false, afterOtherRuleUpdate.first { it.id == other.id }.enabled)
        assertEquals(rule.network, afterOtherRuleUpdate.first { it.id == rule.id }.network)
        assertEquals(rule.packageNames, afterOtherRuleUpdate.first { it.id == rule.id }.packageNames)
    }

    @Test
    fun enablingAndDisablingImmediatelyChangesEffectiveWifiDecision() {
        val disabled = WifiAccessRuleMutations.setEnabled(listOf(rule), rule.id, enabled = false)
        val enabledAgain = WifiAccessRuleMutations.setEnabled(disabled, rule.id, enabled = true)

        assertEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(disabled, office))
        assertEquals(setOf("com.example.browser"), WifiAccessRuleEvaluator.blockedPackages(enabledAgain, office))
        assertEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(enabledAgain, home))
    }

    @Test
    fun disablingOneWifiRulePreservesAnotherRuleAndAllNonWifiBlocks() {
        val homeRule = rule
        val officeRule = rule.copy(id = "office-rule", network = office)
        val onlyOfficeEnabled = WifiAccessRuleMutations.setEnabled(listOf(homeRule, officeRule), homeRule.id, enabled = false)
        val scheduleBlock = BlockSchedule(
            id = "schedule",
            name = "Scheduled block",
            packageNames = setOf("com.example.scheduled"),
            action = ScheduleAction.BLOCK,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            weekdays = setOf(java.time.DayOfWeek.MONDAY),
        )
        val focus = FocusMode("focus", "Focus", setOf("com.example.focus"))
        val temporary = TemporaryBlock(
            id = "temporary",
            packageNames = setOf("com.example.temporary"),
            startedAt = Instant.parse("2026-11-02T09:00:00Z"),
            expiresAt = Instant.parse("2026-11-02T11:00:00Z"),
        )
        val result = EffectiveBlockResolver.resolve(
            manualBlockedPackages = setOf("com.example.manual"),
            activeFocusMode = focus,
            schedules = listOf(scheduleBlock),
            temporaryBlocks = listOf(temporary),
            wifiAccessRules = onlyOfficeEnabled,
            currentWifiNetwork = home,
            now = Instant.parse("2026-11-02T10:00:00Z"),
            zone = ZoneId.of("UTC"),
        )

        assertEquals(
            setOf("com.example.browser", "com.example.manual", "com.example.focus", "com.example.scheduled", "com.example.temporary"),
            result,
        )
        assertNotEquals(emptySet<String>(), WifiAccessRuleEvaluator.blockedPackages(onlyOfficeEnabled, home))
        assertTrue(onlyOfficeEnabled.first { it.id == homeRule.id }.packageNames.contains("com.example.browser"))
    }

    @Test
    fun wifiRestrictionIsAnAdditionalDenyEvenWhenAnActiveScheduleAllowsTheApp() {
        val scheduleAllow = BlockSchedule(
            id = "allow",
            name = "Allow",
            packageNames = setOf("com.example.browser"),
            action = ScheduleAction.ALLOW,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            weekdays = setOf(java.time.DayOfWeek.MONDAY),
        )
        val now = Instant.parse("2026-11-02T10:00:00Z")

        val outsideWifi = EffectiveBlockResolver.resolve(
            manualBlockedPackages = emptySet(),
            schedules = listOf(scheduleAllow),
            temporaryBlocks = emptyList(),
            wifiAccessRules = listOf(rule),
            currentWifiNetwork = office,
            now = now,
            zone = ZoneId.of("UTC"),
        )
        assertEquals(setOf("com.example.browser"), outsideWifi)

        val onHomeWifiWithManualBlock = EffectiveBlockResolver.resolve(
            manualBlockedPackages = setOf("com.example.browser"),
            schedules = emptyList(),
            temporaryBlocks = emptyList(),
            wifiAccessRules = listOf(rule),
            currentWifiNetwork = home,
            now = now,
            zone = ZoneId.of("UTC"),
        )
        assertEquals(setOf("com.example.browser"), onHomeWifiWithManualBlock)
    }

    @Test
    fun temporaryAndOverlappingScheduleRulesRemainEffectiveAlongsideWifiRule() {
        val schedule = BlockSchedule(
            id = "block",
            name = "Scheduled",
            packageNames = setOf("com.example.other"),
            action = ScheduleAction.BLOCK,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            weekdays = setOf(java.time.DayOfWeek.MONDAY),
        )
        val temporary = TemporaryBlock(
            id = "temporary",
            packageNames = setOf("com.example.temp"),
            startedAt = Instant.parse("2026-11-02T09:00:00Z"),
            expiresAt = Instant.parse("2026-11-02T11:00:00Z"),
        )
        val result = EffectiveBlockResolver.resolve(
            manualBlockedPackages = emptySet(),
            schedules = listOf(schedule, schedule.copy(id = "overlap")),
            temporaryBlocks = listOf(temporary),
            wifiAccessRules = listOf(rule),
            currentWifiNetwork = null,
            now = Instant.parse("2026-11-02T10:00:00Z"),
            zone = ZoneId.of("UTC"),
        )
        assertEquals(setOf("com.example.browser", "com.example.other", "com.example.temp"), result)
    }
}
