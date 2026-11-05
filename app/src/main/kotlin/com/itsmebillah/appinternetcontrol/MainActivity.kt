package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val selectionStore by lazy { AppSelectionStore(this) }
    private lateinit var statusView: TextView
    private lateinit var enableButton: Button
    private lateinit var searchField: EditText
    private lateinit var appRows: LinearLayout
    private lateinit var iconLoader: PackageIconLoader
    private var installedApps: List<InstalledApp> = emptyList()
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemovedFeatureDataCleanup.run(this)
        iconLoader = PackageIconLoader(this)
        setContentView(createScreen())
        loadInstalledApps()
        renderAppRows()
        renderStatus()
        UpdateCheckCoordinator.check(this, force = false) { result ->
            if (!isFinishing && !isDestroyed) showUpdateResult(result)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppBlockingVpnService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        ScheduleCoordinator.reconcile(this)
        renderStatus()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::iconLoader.isInitialized) iconLoader.close()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return

        if (resultCode == RESULT_OK) {
            startOrUpdateVpn()
        } else {
            renderStatus()
            Toast.makeText(this, R.string.vpn_consent_declined, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            continueEnableFlow()
        } else {
            renderStatus()
            Toast.makeText(this, R.string.notification_permission_optional, Toast.LENGTH_LONG).show()
            continueEnableFlow()
        }
    }

    private fun createScreen(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val navigationBarInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    0,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            view.setPadding(
                dp(20) + navigationBarInsets.left,
                dp(16),
                dp(20) + navigationBarInsets.right,
                dp(16) + navigationBarInsets.bottom,
            )
            insets
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.info_icon)
                textSize = 24f
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                contentDescription = getString(R.string.vpn_limitation_info_accessibility)
                isClickable = true
                isFocusable = true
                setOnClickListener { showVpnLimitationInfo() }
            })
        })

        searchField = EditText(this).apply {
            hint = getString(R.string.search_apps)
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderAppRows()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        content.addView(searchField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            contentDescription = getString(R.string.enforcement_status_accessibility)
        }
        content.addView(statusView)

        enableButton = Button(this).apply {
            text = getString(R.string.enable_selected_blocks)
            setOnClickListener { beginEnableFlow() }
        }
        content.addView(enableButton)

        content.addView(Button(this).apply {
            text = getString(R.string.more_features)
            setOnClickListener { showFeatureMenu() }
        })

        val scrollView = ScrollView(this)
        appRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(appRows)
        content.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return content
    }

    private fun showFeatureMenu() {
        val destinations = listOf(
            getString(R.string.wifi_access_title) to WifiAccessActivity::class.java,
            getString(R.string.manage_schedules) to ScheduleActivity::class.java,
            getString(R.string.temporary_blocks) to TemporaryBlockActivity::class.java,
            getString(R.string.focus_modes) to FocusModeActivity::class.java,
            getString(R.string.history_title) to ChangeHistoryActivity::class.java,
            getString(R.string.speed_title) to SpeedActivity::class.java,
            getString(R.string.usage_title) to UsageHistoryActivity::class.java,
            getString(R.string.analytics_title) to UsageAnalyticsActivity::class.java,
            getString(R.string.about_updates_title) to AboutUpdateActivity::class.java,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.more_features)
            .setItems(destinations.map { it.first }.toTypedArray()) { _, index ->
                startActivity(Intent(this, destinations[index].second))
            }
            .show()
    }

    private fun showVpnLimitationInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_limitation_info_title)
            .setMessage(R.string.vpn_limitation_info_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showUpdateResult(result: UpdateCheckResult) {
        val available = result as? UpdateCheckResult.Available ?: return
        UpdatePromptDialog.show(
            activity = this,
            available = available,
            onUpdate = { openReleasePage(available.offer) },
            onLater = { UpdateCheckCoordinator.rememberLater(this, available.offer) },
        )
    }

    private fun openReleasePage(offer: UpdateOffer) {
        try {
            UpdateCheckCoordinator.launchUpdate(this, offer)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.update_link_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.update_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadInstalledApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        installedApps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filterNot { it.packageName == packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val label = packageManager.getApplicationLabel(info).toString()
                InstalledApp(info.packageName, label)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

        val visiblePackages = installedApps.mapTo(mutableSetOf()) { it.packageName }
        val current = selectionStore.read()
        val pruned = current.retainVisiblePackages(visiblePackages)
        if (pruned.packageNames != current.packageNames) selectionStore.write(pruned)
    }

    private fun renderAppRows() {
        if (!::appRows.isInitialized) return
        appRows.removeAllViews()
        val selection = selectionStore.read()
        val activeSchedules = BlockScheduleStore(this).read()
            .filter { ScheduleEvaluator.isActive(it, java.time.Instant.now(), java.time.ZoneId.systemDefault()) }
        val activeTemporaryPackages = TemporaryBlockEvaluator.activePackageNames(
            TemporaryBlockStore(this).expire(java.time.Instant.now()),
            java.time.Instant.now(),
        )
        val focusPackages = FocusModeStore(this).activeMode()?.packageNames.orEmpty()
        val query = if (::searchField.isInitialized) searchField.text.toString().trim() else ""
        val matchingApps = installedApps.filter {
            query.isBlank() || it.displayName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }

        if (matchingApps.isEmpty()) {
            appRows.addView(TextView(this).apply {
                text = getString(R.string.no_apps_found)
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(0, padding, 0, padding)
            })
            return
        }

        matchingApps.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, padding, 0, padding)
            }
            val icon = ImageView(this).apply {
                val size = (48 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                contentDescription = getString(R.string.app_icon_accessibility, app.displayName)
            }
            iconLoader.bind(app.packageName, icon)
            row.addView(icon)

            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * resources.displayMetrics.density).toInt(), 0, 8, 0)
            }
            labels.addView(TextView(this).apply {
                text = app.displayName
                textSize = 16f
            })
            labels.addView(TextView(this).apply {
                val appActions = activeSchedules.filter { app.packageName in it.packageNames }.map { it.action }
                val scheduleState = when {
                    app.packageName in activeTemporaryPackages -> getString(R.string.app_temporary_block)
                    ScheduleAction.BLOCK in appActions -> getString(R.string.app_schedule_block)
                    ScheduleAction.ALLOW in appActions -> getString(R.string.app_schedule_allow)
                    app.packageName in focusPackages -> getString(R.string.app_focus_block)
                    else -> getString(if (app.packageName in selection.packageNames) R.string.app_manual_block else R.string.app_manual_allow)
                }
                text = "${app.packageName} · $scheduleState"
                textSize = 12f
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Switch(this).apply {
                text = getString(R.string.block_control_label)
                contentDescription = getString(R.string.block_app_accessibility, app.displayName)
                isChecked = app.packageName in selection.packageNames
                setOnCheckedChangeListener { _, shouldBlock -> onSelectionChanged(app, shouldBlock) }
            })
            appRows.addView(row)
            appRows.addView(View(this).apply {
                setBackgroundColor(0x22000000)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.density.toInt().coerceAtLeast(1)))
        }
    }

    private fun onSelectionChanged(app: InstalledApp, shouldBlock: Boolean) {
        val updated = selectionStore.read().withBlocked(app.packageName, shouldBlock)
        selectionStore.write(updated)
        ScheduleCoordinator.captureManualChange(this, app.packageName, shouldBlock)
        renderStatus()

        if (VpnServiceLifecyclePolicy.shouldStop(ScheduleCoordinator.effectiveBlockedPackages(this))) {
            AppBlockingVpnService.requestStop(this)
        } else {
            beginEnableFlow()
        }
    }

    private fun beginEnableFlow() {
        if (selectionStore.read().packageNames.isEmpty()) {
            renderStatus()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        continueEnableFlow()
    }

    private fun continueEnableFlow() {
        if (selectionStore.read().packageNames.isEmpty()) return
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            startActivityForResult(consentIntent, VPN_CONSENT_REQUEST)
        } else {
            startOrUpdateVpn()
        }
    }

    private fun startOrUpdateVpn() {
        val packagesToRoute = VpnRoutingPolicy.selectedInstalledPackages(
            selection = AppSelection.of(ScheduleCoordinator.effectiveBlockedPackages(this)),
            installedPackages = installedApps.mapTo(mutableSetOf()) { it.packageName },
            ownPackage = packageName,
        )
        if (packagesToRoute.isEmpty()) {
            renderStatus()
            return
        }

        val serviceIntent = Intent(this, AppBlockingVpnService::class.java)
            .putStringArrayListExtra(AppBlockingVpnService.EXTRA_SELECTED_PACKAGES, ArrayList(packagesToRoute))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        renderStatus()
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized || !::enableButton.isInitialized) return
        val selection = selectionStore.read()
        val activeFocusMode = FocusModeStore(this).activeMode()
        val effectivePackages = ScheduleCoordinator.effectiveBlockedPackages(this)
        statusView.text = when {
            AppBlockingVpnService.isVpnActive -> getString(R.string.status_vpn_active_unverified_short)
            effectivePackages.isNotEmpty() -> getString(R.string.status_effective_not_active)
            activeFocusMode != null -> getString(R.string.status_focus_active, activeFocusMode.name)
            selection.packageNames.isNotEmpty() -> getString(R.string.status_schedule_allows)
            else -> getString(R.string.status_no_selection)
        }
        enableButton.isEnabled = effectivePackages.isNotEmpty()
        enableButton.text = getString(R.string.enable_current_blocks)
        renderAppRows()
    }

    companion object {
        private const val VPN_CONSENT_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
    }
}
