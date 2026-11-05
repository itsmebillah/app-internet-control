package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class WifiAccessActivity : Activity() {
    private lateinit var rulesContainer: LinearLayout
    private lateinit var iconLoader: PackageIconLoader
    private lateinit var appUi: AppUiComponents
    private var pendingNetwork: WifiNetworkIdentity? = null
    private var apps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconLoader = PackageIconLoader(this)
        appUi = AppUiComponents(this, iconLoader)
        apps = loadLaunchableApps()
        setContentView(createScreen())
        render()
    }

    override fun onResume() {
        super.onResume()
        if (WifiAccessRuleStore(this).read().any { it.enabled } && identityPermissionGranted()) {
            WifiAccessRuntimeState.update(WifiNetworkIdentityReader.currentConnectedWifi(this))
            ScheduleCoordinator.reconcile(this)
        }
        if (::rulesContainer.isInitialized) render()
    }

    override fun onDestroy() {
        if (::iconLoader.isInitialized) iconLoader.close()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return
        if (resultCode == RESULT_OK) {
            WifiAccessRuntimeState.update(WifiNetworkIdentityReader.currentConnectedWifi(this))
            ScheduleCoordinator.reconcile(this)
        } else {
            Toast.makeText(this, R.string.wifi_consent_declined, Toast.LENGTH_LONG).show()
        }
        render()
    }

    @Deprecated("Retained for minSdk-compatible runtime permission flow")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != WIFI_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.wifi_permission_denied, Toast.LENGTH_LONG).show()
            return
        }
        chooseAppsForCurrentNetwork()
    }

    private fun createScreen(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.wifi_access_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(Button(this).apply {
            text = getString(R.string.wifi_use_current)
            setOnClickListener { requestIdentityAccess() }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.wifi_add_rule)
            setOnClickListener { requestIdentityAccess() }
        })
        rulesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rulesContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun requestIdentityAccess() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), WIFI_PERMISSION_REQUEST)
            return
        }
        if (!getSystemService(LocationManager::class.java).isLocationEnabled) {
            AlertDialog.Builder(this)
                .setMessage(R.string.wifi_location_required)
                .setPositiveButton(android.R.string.ok) { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        chooseAppsForCurrentNetwork()
    }

    private fun chooseAppsForCurrentNetwork() {
        val network = WifiNetworkIdentityReader.currentConnectedWifi(this)
        if (network == null) {
            Toast.makeText(this, R.string.wifi_unknown_network, Toast.LENGTH_LONG).show()
            render()
            return
        }
        pendingNetwork = network
        appUi.showPicker(
            title = R.string.wifi_picker_title,
            apps = apps,
            initiallySelected = emptySet(),
            positiveButtonText = R.string.wifi_add_rule,
        ) { selected ->
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.wifi_select_apps, Toast.LENGTH_LONG).show()
                    return@showPicker
                }
                val saved = WifiAccessRuleStore(this).add(WifiAccessRule(
                    id = UUID.randomUUID().toString(),
                    network = checkNotNull(pendingNetwork),
                    packageNames = selected,
                ))
                if (!saved) {
                    Toast.makeText(this, R.string.wifi_rule_update_failed, Toast.LENGTH_LONG).show()
                    return@showPicker
                }
                pendingNetwork = null
                Toast.makeText(this, R.string.wifi_rule_added, Toast.LENGTH_LONG).show()
                requestVpnConsentIfNeeded()
                render()
        }
    }

    private fun requestVpnConsentIfNeeded() {
        val consent = VpnService.prepare(this)
        if (consent == null) {
            WifiAccessRuntimeState.update(WifiNetworkIdentityReader.currentConnectedWifi(this))
            ScheduleCoordinator.reconcile(this)
        } else {
            startActivityForResult(consent, VPN_CONSENT_REQUEST)
        }
    }

    private fun render() {
        val store = WifiAccessRuleStore(this)
        val rules = store.read()
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply { text = getString(R.string.wifi_empty_rules) })
            return
        }
        rules.forEach { rule ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(TextView(this).apply {
                text = getString(R.string.wifi_rule_card, rule.network.ssid, if (rule.enabled) "" else " · disabled")
                textSize = 15f
                setPadding(0, dp(12), 0, dp(8))
            })
            val appRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            appUi.addAppRows(appRows, rule.packageNames, apps)
            card.addView(appRows)
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(Button(this@WifiAccessActivity).apply {
                text = getString(if (rule.enabled) R.string.wifi_disable else R.string.wifi_enable)
                setOnClickListener {
                    val enable = !rule.enabled
                    if (!store.setEnabled(rule.id, enable)) {
                        Toast.makeText(this@WifiAccessActivity, R.string.wifi_rule_update_failed, Toast.LENGTH_LONG).show()
                        render()
                        return@setOnClickListener
                    }
                    if (enable) {
                        requestVpnConsentIfNeeded()
                    } else {
                        ScheduleCoordinator.reconcile(this@WifiAccessActivity)
                    }
                    render()
                }
            })
            controls.addView(Button(this@WifiAccessActivity).apply {
                text = getString(R.string.wifi_delete)
                setOnClickListener {
                    if (!store.delete(rule.id)) {
                        Toast.makeText(this@WifiAccessActivity, R.string.wifi_rule_update_failed, Toast.LENGTH_LONG).show()
                        render()
                        return@setOnClickListener
                    }
                    ScheduleCoordinator.reconcile(this@WifiAccessActivity)
                    render()
                }
            })
            card.addView(controls)
            rulesContainer.addView(card)
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filterNot { it.packageName == packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun identityPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val WIFI_PERMISSION_REQUEST = 3201
        private const val VPN_CONSENT_REQUEST = 3202
    }
}
