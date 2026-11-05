package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FocusModesUiStateTest {
    private val study = FocusMode("study", "Study", setOf("com.example.reader"))
    private val work = FocusMode("work", "Work", setOf("com.example.mail"))

    @Test
    fun loadingIsDistinctFromEmpty() {
        assertEquals(
            FocusModesUiState.Loading,
            FocusModesUiStateResolver.resolve(isLoading = true, snapshot = FocusModeSnapshot(emptyList(), null)),
        )
    }

    @Test
    fun emptySnapshotShowsOnlyEmptyState() {
        assertEquals(
            FocusModesUiState.Empty,
            FocusModesUiStateResolver.resolve(isLoading = false, snapshot = FocusModeSnapshot(emptyList(), null)),
        )
    }

    @Test
    fun modesWithoutActiveSelectionShowInactiveList() {
        assertEquals(
            FocusModesUiState.Inactive(listOf(study, work)),
            FocusModesUiStateResolver.resolve(isLoading = false, snapshot = FocusModeSnapshot(listOf(study, work), null)),
        )
    }

    @Test
    fun activeSelectionShowsCanonicalActiveMode() {
        val state = FocusModesUiStateResolver.resolve(
            isLoading = false,
            snapshot = FocusModeSnapshot(listOf(study, work), work),
        )

        assertEquals(FocusModesUiState.Active(listOf(study, work), work), state)
        assertSame(work, (state as FocusModesUiState.Active).activeMode)
    }

    @Test
    fun staleActiveModeNotPresentInPersistedListDoesNotShowActiveState() {
        val staleCopy = FocusMode("missing", "Old", setOf("com.example.old"))
        assertEquals(
            FocusModesUiState.Inactive(listOf(study)),
            FocusModesUiStateResolver.resolve(isLoading = false, snapshot = FocusModeSnapshot(listOf(study), staleCopy)),
        )
    }

    @Test
    fun activationAndDeactivationTransitionsUpdateTheRenderedState() {
        val modes = listOf(study, work)
        val inactive = FocusModesUiStateResolver.resolve(false, FocusModeSnapshot(modes, null))
        val active = FocusModesUiStateResolver.resolve(false, FocusModeSnapshot(modes, study))
        val deactivated = FocusModesUiStateResolver.resolve(false, FocusModeSnapshot(modes, null))

        assertEquals(FocusModesUiState.Inactive(modes), inactive)
        assertEquals(FocusModesUiState.Active(modes, study), active)
        assertEquals(FocusModesUiState.Inactive(modes), deactivated)
    }

    @Test
    fun resolvingPersistedSnapshotAgainAfterScreenRecreationKeepsTheSameState() {
        val persistedSnapshot = FocusModeSnapshot(listOf(study, work), study)

        assertEquals(
            FocusModesUiState.Active(listOf(study, work), study),
            FocusModesUiStateResolver.resolve(false, persistedSnapshot),
        )
        assertEquals(
            FocusModesUiState.Active(listOf(study, work), study),
            FocusModesUiStateResolver.resolve(false, persistedSnapshot.copy()),
        )
    }
}
