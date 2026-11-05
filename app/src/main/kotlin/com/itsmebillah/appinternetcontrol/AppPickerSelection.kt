package com.itsmebillah.appinternetcontrol

/** Mutable selection state for app pickers, keyed by package name rather than row position. */
class AppPickerSelection(apps: List<InstalledApp>) {
    private val availablePackages = apps.mapTo(linkedSetOf()) { it.packageName }
    private val selectedPackages = linkedSetOf<String>()

    fun isSelected(packageName: String): Boolean = packageName in selectedPackages

    fun setSelected(packageName: String, selected: Boolean) {
        if (packageName !in availablePackages) return
        if (selected) selectedPackages.add(packageName) else selectedPackages.remove(packageName)
    }

    fun selectedPackageNames(): Set<String> = selectedPackages.toSet()
}

object AppPickerFilter {
    fun filter(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return apps
        return apps.filter { app ->
            app.displayName.contains(normalizedQuery, ignoreCase = true) ||
                app.packageName.contains(normalizedQuery, ignoreCase = true)
        }
    }
}
