package com.itsmebillah.appinternetcontrol

class AppSelection private constructor(packageNames: Set<String>) {
    val packageNames: Set<String> = packageNames.toSet()

    fun withBlocked(packageName: String, blocked: Boolean): AppSelection {
        require(packageName.isNotBlank())
        val updated = packageNames.toMutableSet().apply {
            if (blocked) add(packageName) else remove(packageName)
        }
        return AppSelection(updated.toSet())
    }

    fun retainVisiblePackages(visiblePackages: Set<String>): AppSelection =
        AppSelection(packageNames.intersect(visiblePackages))

    companion object {
        fun of(packageNames: Set<String> = emptySet()) = AppSelection(packageNames.toSet())
    }
}
