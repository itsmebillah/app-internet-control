package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class UserProfileActivity : Activity() {
    private val profileStore by lazy { UserProfileStore(this) }
    private lateinit var statusView: TextView
    private lateinit var profilesList: LinearLayout
    private lateinit var profileName: EditText
    private lateinit var blockedAppsButton: Button
    private lateinit var allowedAppsButton: Button
    private var installedApps: List<InstalledApp> = emptyList()
    private var blockedPackages = mutableSetOf<String>()
    private var allowedPackages = mutableSetOf<String>()
    private var editingProfileId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadVisibleApps()
        setContentView(createScreen())
        renderProfiles()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        ScheduleCoordinator.reconcile(this)
        renderProfiles()
        renderStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return
        if (resultCode == RESULT_OK) {
            ScheduleCoordinator.reconcile(this)
            renderStatus()
        } else {
            Toast.makeText(this, R.string.profile_consent_declined, Toast.LENGTH_LONG).show()
            renderStatus()
        }
    }

    @Deprecated("Deprecated in Android API; retained for minSdk-compatible permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notification_permission_optional, Toast.LENGTH_LONG).show()
        }
        continueConsentFlow()
    }

    private fun createScreen(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.user_profiles)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.profile_precedence_note)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        statusView = TextView(this).apply { textSize = 14f }
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = getString(R.string.deactivate_profile)
            setOnClickListener {
                runCatching { profileStore.activate(null) }
                    .onFailure { Toast.makeText(this@UserProfileActivity, "Could not deactivate profile: ${it.message ?: "save failed"}", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                ScheduleCoordinator.reconcile(this@UserProfileActivity)
                renderProfiles()
                renderStatus()
            }
        })
        profilesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(profilesList)
        content.addView(TextView(this).apply {
            text = getString(R.string.create_profile)
            textSize = 20f
            setPadding(0, dp(16), 0, dp(8))
        })
        profileName = EditText(this).apply { hint = getString(R.string.profile_name_hint) }
        content.addView(profileName)

        val suggestions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.string.profile_suggestion_work, R.string.profile_suggestion_study, R.string.profile_suggestion_sleep).forEach { id ->
            suggestions.addView(Button(this@UserProfileActivity).apply {
                text = getString(id)
                setOnClickListener { profileName.setText(getString(id)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(suggestions)
        blockedAppsButton = Button(this).apply {
            text = getString(R.string.profile_choose_blocked)
            setOnClickListener { chooseApps(blocked = true) }
        }
        allowedAppsButton = Button(this).apply {
            text = getString(R.string.profile_choose_allowed)
            setOnClickListener { chooseApps(blocked = false) }
        }
        content.addView(blockedAppsButton)
        content.addView(allowedAppsButton)
        content.addView(Button(this).apply {
            text = getString(R.string.save_profile)
            setOnClickListener { saveProfile() }
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun loadVisibleApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        installedApps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filterNot { it.packageName == packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    private fun chooseApps(blocked: Boolean) {
        if (installedApps.isEmpty()) return
        val target = if (blocked) blockedPackages else allowedPackages
        val other = if (blocked) allowedPackages else blockedPackages
        val labels = installedApps.map { "${it.displayName} (${it.packageName})" }.toTypedArray()
        val checked = BooleanArray(installedApps.size) { installedApps[it].packageName in target }
        AlertDialog.Builder(this)
            .setTitle(if (blocked) R.string.profile_choose_blocked else R.string.profile_choose_allowed)
            .setMultiChoiceItems(labels, checked) { _, index, selected ->
                val packageName = installedApps[index].packageName
                if (selected) {
                    target.add(packageName)
                    other.remove(packageName)
                } else {
                    target.remove(packageName)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> updateSelectionButtons() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSelectionButtons() {
        blockedAppsButton.text = selectionLabel(R.string.profile_choose_blocked, blockedPackages)
        allowedAppsButton.text = selectionLabel(R.string.profile_choose_allowed, allowedPackages)
    }

    private fun selectionLabel(defaultLabel: Int, packages: Set<String>): String {
        val names = installedApps.filter { it.packageName in packages }.map { it.displayName }
        return if (names.isEmpty()) getString(defaultLabel) else names.joinToString().take(70)
    }

    private fun saveProfile() {
        val name = profileName.text.toString().trim()
        if (name.isBlank() || (blockedPackages.isEmpty() && allowedPackages.isEmpty())) {
            Toast.makeText(this, R.string.profile_needs_name_and_apps, Toast.LENGTH_LONG).show()
            return
        }
        val profile = runCatching {
            UserProfile(
                id = editingProfileId ?: UUID.randomUUID().toString(),
                name = name,
                blockedPackageNames = blockedPackages.toSet(),
                allowedPackageNames = allowedPackages.toSet(),
            )
        }.getOrElse {
            Toast.makeText(this, R.string.profile_overlapping_apps, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { profileStore.save(profile) }
            .onFailure { Toast.makeText(this, "Profile could not be saved: ${it.message ?: "storage error"}", Toast.LENGTH_LONG).show(); return }
        editingProfileId = null
        profileName.text.clear()
        blockedPackages.clear()
        allowedPackages.clear()
        updateSelectionButtons()
        renderProfiles()
        if (profileStore.activeProfile()?.id == profile.id) {
            ScheduleCoordinator.reconcile(this)
            requestConsentForActiveBlocksIfNeeded(profile)
        }
        renderStatus()
    }

    private fun editProfile(profile: UserProfile) {
        editingProfileId = profile.id
        profileName.setText(profile.name)
        blockedPackages = profile.blockedPackageNames.toMutableSet()
        allowedPackages = profile.allowedPackageNames.toMutableSet()
        updateSelectionButtons()
    }

    private fun activateProfile(profile: UserProfile) {
        runCatching { profileStore.activate(profile.id) }
            .onFailure { Toast.makeText(this, "Profile could not be activated: ${it.message ?: "storage error"}", Toast.LENGTH_LONG).show(); return }
        ScheduleCoordinator.reconcile(this)
        renderProfiles()
        renderStatus()
        requestConsentForActiveBlocksIfNeeded(profile)
    }

    private fun requestConsentForActiveBlocksIfNeeded(profile: UserProfile) {
        if (profile.blockedPackageNames.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        } else {
            continueConsentFlow()
        }
    }

    private fun continueConsentFlow() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) startActivityForResult(consentIntent, VPN_CONSENT_REQUEST)
        else {
            ScheduleCoordinator.reconcile(this)
            renderStatus()
        }
    }

    private fun deleteProfile(profile: UserProfile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_profile))
            .setMessage("Delete “${profile.name}”? Other manual, scheduled, focus, and temporary rules will remain unchanged.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_profile) { _, _ ->
                runCatching { profileStore.delete(profile.id) }
                    .onFailure { Toast.makeText(this, "Profile could not be deleted: ${it.message ?: "storage error"}", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                if (editingProfileId == profile.id) {
                    editingProfileId = null
                    profileName.text.clear()
                    blockedPackages.clear()
                    allowedPackages.clear()
                    updateSelectionButtons()
                }
                ScheduleCoordinator.reconcile(this)
                renderProfiles()
                renderStatus()
            }
            .show()
    }

    private fun renderProfiles() {
        if (!::profilesList.isInitialized) return
        profilesList.removeAllViews()
        val profiles = profileStore.read()
        if (profiles.isEmpty()) {
            profilesList.addView(TextView(this).apply { text = getString(R.string.no_profiles) })
            return
        }
        val activeId = profileStore.activeProfile()?.id
        profiles.forEach { profile ->
            val blockedNames = installedApps.filter { it.packageName in profile.blockedPackageNames }.joinToString { it.displayName }
            val allowedNames = installedApps.filter { it.packageName in profile.allowedPackageNames }.joinToString { it.displayName }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(TextView(this).apply {
                text = "${profile.name}${if (profile.id == activeId) " · ${getString(R.string.profile_active)}" else ""}\n${getString(R.string.profile_blocked_summary, blockedNames)}\n${getString(R.string.profile_allowed_summary, allowedNames)}"
                textSize = 15f
            })
            val buttons = LinearLayout(this@UserProfileActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this@UserProfileActivity).apply {
                text = getString(R.string.apply_profile)
                isEnabled = profile.id != activeId
                setOnClickListener { activateProfile(profile) }
            })
            buttons.addView(Button(this@UserProfileActivity).apply {
                text = getString(R.string.edit_profile)
                setOnClickListener { editProfile(profile) }
            })
            buttons.addView(Button(this@UserProfileActivity).apply {
                text = getString(R.string.delete_profile)
                setOnClickListener { deleteProfile(profile) }
            })
            card.addView(buttons)
            profilesList.addView(card)
        }
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized) return
        val active = profileStore.activeProfile()
        statusView.text = when {
            AppBlockingVpnService.isVpnActive -> getString(R.string.status_active_unverified)
            ScheduleCoordinator.effectiveBlockedPackages(this).isNotEmpty() -> getString(R.string.status_effective_not_active)
            active != null -> getString(R.string.status_profile_active, active.name)
            else -> getString(R.string.status_no_selection)
        }
    }

    companion object {
        private const val VPN_CONSENT_REQUEST = 5001
        private const val NOTIFICATION_PERMISSION_REQUEST = 5002
    }
}
