package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.time.LocalDate

class UsageAnalyticsActivity : Activity() {
    private lateinit var database: UsageHistoryDatabase
    private lateinit var statusView: TextView
    private lateinit var totalsView: TextView
    private lateinit var topAppsList: LinearLayout
    private lateinit var trendsList: LinearLayout
    private lateinit var periodSpinner: Spinner
    private var records: List<UsageRecord> = emptyList()

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
            text = getString(R.string.analytics_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        content.addView(statusView)
        periodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@UsageAnalyticsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(R.string.usage_period_today, R.string.usage_period_month, R.string.usage_period_lifetime)
                    .map { getString(it) })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (::totalsView.isInitialized) renderAnalytics()
                }
            }
        }
        content.addView(periodSpinner)
        totalsView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 16, 0, 16)
        }
        content.addView(totalsView)
        content.addView(sectionTitle(R.string.analytics_top_apps))
        topAppsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(topAppsList)
        content.addView(sectionTitle(R.string.analytics_daily_trend))
        content.addView(TextView(this).apply {
            text = getString(R.string.analytics_trend_notice)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        })
        trendsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(trendsList)
        return ScrollView(this).apply { addView(content) }
    }

    private fun sectionTitle(resource: Int) = TextView(this).apply {
        text = getString(resource)
        textSize = 19f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 20, 0, 8)
    }

    private fun render() {
        records = database.readRecords()
        val start = database.collectionStartedAt()
        statusView.text = when {
            start == 0L -> getString(R.string.analytics_not_started)
            database.hasCoverageGap() -> getString(R.string.analytics_coverage_gap, formatDate(start))
            else -> getString(R.string.analytics_coverage_since, formatDate(start))
        }
        renderAnalytics()
    }

    private fun renderAnalytics() {
        if (!::totalsView.isInitialized) return
        val today = LocalDate.now()
        val period = when (periodSpinner.selectedItemPosition) {
            0 -> UsagePeriod.DAY
            1 -> UsagePeriod.MONTH
            else -> UsagePeriod.LIFETIME
        }
        val totals = UsageSummaryCalculator.aggregate(records, period, today)
        if (totals.isEmpty()) {
            totalsView.text = getString(R.string.usage_totals_missing)
        } else {
            val received = totals.sumOf { it.receivedBytes }
            val sent = totals.sumOf { it.transmittedBytes }
            totalsView.text = getString(R.string.usage_totals,
                UsageBytesFormatter.format(received), UsageBytesFormatter.format(sent), UsageBytesFormatter.format(received + sent))
        }
        renderTopApps(UsageAnalyticsCalculator.topApps(records, period, today))
        renderTrend(today)
    }

    private fun renderTopApps(topApps: List<AppUsageTotal>) {
        topAppsList.removeAllViews()
        if (topApps.isEmpty()) {
            topAppsList.addView(TextView(this).apply { text = getString(R.string.analytics_no_top_apps) })
            return
        }
        val maxBytes = topApps.maxOf { it.totalBytes }.coerceAtLeast(1)
        topApps.forEachIndexed { index, appUsage ->
            val name = appUsage.packageNames.joinToString(" / ", transform = ::displayName)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.analytics_app_total, index + 1, name, UsageBytesFormatter.format(appUsage.totalBytes))
                textSize = 14f
            })
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = BAR_MAX
                progress = (appUsage.totalBytes.toDouble() / maxBytes * BAR_MAX).toInt().coerceIn(0, BAR_MAX)
                contentDescription = getString(R.string.analytics_bar_accessibility, name)
            }
            row.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))
            topAppsList.addView(row)
        }
    }

    private fun renderTrend(today: LocalDate) {
        trendsList.removeAllViews()
        val coverage = (0L..6L).associate { offset ->
            val date = today.minusDays(offset)
            date to database.readCoveredMillis(date)
        }
        val points = UsageAnalyticsCalculator.recentDays(records, coverage, today)
        if (points.isEmpty()) {
            trendsList.addView(TextView(this).apply { text = getString(R.string.analytics_no_trend) })
            return
        }
        val maxBytes = points.maxOf { it.receivedBytes + it.transmittedBytes }.coerceAtLeast(1)
        points.forEach { point ->
            val total = point.receivedBytes + point.transmittedBytes
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6, 0, 6) }
            row.addView(TextView(this).apply {
                text = getString(R.string.analytics_day_total, point.date.toString(), UsageBytesFormatter.format(total))
                textSize = 13f
            })
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = BAR_MAX
                progress = (total.toDouble() / maxBytes * BAR_MAX).toInt().coerceIn(0, BAR_MAX)
                contentDescription = getString(R.string.analytics_day_bar_accessibility, point.date.toString())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            trendsList.addView(row)
        }
    }

    private fun displayName(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun formatDate(millis: Long): String = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BAR_MAX = 1_000
    }
}
