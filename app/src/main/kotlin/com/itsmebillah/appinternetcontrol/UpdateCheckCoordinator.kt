package com.itsmebillah.appinternetcontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class UpdatePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun shouldCheck(nowMillis: Long): Boolean = UpdateReminderPolicy.shouldCheck(
        nowMillis = nowMillis,
        lastAttemptMillis = preferences.getLong(KEY_LAST_ATTEMPT, 0),
        intervalMillis = CHECK_INTERVAL_MILLIS,
    )

    fun recordCheckAttempt(nowMillis: Long) {
        preferences.edit().putLong(KEY_LAST_ATTEMPT, nowMillis).apply()
    }

    fun shouldPrompt(offer: UpdateOffer, installedVersionCode: Long, nowMillis: Long, force: Boolean): Boolean {
        if (force) return true
        return UpdateReminderPolicy.shouldPrompt(
            installedVersionCode = installedVersionCode,
            latestVersionCode = offer.versionCode,
            minimumSupportedVersionCode = offer.minimumSupportedVersionCode,
            deferredVersionCode = preferences.getLong(KEY_DEFERRED_VERSION, 0),
            deferredAtMillis = preferences.getLong(KEY_DEFERRED_AT, 0),
            nowMillis = nowMillis,
            laterIntervalMillis = LATER_INTERVAL_MILLIS,
        )
    }

    fun rememberLater(versionCode: Long, nowMillis: Long) {
        preferences.edit()
            .putLong(KEY_DEFERRED_VERSION, versionCode)
            .putLong(KEY_DEFERRED_AT, nowMillis)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "app_update_state"
        private const val KEY_LAST_ATTEMPT = "last_check_attempt"
        private const val KEY_DEFERRED_VERSION = "deferred_version_code"
        private const val KEY_DEFERRED_AT = "deferred_at"
        const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
        const val LATER_INTERVAL_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}

object UpdateCheckCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    internal var providerFactory: (Context) -> AppUpdateProvider = { _ ->
        ManifestAppUpdateProvider(BuildConfig.UPDATE_MANIFEST_URL)
    }

    fun check(context: Context, force: Boolean, callback: (UpdateCheckResult) -> Unit) {
        val appContext = context.applicationContext
        val provider = providerFactory(appContext)
        if (!provider.isConfigured) {
            callback(UpdateCheckResult.NotConfigured)
            return
        }
        val preferences = UpdatePreferences(appContext)
        val now = System.currentTimeMillis()
        if (!force && !preferences.shouldCheck(now)) {
            callback(UpdateCheckResult.Deferred)
            return
        }
        preferences.recordCheckAttempt(now)
        val versionCode = currentVersionCode(appContext)
        executor.execute {
            var result = provider.check(versionCode)
            if (result is UpdateCheckResult.Available &&
                !preferences.shouldPrompt(result.offer, versionCode, System.currentTimeMillis(), force)
            ) {
                result = UpdateCheckResult.Deferred
            }
            mainHandler.post { callback(result) }
        }
    }

    fun rememberLater(context: Context, offer: UpdateOffer) {
        UpdatePreferences(context.applicationContext).rememberLater(offer.versionCode, System.currentTimeMillis())
    }

    fun launchUpdate(context: Context, offer: UpdateOffer) {
        providerFactory(context.applicationContext).launchUpdate(context, offer)
    }

    fun currentVersionName(context: Context): String = context.packageManager
        .getPackageInfo(context.packageName, 0)
        .versionName
        .orEmpty()

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}
