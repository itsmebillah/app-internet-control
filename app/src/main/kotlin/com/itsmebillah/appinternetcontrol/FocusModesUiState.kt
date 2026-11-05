package com.itsmebillah.appinternetcontrol

sealed interface FocusModesUiState {
    data object Loading : FocusModesUiState
    data object Empty : FocusModesUiState
    data class Inactive(val modes: List<FocusMode>) : FocusModesUiState
    data class Active(val modes: List<FocusMode>, val activeMode: FocusMode) : FocusModesUiState
}

object FocusModesUiStateResolver {
    fun resolve(isLoading: Boolean, snapshot: FocusModeSnapshot): FocusModesUiState {
        if (isLoading) return FocusModesUiState.Loading
        if (snapshot.modes.isEmpty()) return FocusModesUiState.Empty

        val activeMode = snapshot.activeMode?.id?.let { activeId ->
            snapshot.modes.firstOrNull { it.id == activeId }
        }
        return if (activeMode == null) {
            FocusModesUiState.Inactive(snapshot.modes)
        } else {
            FocusModesUiState.Active(snapshot.modes, activeMode)
        }
    }
}
