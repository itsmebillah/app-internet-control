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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class FocusModeActivity : Activity() {
    private val focusStore by lazy { FocusModeStore(this) }
    private lateinit var statusView: TextView
    private lateinit var modesList: LinearLayout
    private lateinit var modeName: EditText
    private lateinit var appSelectionButton: Button
    private var installedApps: List<InstalledApp> = emptyList()
    private var selectedPackages = mutableSetOf<String>()
    private var editingModeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadVisibleApps()
        setContentView(createScreen())
        renderModes()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        ScheduleCoordinator.reconcile(this)
        renderModes()
        renderStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return
        if (resultCode == RESULT_OK) {
            ScheduleCoordinator.reconcile(this)
            renderStatus()
        } else {
            Toast.makeText(this, R.string.focus_consent_declined, Toast.LENGTH_LONG).show()
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
            text = getString(R.string.focus_modes)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusView = TextView(this).apply { textSize = 14f }
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = getString(R.string.deactivate_focus_mode)
            setOnClickListener {
                focusStore.activate(null)
                ScheduleCoordinator.reconcile(this@FocusModeActivity)
                renderModes()
                renderStatus()
            }
        })
        modesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(modesList)
        content.addView(TextView(this).apply {
            text = getString(R.string.create_focus_mode)
            textSize = 20f
            setPadding(0, dp(16), 0, dp(8))
        })
        modeName = EditText(this).apply { hint = getString(R.string.focus_name_hint) }
        content.addView(modeName)

        val suggestions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            R.string.focus_suggestion_study,
            R.string.focus_suggestion_work,
            R.string.focus_suggestion_sleep,
        ).forEach { label ->
            suggestions.addView(Button(this@FocusModeActivity).apply {
                text = getString(label)
                setOnClickListener { modeName.setText(getString(label)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(suggestions)
        appSelectionButton = Button(this).apply {
            text = getString(R.string.choose_apps)
            setOnClickListener { chooseApps() }
        }
        content.addView(appSelectionButton)
        content.addView(Button(this).apply {
            text = getString(R.string.save_focus_mode)
            setOnClickListener { saveMode() }
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

    private fun chooseApps() {
        if (installedApps.isEmpty()) return
        val labels = installedApps.map { "${it.displayName} (${it.packageName})" }.toTypedArray()
        val checked = BooleanArray(installedApps.size) { installedApps[it].packageName in selectedPackages }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_apps)
            .setMultiChoiceItems(labels, checked) { _, index, selected ->
                val packageName = installedApps[index].packageName
                if (selected) selectedPackages.add(packageName) else selectedPackages.remove(packageName)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> updateAppButton() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAppButton() {
        if (!::appSelectionButton.isInitialized) return
        val names = installedApps.filter { it.packageName in selectedPackages }.map { it.displayName }
        appSelectionButton.text = if (names.isEmpty()) getString(R.string.choose_apps) else names.joinToString().take(70)
    }

    private fun saveMode() {
        val name = modeName.text.toString().trim()
        if (name.isBlank() || selectedPackages.isEmpty()) {
            Toast.makeText(this, R.string.focus_mode_needs_name_and_apps, Toast.LENGTH_LONG).show()
            return
        }
        val mode = FocusMode(
            id = editingModeId ?: UUID.randomUUID().toString(),
            name = name,
            packageNames = selectedPackages.toSet(),
        )
        focusStore.save(mode)
        editingModeId = null
        modeName.text.clear()
        renderModes()
        if (focusStore.activeMode()?.id == mode.id) ScheduleCoordinator.reconcile(this)
        renderStatus()
    }

    private fun editMode(mode: FocusMode) {
        editingModeId = mode.id
        modeName.setText(mode.name)
        selectedPackages = mode.packageNames.toMutableSet()
        updateAppButton()
    }

    private fun activateMode(mode: FocusMode) {
        focusStore.activate(mode.id)
        renderModes()
        ScheduleCoordinator.reconcile(this)
        renderStatus()
        requestFocusConsentIfNeeded()
    }

    private fun deleteMode(mode: FocusMode) {
        focusStore.delete(mode.id)
        if (editingModeId == mode.id) editingModeId = null
        ScheduleCoordinator.reconcile(this)
        renderModes()
        renderStatus()
    }

    private fun renderModes() {
        if (!::modesList.isInitialized) return
        modesList.removeAllViews()
        val modes = focusStore.read()
        if (modes.isEmpty()) {
            modesList.addView(TextView(this).apply { text = getString(R.string.no_focus_modes) })
            return
        }
        val activeId = focusStore.activeMode()?.id
        modes.forEach { mode ->
            val names = installedApps.filter { it.packageName in mode.packageNames }.joinToString { it.displayName }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(TextView(this).apply {
                text = "${mode.name}${if (mode.id == activeId) " · ${getString(R.string.focus_mode_active)}" else ""}\n$names"
                textSize = 16f
            })
            val buttons = LinearLayout(this@FocusModeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this@FocusModeActivity).apply {
                text = getString(R.string.activate_focus_mode)
                isEnabled = mode.id != activeId
                setOnClickListener { activateMode(mode) }
            })
            buttons.addView(Button(this@FocusModeActivity).apply {
                text = getString(R.string.edit_focus_mode)
                setOnClickListener { editMode(mode) }
            })
            buttons.addView(Button(this@FocusModeActivity).apply {
                text = getString(R.string.delete_focus_mode)
                setOnClickListener { deleteMode(mode) }
            })
            card.addView(buttons)
            modesList.addView(card)
        }
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized) return
        val active = focusStore.activeMode()
        statusView.text = when {
            AppBlockingVpnService.isVpnActive -> getString(R.string.status_active_unverified)
            ScheduleCoordinator.effectiveBlockedPackages(this).isNotEmpty() -> getString(R.string.status_effective_not_active)
            active != null -> getString(R.string.status_focus_active, active.name)
            else -> getString(R.string.status_no_selection)
        }
    }

    private fun requestFocusConsentIfNeeded() {
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

    companion object {
        private const val VPN_CONSENT_REQUEST = 4001
        private const val NOTIFICATION_PERMISSION_REQUEST = 4002
    }
}
