package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class SpeedActivity : Activity() {
    private lateinit var store: SpeedMonitorStore
    private lateinit var stateLabel: TextView
    private lateinit var ratesLabel: TextView
    private lateinit var intervalSpinner: Spinner
    private lateinit var monitorButton: Button
    private var receiverRegistered = false

    private val speedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val rates = if (intent?.getBooleanExtra(SpeedMonitorService.EXTRA_AVAILABLE, false) == true) {
                NetworkRates(
                    intent.getDoubleExtra(SpeedMonitorService.EXTRA_DOWNLOAD, 0.0),
                    intent.getDoubleExtra(SpeedMonitorService.EXTRA_UPLOAD, 0.0),
                )
            } else null
            renderRates(rates)
            renderState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SpeedMonitorStore(this)
        setContentView(createScreen())
        renderState()
        renderRates(if (store.isRunning) store.latestRates else null)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SpeedMonitorService.ACTION_SPEED_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(speedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(speedReceiver, filter)
        receiverRegistered = true
        renderState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(speedReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && store.isRunning && !SpeedMonitorService.canShowSpeedNotification(this)) {
            stopMonitor()
            showNotificationRequired()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            store.isRunning = false
            renderState()
            renderRates(null)
            showNotificationRequired()
            return
        }
        startMonitor()
    }

    private fun createScreen(): LinearLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SpeedActivity).apply {
                text = getString(R.string.speed_title)
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SpeedActivity).apply {
                text = getString(R.string.speed_scope_notice)
                textSize = 14f
                setPadding(0, dp(12), 0, dp(16))
            })
            stateLabel = TextView(this@SpeedActivity).apply { textSize = 16f }
            addView(stateLabel)
            ratesLabel = TextView(this@SpeedActivity).apply {
                textSize = 22f
                setPadding(0, dp(16), 0, dp(16))
            }
            addView(ratesLabel)
            addView(TextView(this@SpeedActivity).apply { text = getString(R.string.speed_interval_label) })
            intervalSpinner = Spinner(this@SpeedActivity).apply {
                adapter = ArrayAdapter(this@SpeedActivity, android.R.layout.simple_spinner_dropdown_item,
                    INTERVALS.map { getString(R.string.speed_interval_value, it / 1000) })
                setSelection(INTERVALS.indexOf(store.intervalMillis).coerceAtLeast(0))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        store.intervalMillis = INTERVALS[position]
                    }
                }
            }
            addView(intervalSpinner)
            monitorButton = Button(this@SpeedActivity).apply {
                setOnClickListener { if (store.isRunning) stopMonitor() else requestStart() }
            }
            addView(monitorButton)
        }
    }

    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        } else startMonitor()
    }

    private fun startMonitor() {
        if (!SpeedMonitorService.canShowSpeedNotification(this)) {
            store.isRunning = false
            stateLabel.text = getString(R.string.speed_state_notification_required)
            monitorButton.text = getString(R.string.speed_start)
            renderRates(null)
            showNotificationRequired()
            return
        }
        store.intervalMillis = INTERVALS[intervalSpinner.selectedItemPosition]
        val intent = Intent(this, SpeedMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            store.isRunning = true
            stateLabel.text = getString(R.string.speed_state_starting)
            monitorButton.text = getString(R.string.speed_stop)
        } catch (_: SecurityException) {
            store.isRunning = false
            stateLabel.text = getString(R.string.speed_state_start_denied)
        } catch (_: RuntimeException) {
            store.isRunning = false
            stateLabel.text = getString(R.string.speed_state_start_denied)
        }
    }

    private fun stopMonitor() {
        startService(Intent(this, SpeedMonitorService::class.java).setAction(SpeedMonitorService.ACTION_STOP))
        store.isRunning = false
        store.latestRates = null
        renderState()
        renderRates(null)
    }

    private fun renderState() {
        if (!::stateLabel.isInitialized) return
        stateLabel.text = getString(if (store.isRunning) R.string.speed_state_running else R.string.speed_state_stopped)
        monitorButton.text = getString(if (store.isRunning) R.string.speed_stop else R.string.speed_start)
    }

    private fun renderRates(rates: NetworkRates?) {
        if (!::ratesLabel.isInitialized) return
        ratesLabel.text = if (rates == null) getString(R.string.speed_waiting) else getString(
            R.string.speed_rates,
            NetworkRateFormatter.format(rates.downloadBytesPerSecond),
            NetworkRateFormatter.format(rates.uploadBytesPerSecond),
        )
    }

    private fun showNotificationRequired() {
        Toast.makeText(this, R.string.speed_notification_required, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 5101
        private val INTERVALS = listOf(1_000L, 2_000L, 5_000L, 10_000L)
    }
}
