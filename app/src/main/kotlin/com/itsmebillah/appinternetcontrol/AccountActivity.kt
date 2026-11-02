package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest

class AccountActivity : Activity() {
    private val repository by lazy { SupabaseAccountRepository(this) }
    private val preferences by lazy { getSharedPreferences("cloud_backup", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var syncOptIn: CheckBox
    private lateinit var authButtons: LinearLayout
    private lateinit var resetPasswordButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        content.addView(TextView(this).apply { text = "Account and backup"; textSize = 24f })
        status = TextView(this).apply { setPadding(0, dp(12), 0, dp(8)) }
        content.addView(status)
        email = EditText(this).apply { hint = "Email"; setSingleLine(true); inputType = 33 }
        password = EditText(this).apply { hint = "Password"; setSingleLine(true); inputType = 129 }
        content.addView(email)
        content.addView(password)
        resetPasswordButton = button("Set new password") {
            val value = password.text.toString()
            if (value.length < 8) toast("Choose a password with at least 8 characters.")
            else runNetwork { repository.updatePassword(value); "Password updated." }
        }.apply { visibility = android.view.View.GONE }
        content.addView(resetPasswordButton)
        authButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        authButtons.addView(button("Create account") { withCredentials { repository.signUp(it.first, it.second) } })
        authButtons.addView(button("Sign in") { withCredentials { repository.signIn(it.first, it.second); "Signed in." } })
        authButtons.addView(button("Send password recovery email") {
            val address = email.text.toString().trim()
            if (address.isBlank()) toast("Enter your account email first.")
            else runNetwork { repository.requestPasswordRecovery(address); "If the address is registered, a recovery email was requested." }
        })
        content.addView(authButtons)
        syncOptIn = CheckBox(this).apply {
            text = "Enable optional cloud backup (manual sync only)"
            isChecked = preferences.getBoolean(OPT_IN_KEY, false)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean(OPT_IN_KEY, checked).apply() }
        }
        content.addView(syncOptIn)
        content.addView(TextView(this).apply {
            text = "Backup includes selected app settings, profiles, schedules, and focus modes. Usage history, analytics, passwords, and device permissions are never uploaded. Each sync asks before replacing either side. Core controls remain local when signed out or offline."
            setPadding(0, dp(4), 0, dp(12))
        })
        content.addView(button("Check and sync backup") { chooseSyncDirection() })
        content.addView(button("Sign out") {
            runNetwork { repository.signOut(); "Signed out. Local controls and data remain on this device." }
        })
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refreshStatus()
        handleAuthCallback(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: android.content.Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "appinternetcontrol" || uri.host != "auth-callback") return
        runNetwork {
            val type = repository.acceptAuthCallback(uri)
            if (type == "recovery") runOnUiThread {
                resetPasswordButton.visibility = android.view.View.VISIBLE
                status.text = "Recovery verified. Enter a new password and choose Set new password."
            }
            "Authentication link verified${if (type == "recovery") "; choose Set new password" else ""}."
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun withCredentials(action: (Pair<String, String>) -> String) {
        val address = email.text.toString().trim()
        val secret = password.text.toString()
        if (address.isBlank() || secret.isBlank()) {
            toast("Enter both email and password.")
            return
        }
        runNetwork { action(address to secret) }
    }

    private fun chooseSyncDirection() {
        if (!repository.isConfigured) {
            toast("Not configured: add the Supabase URL and publishable key to local supabase.properties.")
            return
        }
        if (repository.currentEmail() == null) {
            toast("Sign in before backing up or restoring.")
            return
        }
        if (!syncOptIn.isChecked) {
            toast("Enable optional cloud backup first.")
            return
        }
        val accountSyncKey = LAST_SYNC_HASH + "_" + repository.currentUserId().orEmpty()
        status.text = "Checking local and cloud backup…"
        Thread {
            runCatching {
                val local = CloudBackupSnapshot.capture(this)
                val remote = repository.readCloudBackup()
                val localHash = fingerprint(local)
                val remoteHash = fingerprint(remote)
                val previous = preferences.getString(accountSyncKey, null)
                val conflict = BackupConflictPolicy.compare(localHash, remoteHash, previous)
                Triple(local, remote, when (conflict) {
                    BackupConflict.MATCH -> "Local and cloud backup already match."
                    BackupConflict.FIRST_SYNC -> "No prior sync is recorded. Choose which copy should replace the other."
                    BackupConflict.BOTH_CHANGED -> "Both copies changed since the last sync. Choose one copy; the other will be replaced."
                    BackupConflict.LOCAL_CHANGED -> "Local data changed since last sync. Upload it or restore the cloud copy."
                    BackupConflict.CLOUD_CHANGED -> "Cloud data changed since last sync. Restore it or replace it with local data."
                })
            }.onSuccess { (local, remote, message) -> runOnUiThread {
                status.text = message
                if (fingerprint(local) == fingerprint(remote)) {
                    preferences.edit().putString(accountSyncKey, fingerprint(local)).apply()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Choose backup direction")
                    .setMessage("$message\n\nUpload local data replaces the cloud copy. Restore cloud data replaces the local copy.")
                    .setNegativeButton("Upload local") { _, _ -> runNetwork {
                        repository.writeCloudBackup(local)
                        val hash = fingerprint(local)
                        preferences.edit().putString(accountSyncKey, hash).apply()
                        "Local settings, profiles, schedules, and focus modes backed up."
                    } }
                    .setPositiveButton("Restore cloud") { _, _ -> runNetwork {
                        CloudBackupSnapshot.restore(this, remote)
                        val hash = fingerprint(remote)
                        preferences.edit().putString(accountSyncKey, hash).apply()
                        "Cloud settings, profiles, schedules, and focus modes restored locally."
                    } }
                    .setNeutralButton("Cancel", null)
                    .show()
            } }.onFailure { error -> runOnUiThread { status.text = "Backup check failed: ${safeMessage(error)}" } }
        }.start()
    }

    private fun runNetwork(action: () -> String) {
        status.text = "Working…"
        Thread {
            runCatching(action).onSuccess { message -> runOnUiThread { status.text = message; refreshStatus() } }
                .onFailure { error -> runOnUiThread { status.text = safeMessage(error); refreshStatus() } }
        }.start()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val email = repository.currentEmail()
        status.text = when {
            !repository.isConfigured -> "Not configured. Add local Supabase project URL and publishable key; no network account or backup request can run yet."
            email != null -> "Signed in as $email. Cloud backup is ${if (syncOptIn.isChecked) "opted in" else "off"}; sync is manual."
            else -> "Not signed in. Local app controls work independently of an account."
        }
    }

    private fun fingerprint(json: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(json.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun safeMessage(error: Throwable): String = error.message?.takeIf { it.isNotBlank() }
        ?: "Request failed. Check your network and Supabase configuration."

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val OPT_IN_KEY = "cloud_backup_opt_in"
        private const val LAST_SYNC_HASH = "last_sync_hash"
    }
}
