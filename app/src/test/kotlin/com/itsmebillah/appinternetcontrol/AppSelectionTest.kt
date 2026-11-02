package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class AppSelectionTest {
    @Test
    fun togglingAddsAndRemovesPackageWithoutChangingPreviousSelection() {
        val original = AppSelection.of(setOf("com.example.one"))

        val blocked = original.withBlocked("com.example.two", blocked = true)
        val unblocked = blocked.withBlocked("com.example.one", blocked = false)

        assertEquals(setOf("com.example.one"), original.packageNames)
        assertEquals(setOf("com.example.one", "com.example.two"), blocked.packageNames)
        assertEquals(setOf("com.example.two"), unblocked.packageNames)
        assertNotSame(original, blocked)
    }

    @Test
    fun duplicateBlockRequestDoesNotDuplicatePackage() {
        val selection = AppSelection.of(setOf("com.example.one"))
            .withBlocked("com.example.one", blocked = true)

        assertEquals(setOf("com.example.one"), selection.packageNames)
    }

    @Test
    fun uninstallOrVisibilityLossRemovesStaleSelection() {
        val selection = AppSelection.of(setOf("com.example.one", "com.example.gone"))

        assertEquals(
            setOf("com.example.one"),
            selection.retainVisiblePackages(setOf("com.example.one")).packageNames,
        )
    }
}
