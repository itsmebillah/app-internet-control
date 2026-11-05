package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickerSelectionTest {
    private val apps = listOf(
        InstalledApp("com.example.one", "One"),
        InstalledApp("com.example.two", "Two"),
    )

    @Test
    fun selectionsAreTrackedByPackageAndCanBeUnchecked() {
        val selection = AppPickerSelection(apps)

        selection.setSelected("com.example.two", true)
        assertTrue(selection.isSelected("com.example.two"))
        assertEquals(setOf("com.example.two"), selection.selectedPackageNames())

        selection.setSelected("com.example.two", false)
        assertFalse(selection.isSelected("com.example.two"))
        assertTrue(selection.selectedPackageNames().isEmpty())
    }

    @Test
    fun unavailablePackagesCannotBeAddedToSelection() {
        val selection = AppPickerSelection(apps)

        selection.setSelected("com.example.unknown", true)

        assertTrue(selection.selectedPackageNames().isEmpty())
    }

    @Test
    fun filterMatchesDisplayNameAndPackageNameWithoutCaseSensitivity() {
        assertEquals(listOf(apps[0]), AppPickerFilter.filter(apps, "oNe"))
        assertEquals(listOf(apps[1]), AppPickerFilter.filter(apps, "EXAMPLE.TWO"))
    }

    @Test
    fun emptyFilterReturnsAllAppsAndFilteringDoesNotClearSelection() {
        val selection = AppPickerSelection(apps)
        selection.setSelected("com.example.two", true)

        assertEquals(apps, AppPickerFilter.filter(apps, " "))
        assertEquals(setOf("com.example.two"), selection.selectedPackageNames())
    }

    @Test
    fun appRowsKeepInstalledDisplayOrderAndAppendMissingPackagesInOriginalOrder() {
        val packages = listOf("com.example.two", "removed.package", "com.example.one")

        assertEquals(
            listOf("com.example.one", "com.example.two", "removed.package"),
            AppRowOrdering.preserve(packages, apps),
        )
    }

    @Test
    fun appRowsWithNoKnownAppsPreserveTheirExistingOrder() {
        val packages = listOf("z.package", "a.package")

        assertEquals(packages, AppRowOrdering.preserve(packages, emptyList()))
    }
}
