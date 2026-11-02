package com.itsmebillah.appinternetcontrol

object VpnRoutingPolicy {
    fun selectedInstalledPackages(
        selection: AppSelection,
        installedPackages: Set<String>,
        ownPackage: String,
    ): List<String> = selection.packageNames
        .intersect(installedPackages)
        .filterNot { it == ownPackage }
        .sorted()
}
