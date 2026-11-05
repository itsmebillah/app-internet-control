package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChangeHistoryActivity : Activity() {
    private lateinit var iconLoader: PackageIconLoader
    private lateinit var appUi: AppUiComponents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconLoader = PackageIconLoader(this)
        appUi = AppUiComponents(this, iconLoader)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        content.addView(TextView(this).apply {
            text = getString(R.string.history_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.history_privacy_notice)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        })
        content.addView(android.widget.Button(this).apply {
            text = getString(R.string.history_clear)
            setOnClickListener {
                AlertDialog.Builder(this@ChangeHistoryActivity)
                    .setMessage(R.string.history_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.history_clear) { _, _ ->
                        ChangeHistoryStore(this@ChangeHistoryActivity).clear()
                        recreate()
                    }
                    .show()
            }
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val events = ChangeHistoryStore(this).read().asReversed()
        if (events.isEmpty()) {
            rows.addView(TextView(this).apply { text = getString(R.string.history_empty) })
        } else {
            events.forEach { event ->
                val action = getString(if (event.blocked) R.string.history_blocked else R.string.history_allowed)
                val source = getString(when (event.source) {
                    ChangeSource.MANUAL -> R.string.history_source_manual
                    ChangeSource.TIMER -> R.string.history_source_timer
                    ChangeSource.SCHEDULE -> R.string.history_source_schedule
                    ChangeSource.FOCUS -> R.string.history_source_focus
                    ChangeSource.OTHER -> R.string.history_source_other
                })
                val outcome = getString(when (event.outcome) {
                    EnforcementOutcome.PENDING -> R.string.history_outcome_pending
                    EnforcementOutcome.NOT_ACTIVE -> R.string.history_outcome_not_active
                    EnforcementOutcome.ACTIVE_UNVERIFIED -> R.string.history_outcome_unverified
                    EnforcementOutcome.OVERRIDDEN -> R.string.history_outcome_overridden
                })
                val timestamp = HISTORY_FORMAT.format(event.timestamp.atZone(ZoneId.systemDefault()))
                rows.addView(appUi.appRow(appUi.installedApp(event.packageName)))
                rows.addView(TextView(this).apply {
                    text = getString(R.string.history_entry, timestamp, action, source, outcome)
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(12))
                })
                rows.addView(TextView(this).apply { setBackgroundColor(0x22000000) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
        }
        val scroll = ScrollView(this).apply { addView(rows) }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
    }

    override fun onDestroy() {
        if (::iconLoader.isInitialized) iconLoader.close()
        super.onDestroy()
    }

    companion object {
        private val HISTORY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
