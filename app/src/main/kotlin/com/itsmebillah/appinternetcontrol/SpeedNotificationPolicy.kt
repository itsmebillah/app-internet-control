package com.itsmebillah.appinternetcontrol

object SpeedNotificationPolicy {
    fun canMonitor(
        notificationPermissionGranted: Boolean,
        appNotificationsEnabled: Boolean,
        speedChannelEnabled: Boolean,
    ): Boolean = notificationPermissionGranted && appNotificationsEnabled && speedChannelEnabled
}
