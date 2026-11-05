package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class TemporaryBlockActivity : Activity() {
    private val blockStore by lazy { TemporaryBlockStore(this) }
    private lateinit var statusView: TextView
    private lateinit var appButton: Button
    private lateinit var selectedAppsRows: LinearLayout
    private lateinit var durationField: EditText
    private lateinit var durationUnit: Spinner
    private lateinit var activeBlocksList: LinearLayout
    private var installedApps: List<InstalledApp> = emptyList()
    private lateinit var iconLoader: PackageIconLoader
    private lateinit var appUi: AppUiComponents
    private var selectedPackages = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            pruneExpiredBlocks()
            renderBlocks()
            renderStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconLoader = PackageIconLoader(this)
        appUi = AppUiComponents(this, iconLoader)
        loadVisibleApps()
        setContentView(createScreen())
        renderBlocks()
        renderStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        if (::iconLoader.isInitialized) iconLoader.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        pruneExpiredBlocks()
        ScheduleCoordinator.reconcile(this)
        renderBlocks()
        renderStatus()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MILLIS)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return
        if (resultCode == RESULT_OK) {
            ScheduleCoordinator.reconcile(this)
            renderStatus()
        } else {
            Toast.makeText(this, R.string.temporary_vpn_consent_declined, Toast.LENGTH_LONG).show()
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

    private fun createScreen(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.temporary_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.temporary_timing_warning)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        statusView = TextView(this).apply { textSize = 14f }
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = getString(R.string.enable_current_blocks)
            setOnClickListener { requestEnforcement() }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.temporary_choose_apps)
            setPadding(0, dp(12), 0, dp(4))
        })
        appButton = Button(this).apply {
            text = getString(R.string.choose_apps)
            setOnClickListener { chooseApps() }
        }
        content.addView(appButton)
        selectedAppsRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(selectedAppsRows)
        durationField = EditText(this).apply {
            hint = getString(R.string.temporary_duration)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("30")
        }
        content.addView(durationField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        durationUnit = Spinner(this).apply {
            adapter = ArrayAdapter.createFromResource(
                this@TemporaryBlockActivity,
                R.array.duration_units,
                android.R.layout.simple_spinner_item,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        content.addView(durationUnit)
        content.addView(Button(this).apply {
            text = getString(R.string.temporary_start_block)
            setOnClickListener { createTemporaryBlock() }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.active_temporary_blocks)
            textSize = 20f
            setPadding(0, dp(16), 0, dp(8))
        })
        activeBlocksList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(activeBlocksList)
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
        appUi.showPicker(R.string.temporary_choose_apps, installedApps, selectedPackages) { selected ->
            selectedPackages = selected.toMutableSet()
            updateAppButton()
        }
    }

    private fun updateAppButton() {
        appButton.text = getString(R.string.choose_apps)
        if (::selectedAppsRows.isInitialized) appUi.addAppRows(selectedAppsRows, selectedPackages, installedApps)
    }

    private fun createTemporaryBlock() {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, R.string.temporary_choose_app, Toast.LENGTH_LONG).show()
            return
        }
        val value = durationField.text.toString().toLongOrNull()
        val multiplier = when (durationUnit.selectedItemPosition) {
            0 -> MILLIS_PER_MINUTE
            1 -> MILLIS_PER_HOUR
            else -> MILLIS_PER_DAY
        }
        if (value == null || value < 1 || value > MAX_DURATION_MILLIS / multiplier) {
            Toast.makeText(this, R.string.temporary_invalid_duration, Toast.LENGTH_LONG).show()
            return
        }

        val start = Instant.now()
        blockStore.write(
            blockStore.read() + TemporaryBlock(
                id = UUID.randomUUID().toString(),
                packageNames = selectedPackages.toSet(),
                startedAt = start,
                expiresAt = start.plusMillis(value * multiplier),
            ),
        )
        selectedPackages.clear()
        updateAppButton()
        pruneExpiredBlocks()
        ScheduleCoordinator.reconcile(this)
        renderBlocks()
        renderStatus()
        requestEnforcement()
    }

    private fun cancelBlock(block: TemporaryBlock) {
        blockStore.write(blockStore.read().filterNot { it.id == block.id })
        ScheduleCoordinator.reconcile(this)
        renderBlocks()
        renderStatus()
    }

    private fun pruneExpiredBlocks() {
        val before = blockStore.read()
        val remaining = blockStore.expire(Instant.now())
        if (before.size != remaining.size) ScheduleCoordinator.reconcile(this)
    }

    private fun renderBlocks() {
        if (!::activeBlocksList.isInitialized) return
        activeBlocksList.removeAllViews()
        val now = Instant.now()
        val blocks = blockStore.expire(now)
        if (blocks.isEmpty()) {
            activeBlocksList.addView(TextView(this).apply { text = getString(R.string.temporary_empty) })
            return
        }
        blocks.forEach { block ->
            val remaining = Duration.between(now, block.expiresAt).coerceAtLeast(Duration.ZERO)
            val expiry = block.expiresAt.atZone(ZoneId.systemDefault()).format(EXPIRY_FORMAT)
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = getString(R.string.temporary_block_card_time, expiry, formatRemaining(remaining))
            })
            val appRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            appUi.addAppRows(appRows, block.packageNames, installedApps)
            row.addView(appRows)
            row.addView(Button(this).apply {
                text = getString(R.string.temporary_cancel)
                setOnClickListener { cancelBlock(block) }
            })
            activeBlocksList.addView(row)
        }
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized) return
        statusView.text = when {
            AppBlockingVpnService.isVpnActive -> getString(R.string.status_active_unverified)
            ScheduleCoordinator.effectiveBlockedPackages(this).isNotEmpty() -> getString(R.string.status_effective_not_active)
            else -> getString(R.string.status_no_selection)
        }
    }

    private fun formatRemaining(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0)
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> getString(R.string.remaining_days_hours, days, hours)
            hours > 0 -> getString(R.string.remaining_hours_minutes, hours, minutes)
            else -> getString(R.string.remaining_minutes, minutes.coerceAtLeast(1))
        }
    }

    private fun requestEnforcement() {
        if (ScheduleCoordinator.effectiveBlockedPackages(this).isEmpty()) {
            renderStatus()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        continueConsentFlow()
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
        private const val VPN_CONSENT_REQUEST = 3001
        private const val NOTIFICATION_PERMISSION_REQUEST = 3002
        private const val REFRESH_INTERVAL_MILLIS = 15_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
        private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
        private const val MAX_DURATION_MILLIS = 7 * MILLIS_PER_DAY
        private val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, HH:mm")
    }
}
