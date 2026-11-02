package com.itsmebillah.appinternetcontrol

import android.content.Context

class ScheduleRuntimeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var status: String
        get() = preferences.getString(STATUS_KEY, STATUS_NOT_CONFIGURED) ?: STATUS_NOT_CONFIGURED
        set(value) = preferences.edit().putString(STATUS_KEY, value).apply()

    companion object {
        const val STATUS_NOT_CONFIGURED = "not_configured"
        const val STATUS_NO_CURRENT_BLOCK = "no_current_block"
        const val STATUS_AWAITING_VPN_CONSENT = "awaiting_vpn_consent"
        const val STATUS_START_REQUESTED = "start_requested"
        const val STATUS_BACKGROUND_START_PENDING = "background_start_pending"
        const val STATUS_FAILED = "failed"
        private const val PREFERENCES_NAME = "schedule_runtime"
        private const val STATUS_KEY = "enforcement_status"
    }
}
