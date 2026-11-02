package com.itsmebillah.appinternetcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleCoordinator.reconcile(context)
    }
}
