package com.itsmebillah.appinternetcontrol

import android.app.job.JobScheduler
import android.content.Context
import java.security.KeyStore

/** One-time migration for local state owned by features removed from the app. */
object RemovedFeatureDataCleanup {
    private const val CLEANUP_PREFERENCES = "removed_feature_data_cleanup"
    private const val CLEANUP_COMPLETE = "cleanup_v1_complete"
    private const val LEGACY_AUTO_BACKUP_JOB_ID = 6_804
    private const val LEGACY_SESSION_KEY_ALIAS = "app_internet_control_supabase_session"

    fun run(context: Context) {
        val appContext = context.applicationContext
        val marker = appContext.getSharedPreferences(CLEANUP_PREFERENCES, Context.MODE_PRIVATE)
        if (marker.getBoolean(CLEANUP_COMPLETE, false)) return

        val localDataCleared = runCatching {
            listOf("user_profiles", "account_session", "cloud_backup").forEach { preferencesName ->
                check(appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit())
            }
            appContext.getSystemService(JobScheduler::class.java).cancel(LEGACY_AUTO_BACKUP_JOB_ID)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { keyStore ->
                if (keyStore.containsAlias(LEGACY_SESSION_KEY_ALIAS)) {
                    keyStore.deleteEntry(LEGACY_SESSION_KEY_ALIAS)
                }
                check(!keyStore.containsAlias(LEGACY_SESSION_KEY_ALIAS))
            }
        }.isSuccess
        if (!localDataCleared) return
        marker.edit().putBoolean(CLEANUP_COMPLETE, true).commit()
    }
}
