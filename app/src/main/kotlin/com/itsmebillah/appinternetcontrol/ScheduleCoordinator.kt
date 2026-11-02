package com.itsmebillah.appinternetcontrol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import java.time.Instant
import java.time.ZoneId

object ScheduleCoordinator {
    fun effectiveBlockedPackages(context: Context, now: Instant = Instant.now()): Set<String> =
        EffectiveBlockResolver.resolve(
            manualBlockedPackages = AppSelectionStore(context).read().packageNames,
            schedules = BlockScheduleStore(context).read(),
            temporaryBlocks = TemporaryBlockStore(context).expire(now),
            now = now,
            zone = ZoneId.systemDefault(),
        )

    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val now = Instant.now()
        val schedules = BlockScheduleStore(appContext).read()
        val temporaryBlocks = TemporaryBlockStore(appContext).expire(now)
        scheduleNextBoundary(appContext, schedules, temporaryBlocks, now)

        val effectivePackages = EffectiveBlockResolver.resolve(
            manualBlockedPackages = AppSelectionStore(appContext).read().packageNames,
            schedules = schedules,
            temporaryBlocks = temporaryBlocks,
            now = now,
            zone = ZoneId.systemDefault(),
        )
        val runtimeStore = ScheduleRuntimeStore(appContext)
        if (effectivePackages.isEmpty()) {
            if (AppBlockingVpnService.isVpnActive) {
                appContext.stopService(Intent(appContext, AppBlockingVpnService::class.java))
            }
            runtimeStore.status = ScheduleRuntimeStore.STATUS_NO_CURRENT_BLOCK
            notifyStatusChanged(appContext)
            return
        }

        if (VpnService.prepare(appContext) != null) {
            runtimeStore.status = ScheduleRuntimeStore.STATUS_AWAITING_VPN_CONSENT
            notifyStatusChanged(appContext)
            return
        }

        val serviceIntent = Intent(appContext, AppBlockingVpnService::class.java)
            .putStringArrayListExtra(
                AppBlockingVpnService.EXTRA_SELECTED_PACKAGES,
                ArrayList(effectivePackages.sorted()),
            )
        try {
            if (AppBlockingVpnService.isVpnActive) {
                if (AppBlockingVpnService.activePackageNames != effectivePackages) {
                    appContext.startService(serviceIntent)
                }
                runtimeStore.status = ScheduleRuntimeStore.STATUS_START_REQUESTED
            } else {
                startForegroundService(appContext, serviceIntent)
                runtimeStore.status = ScheduleRuntimeStore.STATUS_START_REQUESTED
            }
        } catch (_: SecurityException) {
            runtimeStore.status = ScheduleRuntimeStore.STATUS_FAILED
        } catch (_: RuntimeException) {
            runtimeStore.status = ScheduleRuntimeStore.STATUS_BACKGROUND_START_PENDING
        }
        notifyStatusChanged(appContext)
    }

    private fun startForegroundService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun scheduleNextBoundary(
        context: Context,
        schedules: List<BlockSchedule>,
        temporaryBlocks: List<TemporaryBlock>,
        now: Instant,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SCHEDULE_ALARM_REQUEST_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION_SCHEDULE_BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = listOfNotNull(
            ScheduleEvaluator.nextBoundary(schedules, now, ZoneId.systemDefault()),
            TemporaryBlockEvaluator.nextExpiry(temporaryBlocks, now),
        ).minOrNull()
        if (next == null) {
            alarmManager.cancel(pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pendingIntent)
        }
    }

    private fun notifyStatusChanged(context: Context) {
        context.sendBroadcast(Intent(AppBlockingVpnService.ACTION_STATUS_CHANGED).setPackage(context.packageName))
    }

    private const val ACTION_SCHEDULE_BOUNDARY = "com.itsmebillah.appinternetcontrol.SCHEDULE_BOUNDARY"
    private const val SCHEDULE_ALARM_REQUEST_CODE = 73
}
