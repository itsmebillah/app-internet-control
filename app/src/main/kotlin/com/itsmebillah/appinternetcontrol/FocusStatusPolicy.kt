package com.itsmebillah.appinternetcontrol

enum class FocusStatus {
    ACTIVE_UNVERIFIED,
    RULE_NOT_ACTIVE,
    MODE_ACTIVE,
    NO_SELECTION,
}

object FocusStatusPolicy {
    fun resolve(vpnActive: Boolean, hasEffectiveBlocks: Boolean, activeModeName: String?): FocusStatus = when {
        vpnActive -> FocusStatus.ACTIVE_UNVERIFIED
        hasEffectiveBlocks -> FocusStatus.RULE_NOT_ACTIVE
        activeModeName != null -> FocusStatus.MODE_ACTIVE
        else -> FocusStatus.NO_SELECTION
    }
}
