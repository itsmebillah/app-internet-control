package com.itsmebillah.appinternetcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AppIC.Schedule", "boundary_receiver action=${intent.action ?: "none"} receivedAt=${java.time.Instant.now()}")
        ScheduleCoordinator.reconcile(context)
    }
}
