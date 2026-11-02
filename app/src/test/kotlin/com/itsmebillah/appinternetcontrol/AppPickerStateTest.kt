package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickerStateTest {
    private val apps = listOf(
        InstalledApp("com.google.chrome", "Chrome"),
        InstalledApp("org.mozilla.firefox", "Firefox"),
        InstalledApp("com.brave.browser", "Brave Browser"),
    )

    @Test
    fun filtersAppNamesWithoutCaseSensitivityAndKeepsAllForBlankQuery() {
        val picker = AppPickerState(apps, emptySet())

        assertEquals(listOf("Chrome"), picker.filter("cHrOmE").map { it.displayName })
        assertEquals(listOf("Brave Browser"), picker.filter("  BROWSER ").map { it.displayName })
        assertEquals(apps, picker.filter("  "))
        assertTrue(picker.filter("not installed").isEmpty())
    }

    @Test
    fun selectionSurvivesFilteringAndCanBeEditedWithoutLosingOtherChoices() {
        val picker = AppPickerState(apps, setOf("com.google.chrome"))

        picker.filter("brave").single().let { picker.setSelected(it.packageName, true) }
        picker.filter("firefox").single().let { picker.setSelected(it.packageName, false) }

        assertTrue(picker.isSelected("com.google.chrome"))
        assertTrue(picker.isSelected("com.brave.browser"))
        assertFalse(picker.isSelected("org.mozilla.firefox"))
        assertEquals(setOf("com.google.chrome", "com.brave.browser"), picker.selectedPackages())
    }
}
