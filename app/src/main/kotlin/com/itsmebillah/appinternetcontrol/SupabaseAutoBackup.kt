package com.itsmebillah.appinternetcontrol

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

enum class SupabaseBackupFrequency(val intervalMillis: Long) {
    DAILY(24L * 60 * 60 * 1000),
    WEEKLY(7L * 24 * 60 * 60 * 1000),
}

class SupabaseAutoBackupStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_backup", Context.MODE_PRIVATE)

    fun isEnabled(userId: String) = prefs.getBoolean(key(KEY_ENABLED, userId), false)
    fun setEnabled(userId: String, enabled: Boolean) { prefs.edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply() }

    fun frequency(userId: String): SupabaseBackupFrequency = runCatching {
        SupabaseBackupFrequency.valueOf(prefs.getString(key(KEY_FREQUENCY, userId), null) ?: "DAILY")
    }.getOrDefault(SupabaseBackupFrequency.DAILY)

    fun setFrequency(userId: String, frequency: SupabaseBackupFrequency) {
        prefs.edit().putString(key(KEY_FREQUENCY, userId), frequency.name).apply()
    }

    fun lastSuccessMillis(userId: String): Long? = prefs.getLong(key(KEY_LAST_SUCCESS, userId), 0L).takeIf { it > 0L }
    fun setLastSuccessMillis(userId: String, time: Long) { prefs.edit().putLong(key(KEY_LAST_SUCCESS, userId), time).apply() }
    fun lastError(userId: String): String? = prefs.getString(key(KEY_LAST_ERROR, userId), null)
    fun setLastError(userId: String, error: String?) { prefs.edit().putString(key(KEY_LAST_ERROR, userId), error).apply() }
    fun lastSyncHash(userId: String): String? = prefs.getString(key(KEY_LAST_SYNC_HASH, userId), null)
    fun setLastSyncHash(userId: String, hash: String) { prefs.edit().putString(key(KEY_LAST_SYNC_HASH, userId), hash).apply() }

    private fun key(prefix: String, userId: String) = "$prefix$userId"

    companion object {
        private const val KEY_ENABLED = "supabase_auto_enabled_"
        private const val KEY_FREQUENCY = "supabase_auto_frequency_"
        private const val KEY_LAST_SUCCESS = "supabase_auto_last_success_"
        private const val KEY_LAST_ERROR = "supabase_auto_last_error_"
        private const val KEY_LAST_SYNC_HASH = "last_sync_hash_"
    }
}

enum class AutoBackupDecision { UPLOAD_LOCAL, ALREADY_CURRENT, CLOUD_CHANGED, CONFLICT }

object SupabaseAutoBackupPolicy {
    fun decision(localHash: String, cloudHash: String?, baselineHash: String?): AutoBackupDecision {
        if (cloudHash == localHash) return AutoBackupDecision.ALREADY_CURRENT
        if (cloudHash == null) {
            return if (baselineHash == null) AutoBackupDecision.UPLOAD_LOCAL else AutoBackupDecision.CONFLICT
        }
        if (baselineHash == null) return AutoBackupDecision.CONFLICT
        return when {
            cloudHash == baselineHash -> AutoBackupDecision.UPLOAD_LOCAL
            localHash == baselineHash -> AutoBackupDecision.CLOUD_CHANGED
            else -> AutoBackupDecision.CONFLICT
        }
    }

    fun mayRun(signedIn: Boolean, enabled: Boolean, configured: Boolean) = signedIn && enabled && configured
}

object CloudBackupFingerprint {
    fun of(json: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(json).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${canonical(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index -> canonical(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        null, JSONObject.NULL -> "null"
        else -> JSONObject.quote(value.toString())
    }
}

object SupabaseBackupAttemptPolicy {
    fun <T> run(uploadAndVerify: () -> T, markSuccess: (T) -> Unit): T {
        val result = uploadAndVerify()
        markSuccess(result)
        return result
    }
}

object SupabaseAutoBackupManager {
    fun run(context: Context) {
        val repository = SupabaseAccountRepository(context)
        val userId = repository.currentUserId() ?: throw IOException("Sign in to Supabase before automatic backup can run.")
        val store = SupabaseAutoBackupStore(context)
        if (!SupabaseAutoBackupPolicy.mayRun(true, store.isEnabled(userId), repository.isConfigured)) {
            throw IOException("Sign in and enable Supabase automatic backup before uploading data.")
        }

        try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = connectivity.activeNetwork
            if (activeNetwork == null || connectivity.getNetworkCapabilities(activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                throw IOException("No network connection. Supabase backup will retry when connected.")
            }
            val local = CloudBackupSnapshot.capture(context)
            CloudBackupSnapshot.validate(local)
            val localHash = CloudBackupFingerprint.of(local)
            val remote = repository.readCloudBackup(expectedUserId = userId)
            val remoteSettings = remote.optJSONObject("settings")
            val cloudHash = if (remoteSettings?.has("backupSchemaVersion") == true) {
                CloudBackupSnapshot.validate(remote)
                CloudBackupFingerprint.of(remote)
            } else null
            val decision = SupabaseAutoBackupPolicy.decision(localHash, cloudHash, store.lastSyncHash(userId))
            when (decision) {
                AutoBackupDecision.ALREADY_CURRENT -> store.setLastSyncHash(userId, localHash)
                AutoBackupDecision.UPLOAD_LOCAL -> {
                    check(store.isEnabled(userId)) { "Supabase automatic backup was disabled before upload; no data was sent." }
                    SupabaseBackupAttemptPolicy.run(
                        uploadAndVerify = {
                            repository.writeCloudBackup(local, expectedUserId = userId)
                            val persisted = repository.readCloudBackup(expectedUserId = userId)
                            CloudBackupSnapshot.validate(persisted)
                            check(CloudBackupFingerprint.of(persisted) == localHash) {
                                "Supabase backup verification did not match the uploaded settings. Local data was kept."
                            }
                            localHash
                        },
                        markSuccess = { verifiedHash ->
                            store.setLastSyncHash(userId, verifiedHash)
                            store.setLastSuccessMillis(userId, System.currentTimeMillis())
                            store.setLastError(userId, null)
                        },
                    )
                }
                AutoBackupDecision.CLOUD_CHANGED -> throw IOException(
                    "The Supabase backup changed on another device. Local data was not overwritten; review and restore it from Account and backup.",
                )
                AutoBackupDecision.CONFLICT -> throw IOException(
                    "Both local and Supabase backup data changed, or no sync baseline exists. No data was overwritten; review the backup direction in Account and backup.",
                )
            }
            if (decision == AutoBackupDecision.ALREADY_CURRENT) store.setLastError(userId, null)
        } catch (failure: Exception) {
            store.setLastError(userId, failure.message?.takeIf(String::isNotBlank) ?: "Supabase automatic backup failed. Local data was kept.")
            throw failure
        }
    }
}

class SupabaseAutoBackupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val retry = try {
                SupabaseAutoBackupManager.run(applicationContext)
                false
            } catch (_: Exception) {
                true
            }
            jobFinished(params, retry)
        }, "supabase-auto-backup").apply { isDaemon = true; start() }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 6_804

        fun schedule(context: Context): Boolean {
            val repository = SupabaseAccountRepository(context)
            val userId = repository.currentUserId()
            val store = SupabaseAutoBackupStore(context)
            if (!repository.isConfigured || userId == null || !store.isEnabled(userId)) {
                cancel(context)
                return true
            }
            val interval = store.frequency(userId).intervalMillis
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SupabaseAutoBackupJobService::class.java))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval, interval / 10)
                .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            return context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
        }

        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }
}
