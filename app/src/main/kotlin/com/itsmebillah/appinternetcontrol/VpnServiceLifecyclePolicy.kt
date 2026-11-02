package com.itsmebillah.appinternetcontrol

object VpnServiceLifecyclePolicy {
    fun shouldStop(effectiveBlockedPackages: Set<String>): Boolean = effectiveBlockedPackages.isEmpty()
}
