package com.itsmebillah.appinternetcontrol

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Process
import java.time.ZoneId

enum class UsageCollectionResult { CAPTURED, NO_NEW_INTERVAL, ACCESS_REQUIRED, NOT_TRACKING, UNAVAILABLE }

object UsageCollectionRunner {
    @Synchronized
    fun collect(context: Context, nowMillis: Long = System.currentTimeMillis()): UsageCollectionResult {
        if (!hasUsageAccess(context)) return UsageCollectionResult.ACCESS_REQUIRED
        val database = UsageHistoryDatabase(context)
        if (!database.isStarted()) return UsageCollectionResult.NOT_TRACKING
        val previousCapture = database.lastCapturedAt()
        if (nowMillis - previousCapture < MIN_COLLECTION_INTERVAL_MILLIS) return UsageCollectionResult.NO_NEW_INTERVAL

        val earliestAllowed = nowMillis - MAX_CATCH_UP_MILLIS
        val gap = previousCapture < earliestAllowed
        val effectiveStart = maxOf(previousCapture, earliestAllowed)
        val zone = ZoneId.systemDefault()
        val visiblePackagesByUid = visibleLauncherPackages(context)
            .groupBy({ it.second }, { it.first })
            .mapValues { (_, packages) -> packages.toSet() }
        val windows = mutableListOf<UsageWindow>()
        return try {
            UsageWindowPlanner.split(effectiveStart, nowMillis, zone).forEach { slice ->
                val bytesByUid = queryNetworkSummary(context, slice.startMillis, slice.endMillis)
                val records = bytesByUid.mapNotNull { (uid, usage) ->
                    val packages = visiblePackagesByUid[uid].orEmpty()
                    if (packages.isEmpty()) null else UsageRecord(slice.date, packages, usage.first, usage.second)
                }
                windows += UsageWindow(slice.date, slice.startMillis, slice.endMillis, records)
            }
            if (Thread.currentThread().isInterrupted) return UsageCollectionResult.UNAVAILABLE
            if (!database.isStarted()) return UsageCollectionResult.NOT_TRACKING
            database.applyCapture(windows, nowMillis, gap)
            UsageCollectionResult.CAPTURED
        } catch (_: SecurityException) {
            UsageCollectionResult.ACCESS_REQUIRED
        } catch (_: RuntimeException) {
            UsageCollectionResult.UNAVAILABLE
        } finally {
            database.close()
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun visibleLauncherPackages(context: Context): List<Pair<String, Int>> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filterNot { it.packageName == context.packageName }
            .distinctBy { it.packageName }
            .map { it.packageName to it.uid }
    }

    @Suppress("DEPRECATION")
    private fun queryNetworkSummary(context: Context, start: Long, end: Long): Map<Int, Pair<Long, Long>> {
        val manager = context.getSystemService(NetworkStatsManager::class.java)
        val totals = mutableMapOf<Int, Pair<Long, Long>>()
        listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE).forEach { networkType ->
            val stats = manager.querySummary(networkType, null, start, end)
                ?: throw IllegalStateException("Android returned no network statistics")
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    val current = totals[bucket.uid] ?: (0L to 0L)
                    totals[bucket.uid] = current.first + bucket.rxBytes to current.second + bucket.txBytes
                }
            } finally {
                stats.close()
            }
        }
        return totals
    }

    private const val MAX_CATCH_UP_MILLIS = 30L * 24 * 60 * 60 * 1000
    private const val MIN_COLLECTION_INTERVAL_MILLIS = 60_000L
}
