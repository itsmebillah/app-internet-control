package com.itsmebillah.appinternetcontrol

import android.content.Context

class AppSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): AppSelection = AppSelection.of(
        preferences.getStringSet(SELECTED_PACKAGES_KEY, emptySet()).orEmpty(),
    )

    fun write(selection: AppSelection) {
        preferences.edit()
            .putStringSet(SELECTED_PACKAGES_KEY, selection.packageNames.toSet())
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "app_selection"
        private const val SELECTED_PACKAGES_KEY = "blocked_package_names"
    }
}
