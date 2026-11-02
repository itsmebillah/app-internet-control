package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceLifecyclePolicyTest {
    @Test fun stopFilteringWhenNoAppsRemainEffectivelyBlocked() {
        assertTrue(VpnServiceLifecyclePolicy.shouldStop(emptySet()))
    }

    @Test fun retainVpnWhenAnyAppRemainsEffectivelyBlocked() {
        assertFalse(VpnServiceLifecyclePolicy.shouldStop(setOf("com.example.blocked")))
    }
}
