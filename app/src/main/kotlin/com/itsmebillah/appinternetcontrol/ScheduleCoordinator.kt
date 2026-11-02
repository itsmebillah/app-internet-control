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
            activeFocusMode = FocusModeStore(context).activeMode(),
            activeProfile = UserProfileStore(context).activeProfile(),
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
            activeFocusMode = FocusModeStore(appContext).activeMode(),
            activeProfile = UserProfileStore(appContext).activeProfile(),
            schedules = schedules,
            temporaryBlocks = temporaryBlocks,
            now = now,
            zone = ZoneId.systemDefault(),
        )
        recordEffectiveTransitions(appContext, now, effectivePackages, schedules, temporaryBlocks)
        val runtimeStore = ScheduleRuntimeStore(appContext)
        if (VpnServiceLifecyclePolicy.shouldStop(effectivePackages)) {
            if (AppBlockingVpnService.isVpnActive) {
                AppBlockingVpnService.requestStop(appContext)
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

    fun captureManualChange(context: Context, packageName: String, requestedBlocked: Boolean) {
        val now = Instant.now()
        val packages = effectiveBlockedPackages(context, now)
        val appContext = context.applicationContext
        recordEffectiveTransitions(appContext, now, packages,
            BlockScheduleStore(appContext).read(), TemporaryBlockStore(appContext).read(),
            manualRequest = packageName to requestedBlocked)
    }

    private fun recordEffectiveTransitions(
        context: Context,
        now: Instant,
        blockedPackages: Set<String>,
        schedules: List<BlockSchedule>,
        temporaryBlocks: List<TemporaryBlock>,
        manualRequest: Pair<String, Boolean>? = null,
    ) {
        val manual = AppSelectionStore(context).read().packageNames
        val focus = FocusModeStore(context).activeMode()
        val profile = UserProfileStore(context).activeProfile()
        val activeSchedules = schedules.filter { ScheduleEvaluator.isActive(it, now, ZoneId.systemDefault()) }
        val activeTemporary = TemporaryBlockEvaluator.activePackageNames(temporaryBlocks, now)
        val history = ChangeHistoryStore(context)
        val previous = history.readSnapshot()
        val trackedPackages = previous.keys + manual + focus?.packageNames.orEmpty() +
            profile?.blockedPackageNames.orEmpty() + profile?.allowedPackageNames.orEmpty() +
            activeSchedules.flatMap { it.packageNames } + activeTemporary
        val decisions = trackedPackages.associateWith { packageName ->
            val source = when {
                packageName in activeTemporary -> ChangeSource.TIMER
                activeSchedules.any { packageName in it.packageNames } -> ChangeSource.SCHEDULE
                packageName in profile?.blockedPackageNames.orEmpty() || packageName in profile?.allowedPackageNames.orEmpty() -> ChangeSource.PROFILE
                packageName in focus?.packageNames.orEmpty() -> ChangeSource.FOCUS
                else -> ChangeSource.MANUAL
            }
            EffectiveDecision(packageName in blockedPackages, source)
        }
        if (history.hasSnapshot()) {
            val transitions = ChangeHistoryReducer.transitions(previous, decisions, now) { blocked ->
                when {
                    AppBlockingVpnService.isVpnActive -> EnforcementOutcome.ACTIVE_UNVERIFIED
                    blocked -> EnforcementOutcome.PENDING
                    else -> EnforcementOutcome.NOT_ACTIVE
                }
            }.filterNot { it.packageName == manualRequest?.first }
            history.append(transitions)
        }
        manualRequest?.let { (packageName, requestedBlocked) ->
            val effectiveBlocked = decisions[packageName]?.blocked == true
            val outcome = when {
                effectiveBlocked != requestedBlocked -> EnforcementOutcome.OVERRIDDEN
                AppBlockingVpnService.isVpnActive && requestedBlocked -> EnforcementOutcome.ACTIVE_UNVERIFIED
                requestedBlocked -> EnforcementOutcome.PENDING
                else -> EnforcementOutcome.NOT_ACTIVE
            }
            history.append(listOf(ChangeEvent(
                id = "$packageName:${now.toEpochMilli()}:manual",
                timestamp = now,
                packageName = packageName,
                blocked = requestedBlocked,
                source = ChangeSource.MANUAL,
                outcome = outcome,
            )))
        }
        history.writeSnapshot(decisions)
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
