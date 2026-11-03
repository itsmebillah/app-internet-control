package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class UsageHistoryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var database: UsageHistoryDatabase
    private lateinit var statusView: TextView
    private lateinit var totalView: TextView
    private lateinit var coverageView: TextView
    private lateinit var periodSpinner: Spinner
    private lateinit var recordsList: LinearLayout
    private lateinit var trackingButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = UsageHistoryDatabase(this)
        setContentView(createScreen())
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) render()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun createScreen(): ScrollView {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_scope_notice)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        })
        statusView = TextView(this).apply { textSize = 14f }
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = getString(R.string.usage_access_settings)
            setOnClickListener { openUsageAccessSettings() }
        })
        trackingButton = Button(this).apply { setOnClickListener { toggleCollection() } }
        content.addView(trackingButton)
        content.addView(Button(this).apply {
            text = getString(R.string.usage_refresh)
            setOnClickListener { refreshUsage() }
        })
        content.addView(Button(this).apply {
            text = getString(R.string.usage_clear)
            setOnClickListener {
                AlertDialog.Builder(this@UsageHistoryActivity)
                    .setMessage(R.string.usage_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.usage_clear) { _, _ ->
                        database.clearHistory()
                        render()
                    }
                    .show()
            }
        })
        coverageView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }
        content.addView(coverageView)
        totalView = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(totalView)
        periodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@UsageHistoryActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(R.string.usage_period_today, R.string.usage_period_month, R.string.usage_period_lifetime)
                    .map { getString(it) })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    renderRows()
                }
            }
        }
        content.addView(periodSpinner)
        recordsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recordsList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply { addView(content) }
    }

    private fun toggleCollection() {
        if (database.isStarted()) {
            database.stopCollection()
            UsageCollectionJobService.cancel(this)
            render()
            return
        }
        if (!UsageCollectionRunner.hasUsageAccess(this)) {
            openUsageAccessSettings()
            return
        }
        if (!UsageCollectionJobService.schedule(this)) {
            Toast.makeText(this, R.string.usage_job_schedule_failed, Toast.LENGTH_LONG).show()
            return
        }
        database.startCollection()
        render()
        refreshUsage()
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.usage_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUsage() {
        if (!UsageCollectionRunner.hasUsageAccess(this)) {
            Toast.makeText(this, R.string.usage_access_needed, Toast.LENGTH_LONG).show()
            render()
            return
        }
        if (!database.isStarted()) {
            Toast.makeText(this, R.string.usage_collection_stopped, Toast.LENGTH_LONG).show()
            return
        }
        statusView.text = getString(R.string.usage_refreshing)
        worker.execute {
            val result = UsageCollectionRunner.collect(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    UsageCollectionResult.CAPTURED -> Unit
                    UsageCollectionResult.NO_NEW_INTERVAL -> Toast.makeText(this, R.string.usage_no_new_data, Toast.LENGTH_SHORT).show()
                    UsageCollectionResult.ACCESS_REQUIRED -> Toast.makeText(this, R.string.usage_access_needed, Toast.LENGTH_LONG).show()
                    UsageCollectionResult.NOT_TRACKING -> Toast.makeText(this, R.string.usage_collection_stopped, Toast.LENGTH_LONG).show()
                    UsageCollectionResult.UNAVAILABLE -> Toast.makeText(this, R.string.usage_data_unavailable, Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun render() {
        if (!::statusView.isInitialized) return
        val hasAccess = UsageCollectionRunner.hasUsageAccess(this)
        val started = database.collectionStartedAt()
        val collecting = database.isStarted()
        statusView.text = when {
            !hasAccess -> getString(R.string.usage_access_missing)
            collecting -> getString(R.string.usage_tracking_since, formatTimestamp(started))
            started > 0 -> getString(R.string.usage_collection_paused)
            else -> getString(R.string.usage_not_started)
        }
        trackingButton.text = getString(if (collecting) R.string.usage_stop else R.string.usage_start)
        trackingButton.isEnabled = hasAccess || collecting
        val today = LocalDate.now()
        val coverage = database.readCoveredMillis(today)
        coverageView.text = buildString {
            append(getString(R.string.usage_today_coverage, formatDuration(coverage)))
            val last = database.lastCapturedAt()
            if (last > 0) append("\n").append(getString(R.string.usage_last_capture, formatTimestamp(last)))
            if (database.hasCoverageGap()) append("\n").append(getString(R.string.usage_gap_warning))
        }
        val period = selectedPeriod()
        val totals = UsageSummaryCalculator.aggregate(database.readRecords(), period, today)
        totalView.text = if (totals.isEmpty()) getString(R.string.usage_totals_missing) else {
            val rx = totals.sumOf { it.receivedBytes }
            val tx = totals.sumOf { it.transmittedBytes }
            getString(R.string.usage_totals, UsageBytesFormatter.format(rx), UsageBytesFormatter.format(tx), UsageBytesFormatter.format(rx + tx))
        }
        renderRows(totals)
    }

    private fun renderRows(totals: List<AppUsageTotal>? = null) {
        if (!::recordsList.isInitialized) return
        recordsList.removeAllViews()
        val entries = totals ?: UsageSummaryCalculator.aggregate(database.readRecords(), selectedPeriod(), LocalDate.now())
        if (entries.isEmpty()) {
            recordsList.addView(TextView(this).apply { text = getString(R.string.usage_empty) })
            return
        }
        entries.forEach { entry ->
            val names = entry.packageNames.map(::displayName).joinToString(" / ")
            recordsList.addView(TextView(this).apply {
                text = getString(R.string.usage_app_row, names,
                    UsageBytesFormatter.format(entry.receivedBytes),
                    UsageBytesFormatter.format(entry.transmittedBytes))
                textSize = 14f
                setPadding(0, dp(10), 0, dp(10))
            })
            recordsList.addView(TextView(this).apply { setBackgroundColor(0x22000000) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun selectedPeriod(): UsagePeriod = when (periodSpinner.selectedItemPosition) {
        0 -> UsagePeriod.DAY
        1 -> UsagePeriod.MONTH
        else -> UsagePeriod.LIFETIME
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun displayName(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun formatTimestamp(millis: Long): String = USAGE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes < 60) getString(R.string.usage_covered_minutes, minutes)
        else getString(R.string.usage_covered_hours_minutes, minutes / 60, minutes % 60)
    }

    companion object {
        private val USAGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
