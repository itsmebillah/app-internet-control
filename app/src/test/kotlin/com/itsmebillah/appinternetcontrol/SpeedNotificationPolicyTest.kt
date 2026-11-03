package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedNotificationPolicyTest {
    @Test
    fun monitoringRequiresNotificationPermissionAndVisibleChannel() {
        assertTrue(SpeedNotificationPolicy.canMonitor(true, true, true))
        assertFalse(SpeedNotificationPolicy.canMonitor(false, true, true))
        assertFalse(SpeedNotificationPolicy.canMonitor(true, false, true))
        assertFalse(SpeedNotificationPolicy.canMonitor(true, true, false))
    }
}
