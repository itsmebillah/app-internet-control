package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRoutingPolicyTest {
    @Test
    fun routesOnlySelectedVisibleAppsAndNeverRoutesThisAppThroughItself() {
        val routes = VpnRoutingPolicy.selectedInstalledPackages(
            selection = AppSelection.of(
                setOf("com.example.blocked", "com.example.uninstalled", "com.itsmebillah.appinternetcontrol"),
            ),
            installedPackages = setOf("com.example.blocked", "com.itsmebillah.appinternetcontrol"),
            ownPackage = "com.itsmebillah.appinternetcontrol",
        )

        assertEquals(listOf("com.example.blocked"), routes)
    }
}
