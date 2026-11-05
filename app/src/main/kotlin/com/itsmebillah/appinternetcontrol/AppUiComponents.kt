package com.itsmebillah.appinternetcontrol

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Shared rendering for app names so every installed-app label is paired with its launcher icon. */
class AppUiComponents(private val activity: Activity, private val icons: PackageIconLoader) {
    private val density get() = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    fun appRow(app: InstalledApp, sizeDp: Int = 40): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val icon = ImageView(activity).apply {
            contentDescription = activity.getString(R.string.app_icon_accessibility, app.displayName)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        row.addView(TextView(activity).apply {
            text = app.displayName
            textSize = 14f
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        icons.bind(app.packageName, icon)
        return row
    }

    fun addAppRows(container: LinearLayout, packageNames: Collection<String>, knownApps: List<InstalledApp>) {
        container.removeAllViews()
        val known = knownApps.associateBy { it.packageName }
        AppRowOrdering.preserve(packageNames, knownApps).forEach { packageName ->
            val app = known[packageName] ?: installedApp(packageName)
            container.addView(appRow(app))
        }
    }

    fun showPicker(
        title: Int,
        apps: List<InstalledApp>,
        initiallySelected: Set<String>,
        positiveButtonText: Int = android.R.string.ok,
        onConfirmed: (Set<String>) -> Unit,
    ) {
        if (apps.isEmpty()) return
        val selected = initiallySelected.toMutableSet()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16)
            setPadding(padding, dp(4), padding, padding)
        }
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.search_apps)
            isSingleLine = true
        }
        val empty = TextView(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(20), dp(8), dp(20))
        }
        val list = ListView(activity).apply { dividerHeight = dp(1) }
        val adapter = object : BaseAdapter() {
            var query = ""
            private fun visible() = AppPickerFilter.filter(apps, query)
            override fun getCount() = visible().size
            override fun getItem(position: Int) = visible()[position]
            override fun getItemId(position: Int) = getItem(position).packageName.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = getItem(position)
                val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    addView(ImageView(activity), LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(TextView(activity).apply { textSize = 15f; setPadding(dp(10), 0, dp(6), 0) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(activity).apply { isClickable = false; isFocusable = false })
                }
                val icon = row.getChildAt(0) as ImageView
                icon.contentDescription = activity.getString(R.string.app_icon_accessibility, app.displayName)
                icons.bind(app.packageName, icon)
                (row.getChildAt(1) as TextView).text = app.displayName
                (row.getChildAt(2) as CheckBox).apply {
                    isChecked = app.packageName in selected
                    contentDescription = app.displayName
                }
                row.setOnClickListener {
                    if (!selected.add(app.packageName)) selected.remove(app.packageName)
                    notifyDataSetChanged()
                }
                return row
            }
            fun refresh() {
                notifyDataSetChanged()
                val hasItems = count > 0
                list.visibility = if (hasItems) View.VISIBLE else View.GONE
                empty.visibility = if (hasItems) View.GONE else View.VISIBLE
                empty.text = activity.getString(if (query.isBlank()) R.string.app_picker_empty else R.string.app_picker_no_match)
            }
        }
        list.adapter = adapter
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.query = s?.toString().orEmpty()
                adapter.refresh()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        content.addView(search)
        content.addView(empty)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        adapter.refresh()
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(positiveButtonText) { _, _ -> onConfirmed(selected.toSet()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun installedApp(packageName: String): InstalledApp {
        val label = runCatching {
            activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return InstalledApp(packageName, label)
    }
}

/** Keeps prior app-list order and appends packages whose app is no longer in the launchable list. */
object AppRowOrdering {
    fun preserve(packageNames: Collection<String>, knownApps: List<InstalledApp>): List<String> {
        val requested = packageNames.toSet()
        val knownOrder = knownApps.map { it.packageName }.filter { it in requested }.distinct()
        val knownSet = knownOrder.toSet()
        return knownOrder + packageNames.filterNot { it in knownSet }.distinct()
    }
}
