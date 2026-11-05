package com.itsmebillah.appinternetcontrol

import java.util.UUID

/** A device-local identity for one saved Wi-Fi configuration. BSSID is intentionally not stored. */
data class WifiNetworkIdentity(
    val networkId: Int,
    val ssid: String,
    val securityType: Int? = null,
) {
    init {
        require(networkId >= 0)
        require(ssid.isNotBlank())
    }
}

data class WifiAccessRule(
    val id: String = UUID.randomUUID().toString(),
    val network: WifiNetworkIdentity,
    val packageNames: Set<String>,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank())
        require(packageNames.isNotEmpty())
    }
}

/**
 * An enabled Wi-Fi rule is an additional deny constraint. For an app covered by one or more rules,
 * it is blocked unless the connected Wi-Fi identity matches at least one of those saved networks.
 * Existing schedules/focus/manual resolution still applies on a matching Wi-Fi network.
 * Unknown network identity fails closed.
 */
object WifiAccessRuleEvaluator {
    fun blockedPackages(rules: List<WifiAccessRule>, currentNetwork: WifiNetworkIdentity?): Set<String> {
        val enabled = rules.filter { it.enabled }
        if (enabled.isEmpty()) return emptySet()
        val allowedNetworksByPackage = mutableMapOf<String, MutableSet<WifiNetworkIdentity>>()
        enabled.forEach { rule ->
            rule.packageNames.forEach { packageName ->
                allowedNetworksByPackage.getOrPut(packageName) { mutableSetOf() }.add(rule.network)
            }
        }
        return allowedNetworksByPackage.asSequence()
            .filter { (_, networks) -> currentNetwork == null || networks.none { it == currentNetwork } }
            .map { (packageName, _) -> packageName }
            .toSet()
    }
}
