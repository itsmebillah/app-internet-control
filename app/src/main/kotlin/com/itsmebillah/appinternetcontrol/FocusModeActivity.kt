package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
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
    private lateinit var deactivateModeButton: Button
    private lateinit var modeName: EditText
    private lateinit var appSelectionButton: Button
    private lateinit var selectedAppsRows: LinearLayout
    private var installedApps: List<InstalledApp> = emptyList()
    private lateinit var iconLoader: PackageIconLoader
    private lateinit var appUi: AppUiComponents
    private var selectedPackages = mutableSetOf<String>()
    private var editingModeId: String? = null
    private var statusReceiverRegistered = false

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconLoader = PackageIconLoader(this)
        appUi = AppUiComponents(this, iconLoader)
        loadVisibleApps()
        setContentView(createScreen())
        renderModes()
        renderStatus()
    }

    override fun onDestroy() {
        if (::iconLoader.isInitialized) iconLoader.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ScheduleCoordinator.reconcile(this)
        renderModes()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppBlockingVpnService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStatusReceiver, filter)
        }
        statusReceiverRegistered = true
        renderStatus()
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(vpnStatusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
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
        deactivateModeButton = Button(this).apply {
            text = getString(R.string.deactivate_focus_mode)
            visibility = View.GONE
            setOnClickListener {
                focusStore.activate(null)
                ScheduleCoordinator.reconcile(this@FocusModeActivity)
                renderModes()
                renderStatus()
            }
        }
        content.addView(deactivateModeButton)
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
        selectedAppsRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(selectedAppsRows)
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
        appUi.showPicker(R.string.choose_apps, installedApps, selectedPackages) { selected ->
            selectedPackages = selected.toMutableSet()
            updateAppButton()
        }
    }

    private fun updateAppButton() {
        if (!::appSelectionButton.isInitialized) return
        appSelectionButton.text = getString(R.string.choose_apps)
        if (::selectedAppsRows.isInitialized) appUi.addAppRows(selectedAppsRows, selectedPackages, installedApps)
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
        renderModesState(FocusModesUiState.Loading)
        renderModesState(FocusModesUiStateResolver.resolve(isLoading = false, snapshot = focusStore.snapshot()))
    }

    private fun renderModesState(state: FocusModesUiState) {
        if (!::modesList.isInitialized) return
        modesList.removeAllViews()
        deactivateModeButton.visibility = if (state is FocusModesUiState.Active) View.VISIBLE else View.GONE
        when (state) {
            FocusModesUiState.Loading -> {
                modesList.addView(TextView(this).apply { text = getString(R.string.focus_modes_loading) })
            }
            FocusModesUiState.Empty -> {
                modesList.addView(TextView(this).apply { text = getString(R.string.no_focus_modes) })
            }
            is FocusModesUiState.Inactive -> renderModeCards(state.modes, activeModeId = null)
            is FocusModesUiState.Active -> renderModeCards(state.modes, activeModeId = state.activeMode.id)
        }
    }

    private fun renderModeCards(modes: List<FocusMode>, activeModeId: String?) {
        modes.forEach { mode ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(TextView(this).apply {
                text = "${mode.name}${if (mode.id == activeModeId) " · ${getString(R.string.focus_mode_active)}" else ""}"
                textSize = 16f
            })
            val appRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            appUi.addAppRows(appRows, mode.packageNames, installedApps)
            card.addView(appRows)
            val buttons = LinearLayout(this@FocusModeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this@FocusModeActivity).apply {
                text = getString(R.string.activate_focus_mode)
                isEnabled = mode.id != activeModeId
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
        statusView.text = when (FocusStatusPolicy.resolve(
            vpnActive = AppBlockingVpnService.isVpnActive,
            hasEffectiveBlocks = ScheduleCoordinator.effectiveBlockedPackages(this).isNotEmpty(),
            activeModeName = active?.name,
        )) {
            FocusStatus.ACTIVE_UNVERIFIED -> getString(R.string.status_active_unverified)
            FocusStatus.RULE_NOT_ACTIVE -> getString(R.string.status_effective_not_active)
            FocusStatus.MODE_ACTIVE -> getString(R.string.status_focus_active, active?.name.orEmpty())
            FocusStatus.NO_SELECTION -> getString(R.string.status_no_selection)
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
