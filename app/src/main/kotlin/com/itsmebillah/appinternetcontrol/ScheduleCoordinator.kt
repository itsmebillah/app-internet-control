package com.itsmebillah.appinternetcontrol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneId

object ScheduleCoordinator {
    fun effectiveBlockedPackages(context: Context, now: Instant = Instant.now()): Set<String> =
        EffectiveBlockResolver.resolve(
            manualBlockedPackages = AppSelectionStore(context).read().packageNames,
            activeFocusMode = FocusModeStore(context).activeMode(),
            schedules = BlockScheduleStore(context).read(),
            temporaryBlocks = TemporaryBlockStore(context).expire(now),
            wifiAccessRules = WifiAccessRuleStore(context).read(),
            currentWifiNetwork = WifiAccessRuntimeState.currentDefaultWifi,
            now = now,
            zone = ZoneId.systemDefault(),
        )

    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val now = Instant.now()
        val rules = resolveCurrentRules(appContext, now)
        val effectivePackages = rules.effectivePackages
        val hasEnabledWifiRules = WifiAccessRuleStore(appContext).read().any { it.enabled }
        Log.i(TAG, "reconcile now=${now} activeSchedules=${rules.activeScheduleIds.joinToString(",") { it.take(8) }} effectivePackageCount=${effectivePackages.size}")
        val runtimeStore = ScheduleRuntimeStore(appContext)
        if (VpnServiceLifecyclePolicy.shouldStop(effectivePackages) && !hasEnabledWifiRules) {
            if (AppBlockingVpnService.isVpnActive || AppBlockingVpnService.isWifiMonitorActive) {
                Log.i(TAG, "vpn_update=stop reason=no_effective_blocks")
                AppBlockingVpnService.requestStop(appContext)
            } else {
                Log.i(TAG, "vpn_update=none reason=no_effective_blocks vpnAlreadyInactive=true")
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
                if (AppBlockingVpnService.activePackageNames != effectivePackages ||
                    AppBlockingVpnService.isWifiMonitorActive != hasEnabledWifiRules
                ) {
                    Log.i(TAG, "vpn_update=replace_selected_set packageCount=${effectivePackages.size}")
                    appContext.startService(serviceIntent)
                } else {
                    Log.i(TAG, "vpn_update=none reason=selected_set_unchanged")
                }
                runtimeStore.status = ScheduleRuntimeStore.STATUS_START_REQUESTED
            } else if (AppBlockingVpnService.isWifiMonitorActive && effectivePackages.isEmpty()) {
                Log.i(TAG, "vpn_update=none reason=wifi_monitor_active selectedPackageCount=0")
                runtimeStore.status = ScheduleRuntimeStore.STATUS_START_REQUESTED
            } else {
                Log.i(TAG, "vpn_update=start packageCount=${effectivePackages.size}")
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

    /**
     * Re-evaluates persisted rules at VPN startup/update time. Alarm delivery can be delayed,
     * and service intents may contain an old package set, so the schedule snapshot is authoritative.
     */
    fun packagesForVpnServiceStart(context: Context, now: Instant = Instant.now()): Set<String> =
        resolveCurrentRules(context.applicationContext, now).effectivePackages

    private fun resolveCurrentRules(context: Context, now: Instant): CurrentRules {
        val schedules = BlockScheduleStore(context).read()
        val temporaryBlocks = TemporaryBlockStore(context).expire(now)
        val activeScheduleIds = schedules.asSequence()
            .filter { ScheduleEvaluator.isActive(it, now, ZoneId.systemDefault()) }
            .map { it.id }
            .toList()
        activeScheduleIds.forEach { id ->
            val schedule = schedules.first { it.id == id }
            val end = ScheduleEvaluator.activeEnd(schedule, now, ZoneId.systemDefault())
            Log.i(TAG, "schedule_active id=${id.take(8)} computedEnd=${end ?: "unknown"} zone=${ZoneId.systemDefault().id}")
        }
        scheduleNextBoundary(context, schedules, temporaryBlocks, now)
        val effectivePackages = EffectiveBlockResolver.resolve(
            manualBlockedPackages = AppSelectionStore(context).read().packageNames,
            activeFocusMode = FocusModeStore(context).activeMode(),
            schedules = schedules,
            temporaryBlocks = temporaryBlocks,
            wifiAccessRules = WifiAccessRuleStore(context).read(),
            currentWifiNetwork = WifiAccessRuntimeState.currentDefaultWifi,
            now = now,
            zone = ZoneId.systemDefault(),
        )
        recordEffectiveTransitions(context, now, effectivePackages, schedules, temporaryBlocks)
        return CurrentRules(effectivePackages, activeScheduleIds)
    }

    private data class CurrentRules(val effectivePackages: Set<String>, val activeScheduleIds: List<String>)

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
        val activeSchedules = schedules.filter { ScheduleEvaluator.isActive(it, now, ZoneId.systemDefault()) }
        val activeTemporary = TemporaryBlockEvaluator.activePackageNames(temporaryBlocks, now)
        val history = ChangeHistoryStore(context)
        val previous = history.readSnapshot()
        val trackedPackages = previous.keys + manual + focus?.packageNames.orEmpty() +
            activeSchedules.flatMap { it.packageNames } + activeTemporary
        val decisions = trackedPackages.associateWith { packageName ->
            val source = when {
                packageName in activeTemporary -> ChangeSource.TIMER
                activeSchedules.any { packageName in it.packageNames } -> ChangeSource.SCHEDULE
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
        val zone = ZoneId.systemDefault()
        val nextScheduleBoundary = ScheduleEvaluator.nextBoundary(schedules, now, zone)
        val next = listOfNotNull(
            nextScheduleBoundary,
            TemporaryBlockEvaluator.nextExpiry(temporaryBlocks, now),
        ).minOrNull()
        val boundaryScheduleIds = if (next != null && next == nextScheduleBoundary) {
            schedules.asSequence().filter { schedule ->
                schedule.enabled && ScheduleEvaluator.nextBoundary(listOf(schedule), now, zone) == next
            }.map { it.id.take(8) }.joinToString(",")
        } else ""
        Log.i(TAG, "alarm_plan scheduleIds=$boundaryScheduleIds computedBoundary=$next zone=${zone.id} exactAllowed=${Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()}")
        if (next == null) {
            alarmManager.cancel(pendingIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pendingIntent)
                    return
                } catch (_: SecurityException) {
                    // Access can be revoked between the check and the call; keep a delayed fallback alarm.
                }
            }
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pendingIntent)
        }
    }

    private fun notifyStatusChanged(context: Context) {
        context.sendBroadcast(Intent(AppBlockingVpnService.ACTION_STATUS_CHANGED).setPackage(context.packageName))
    }

    private const val ACTION_SCHEDULE_BOUNDARY = "com.itsmebillah.appinternetcontrol.SCHEDULE_BOUNDARY"
    private const val SCHEDULE_ALARM_REQUEST_CODE = 73
    private const val TAG = "AppIC.Schedule"
}
