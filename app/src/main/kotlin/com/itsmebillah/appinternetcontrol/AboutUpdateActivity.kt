package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AboutUpdateActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var checkButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.about_updates_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.about_version, UpdateCheckCoordinator.currentVersionName(this@AboutUpdateActivity), currentVersionCode())
            setPadding(0, padding / 2, 0, padding / 2)
        })
        statusView = TextView(this).apply { text = getString(R.string.update_check_ready) }
        content.addView(statusView)
        checkButton = Button(this).apply {
            text = getString(R.string.update_check_action)
            setOnClickListener { checkForUpdates() }
        }
        content.addView(checkButton)
        content.addView(TextView(this).apply {
            text = getString(R.string.play_store_update_note)
            setPadding(0, padding / 2, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(content)
    }

    private fun checkForUpdates() {
        checkButton.isEnabled = false
        statusView.setText(R.string.update_checking)
        UpdateCheckCoordinator.check(this, force = true) { result ->
            if (isFinishing || isDestroyed) return@check
            checkButton.isEnabled = true
            when (result) {
                UpdateCheckResult.NotConfigured -> statusView.setText(R.string.update_not_configured)
                UpdateCheckResult.UpToDate -> statusView.setText(R.string.update_up_to_date)
                UpdateCheckResult.Deferred -> statusView.setText(R.string.update_check_ready)
                is UpdateCheckResult.Failed -> statusView.setText(
                    when (result.reason) {
                        UpdateFailure.NETWORK -> R.string.update_check_network_error
                        UpdateFailure.INVALID_METADATA -> R.string.update_check_metadata_error
                        UpdateFailure.UNAVAILABLE -> R.string.update_check_unavailable
                    },
                )
                is UpdateCheckResult.Available -> {
                    statusView.text = getString(R.string.update_found_status, result.offer.versionName)
                    UpdatePromptDialog.show(
                        activity = this,
                        available = result,
                        onUpdate = { openReleasePage(result.offer) },
                        onLater = { UpdateCheckCoordinator.rememberLater(this, result.offer) },
                    )
                }
            }
        }
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

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}

object UpdatePromptDialog {
    fun show(
        activity: Activity,
        available: UpdateCheckResult.Available,
        onUpdate: () -> Unit,
        onLater: () -> Unit,
    ) {
        val offer = available.offer
        val installedVersion = UpdateCheckCoordinator.currentVersionName(activity)
        val message = if (available.required) {
            activity.getString(
                R.string.update_required_message,
                offer.versionName,
                offer.minimumSupportedVersionCode,
                installedVersion,
                offer.releaseNotes,
            )
        } else {
            activity.getString(R.string.update_available_message, offer.versionName, installedVersion, offer.releaseNotes)
        }
        AlertDialog.Builder(activity)
            .setTitle(if (available.required) R.string.update_required_title else R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_action) { _, _ -> onUpdate() }
            .setNegativeButton(if (available.required) R.string.update_close else R.string.update_later) { _, _ ->
                if (!available.required) onLater()
            }
            .show()
    }
}
