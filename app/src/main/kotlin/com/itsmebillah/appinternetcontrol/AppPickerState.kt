package com.itsmebillah.appinternetcontrol

import java.util.Locale

/** Keeps selection independent from the currently visible search results. */
class AppPickerState(
    val apps: List<InstalledApp>,
    initiallySelected: Set<String>,
) {
    private val selected = initiallySelected.toMutableSet()

    fun filter(query: String): List<InstalledApp> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return apps
        return apps.filter { it.displayName.lowercase(Locale.ROOT).contains(needle) }
    }

    fun isSelected(packageName: String): Boolean = packageName in selected

    fun setSelected(packageName: String, isSelected: Boolean) {
        if (isSelected) selected.add(packageName) else selected.remove(packageName)
    }

    fun selectedPackages(): Set<String> = selected.toSet()
}
