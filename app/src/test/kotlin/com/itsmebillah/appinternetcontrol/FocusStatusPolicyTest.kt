package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusStatusPolicyTest {
    @Test
    fun activeVpnTakesPrecedenceOverPendingRuleText() {
        assertEquals(
            FocusStatus.ACTIVE_UNVERIFIED,
            FocusStatusPolicy.resolve(vpnActive = true, hasEffectiveBlocks = true, activeModeName = "Study"),
        )
    }

    @Test
    fun pendingRuleAndInactiveModeAreShownWhenVpnIsStopped() {
        assertEquals(
            FocusStatus.RULE_NOT_ACTIVE,
            FocusStatusPolicy.resolve(vpnActive = false, hasEffectiveBlocks = true, activeModeName = "Study"),
        )
        assertEquals(
            FocusStatus.MODE_ACTIVE,
            FocusStatusPolicy.resolve(vpnActive = false, hasEffectiveBlocks = false, activeModeName = "Study"),
        )
        assertEquals(
            FocusStatus.NO_SELECTION,
            FocusStatusPolicy.resolve(vpnActive = false, hasEffectiveBlocks = false, activeModeName = null),
        )
    }
}
