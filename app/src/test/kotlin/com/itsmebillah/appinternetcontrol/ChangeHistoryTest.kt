package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChangeHistoryTest {
    private val timestamp = Instant.parse("2026-11-02T09:30:00Z")

    @Test
    fun recordsBlockAndAllowTransitionsWithTheirControllingSource() {
        val before = mapOf("com.example.app" to EffectiveDecision(false, ChangeSource.MANUAL))
        val blocked = mapOf("com.example.app" to EffectiveDecision(true, ChangeSource.PROFILE))

        val block = ChangeHistoryReducer.transitions(before, blocked, timestamp) { EnforcementOutcome.PENDING }.single()
        assertTrue(block.blocked)
        assertEquals(ChangeSource.PROFILE, block.source)
        assertEquals(EnforcementOutcome.PENDING, block.outcome)

        val allow = ChangeHistoryReducer.transitions(blocked, before, timestamp.plusSeconds(1)) {
            EnforcementOutcome.NOT_ACTIVE
        }.single()
        assertEquals(false, allow.blocked)
        assertEquals(ChangeSource.PROFILE, allow.source)
    }

    @Test
    fun unchangedDecisionsDoNotCreateHistoryEntries() {
        val decisions = mapOf("com.example.app" to EffectiveDecision(true, ChangeSource.TIMER))

        assertTrue(ChangeHistoryReducer.transitions(decisions, decisions, timestamp) {
            EnforcementOutcome.ACTIVE_UNVERIFIED
        }.isEmpty())
    }

    @Test
    fun newPackageDoesNotCreateAnArtificialInitialTransition() {
        val current = mapOf("com.example.app" to EffectiveDecision(true, ChangeSource.MANUAL))

        assertTrue(ChangeHistoryReducer.transitions(emptyMap(), current, timestamp) {
            EnforcementOutcome.PENDING
        }.isEmpty())
    }
}
