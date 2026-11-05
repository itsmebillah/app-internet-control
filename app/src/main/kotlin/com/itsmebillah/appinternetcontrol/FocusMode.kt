package com.itsmebillah.appinternetcontrol

data class FocusMode(
    val id: String,
    val name: String,
    val packageNames: Set<String>,
)

data class FocusModeSnapshot(
    val modes: List<FocusMode>,
    val activeMode: FocusMode?,
)
