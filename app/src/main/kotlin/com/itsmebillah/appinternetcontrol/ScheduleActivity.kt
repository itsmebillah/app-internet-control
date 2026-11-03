package com.itsmebillah.appinternetcontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class ScheduleActivity : Activity() {
    private val scheduleStore by lazy { BlockScheduleStore(this) }
    private lateinit var statusView: TextView
    private lateinit var schedulesList: LinearLayout
    private lateinit var scheduleName: EditText
    private lateinit var dateMode: RadioButton
    private lateinit var weeklyMode: RadioButton
    private lateinit var dateButton: Button
    private lateinit var weekdaysContainer: LinearLayout
    private lateinit var appSelectionButton: Button
    private lateinit var actionGroup: RadioGroup
    private val weekdayBoxes = linkedMapOf<DayOfWeek, CheckBox>()
    private var visibleApps: List<InstalledApp> = emptyList()
    private var selectedPackages = mutableSetOf<String>()
    private var selectedDate = LocalDate.now()
    private var startTime = LocalTime.of(20, 0)
    private var endTime = LocalTime.of(21, 0)
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadVisibleApps()
        setContentView(createScreen())
        renderSchedules()
    }

    override fun onResume() {
        super.onResume()
        ScheduleCoordinator.reconcile(this)
        renderStatus()
        renderSchedules()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return
        if (resultCode == RESULT_OK) {
            ScheduleCoordinator.reconcile(this)
            renderStatus()
        } else {
            ScheduleRuntimeStore(this).status = ScheduleRuntimeStore.STATUS_AWAITING_VPN_CONSENT
            renderStatus()
            Toast.makeText(this, R.string.schedule_vpn_consent_declined, Toast.LENGTH_LONG).show()
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
            text = getString(R.string.schedule_title)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.schedule_timing_warning)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        statusView = TextView(this).apply { textSize = 14f }
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = getString(R.string.enable_schedule_enforcement)
            setOnClickListener { requestScheduleEnforcement() }
        })
        schedulesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(schedulesList)
        content.addView(TextView(this).apply {
            text = getString(R.string.create_schedule)
            textSize = 20f
            setPadding(0, dp(16), 0, dp(8))
        })

        scheduleName = EditText(this).apply { hint = getString(R.string.schedule_name_hint) }
        content.addView(scheduleName)
        appSelectionButton = Button(this).apply {
            text = getString(R.string.choose_apps)
            setOnClickListener { chooseApps() }
        }
        content.addView(appSelectionButton)

        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        dateMode = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.one_time)
        }
        weeklyMode = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.recurring_weekly)
        }
        modeGroup.addView(dateMode)
        modeGroup.addView(weeklyMode)
        modeGroup.check(weeklyMode.id)
        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeVisibility() }
        content.addView(modeGroup)

        dateButton = Button(this).apply { setOnClickListener { chooseDate() } }
        content.addView(dateButton)
        weekdaysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            DayOfWeek.entries.forEach { day ->
                val box = CheckBox(this@ScheduleActivity).apply {
                    text = day.name.take(3)
                    contentDescription = day.name.lowercase().replaceFirstChar(Char::uppercase)
                    isChecked = day == LocalDate.now().dayOfWeek
                }
                weekdayBoxes[day] = box
                addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        content.addView(weekdaysContainer)

        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startButton = Button(this).apply { setOnClickListener { chooseTime(isStart = true) } }
        val endButton = Button(this).apply { setOnClickListener { chooseTime(isStart = false) } }
        timeRow.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(endButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(timeRow)
        startTimeButton = startButton
        endTimeButton = endButton
        updateFormLabels()
        updateModeVisibility()

        actionGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        actionGroup.addView(RadioButton(this).apply {
            id = BLOCK_ACTION_ID
            text = getString(R.string.schedule_block)
        })
        actionGroup.addView(RadioButton(this).apply {
            id = ALLOW_ACTION_ID
            text = getString(R.string.schedule_allow)
        })
        actionGroup.check(BLOCK_ACTION_ID)
        content.addView(actionGroup)

        content.addView(Button(this).apply {
            text = getString(R.string.save_schedule)
            setOnClickListener { saveSchedule() }
        })
        return ScrollView(this).apply { addView(content) }
    }

    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button

    private fun loadVisibleApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        visibleApps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filterNot { it.packageName == packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    private fun chooseApps() {
        if (visibleApps.isEmpty()) return
        val pickerState = AppPickerState(visibleApps, selectedPackages)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val search = EditText(this).apply {
            hint = getString(R.string.search_apps)
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val emptyState = TextView(this).apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
        }
        val list = ListView(this).apply { dividerHeight = dp(1) }
        val icons = mutableMapOf<String, Drawable>()
        val adapter = object : BaseAdapter() {
            var query: String = ""
            private fun rows() = pickerState.filter(query)

            override fun getCount(): Int = rows().size
            override fun getItem(position: Int): InstalledApp = rows()[position]
            override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = getItem(position)
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@ScheduleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                    addView(ImageView(this@ScheduleActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(this@ScheduleActivity).apply {
                        textSize = 15f
                        setPadding(dp(12), 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(this@ScheduleActivity).apply {
                        isClickable = false
                        isFocusable = false
                    })
                }
                val icon = row.getChildAt(0) as ImageView
                val name = row.getChildAt(1) as TextView
                val check = row.getChildAt(2) as CheckBox
                icon.setImageDrawable(icons.getOrPut(app.packageName) {
                    runCatching { packageManager.getApplicationIcon(app.packageName) }
                        .getOrDefault(getDrawable(android.R.drawable.sym_def_app_icon)!!)
                })
                icon.contentDescription = getString(R.string.app_icon_accessibility, app.displayName)
                name.text = app.displayName
                check.isChecked = pickerState.isSelected(app.packageName)
                check.contentDescription = getString(R.string.schedule_app_selection_accessibility, app.displayName)
                return row
            }

            fun refresh() {
                notifyDataSetChanged()
                val hasRows = count > 0
                list.visibility = if (hasRows) View.VISIBLE else View.GONE
                emptyState.visibility = if (hasRows) View.GONE else View.VISIBLE
                emptyState.text = getString(if (query.isBlank()) R.string.app_picker_empty else R.string.app_picker_no_match)
            }

            fun toggle(position: Int) {
                val app = getItem(position)
                pickerState.setSelected(app.packageName, !pickerState.isSelected(app.packageName))
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.toggle(position)
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.query = s?.toString().orEmpty()
                adapter.refresh()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        dialogContent.addView(search)
        dialogContent.addView(emptyState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogContent.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        adapter.refresh()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_apps)
            .setView(dialogContent)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedPackages = pickerState.selectedPackages().toMutableSet()
                updateAppButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun chooseDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                updateFormLabels()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        ).show()
    }

    private fun chooseTime(isStart: Boolean) {
        val current = if (isStart) startTime else endTime
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val selectedTime = ScheduleTimeConversion.fromPickerHourOfDay(hour, minute)
                if (isStart) startTime = selectedTime else endTime = selectedTime
                updateFormLabels()
            },
            current.hour,
            current.minute,
            false,
        ).show()
    }

    private fun updateFormLabels() {
        if (::dateButton.isInitialized) {
            dateButton.text = getString(R.string.schedule_date_label, selectedDate.format(DATE_FORMAT))
        }
        if (::startTimeButton.isInitialized) {
            startTimeButton.text = getString(R.string.schedule_start_label, startTime.format(TIME_FORMAT))
            endTimeButton.text = getString(R.string.schedule_end_label, endTime.format(TIME_FORMAT))
        }
        updateAppButton()
    }

    private fun updateAppButton() {
        if (!::appSelectionButton.isInitialized) return
        val names = visibleApps.filter { it.packageName in selectedPackages }.map { it.displayName }
        appSelectionButton.text = if (names.isEmpty()) getString(R.string.choose_apps)
        else names.joinToString().take(70)
    }

    private fun updateModeVisibility() {
        if (!::dateButton.isInitialized || !::weekdaysContainer.isInitialized) return
        val isOneTime = dateMode.isChecked
        dateButton.visibility = if (isOneTime) View.VISIBLE else View.GONE
        weekdaysContainer.visibility = if (isOneTime) View.GONE else View.VISIBLE
    }

    private fun saveSchedule() {
        val name = scheduleName.text.toString().trim().ifBlank { getString(R.string.default_schedule_name) }
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, R.string.schedule_select_app, Toast.LENGTH_LONG).show()
            return
        }
        val oneTime = dateMode.isChecked
        val weekdays = if (oneTime) emptySet() else weekdayBoxes.filterValues { it.isChecked }.keys
        if (!oneTime && weekdays.isEmpty()) {
            Toast.makeText(this, R.string.schedule_select_day, Toast.LENGTH_LONG).show()
            return
        }
        val previous = scheduleStore.read()
        val schedule = runCatching {
            BlockSchedule(
                id = editingId ?: UUID.randomUUID().toString(),
                name = name,
                packageNames = selectedPackages.toSet(),
                action = if (actionGroup.checkedRadioButtonId == BLOCK_ACTION_ID) ScheduleAction.BLOCK else ScheduleAction.ALLOW,
                startTime = startTime,
                endTime = endTime,
                weekdays = weekdays,
                oneTimeDate = if (oneTime) selectedDate else null,
            )
        }.getOrElse {
            Toast.makeText(this, R.string.schedule_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val updated = previous.filterNot { it.id == schedule.id } + schedule
        scheduleStore.write(updated)
        editingId = null
        scheduleName.text.clear()
        renderSchedules()
        ScheduleCoordinator.reconcile(this)
        if (requestExactAlarmAccessIfNeeded()) return
        if (schedule.action == ScheduleAction.BLOCK && VpnService.prepare(this) != null) {
            requestScheduleEnforcement()
        }
        renderStatus()
    }

    private fun editSchedule(schedule: BlockSchedule) {
        editingId = schedule.id
        scheduleName.setText(schedule.name)
        selectedPackages = schedule.packageNames.toMutableSet()
        startTime = schedule.startTime
        endTime = schedule.endTime
        selectedDate = schedule.oneTimeDate ?: LocalDate.now()
        schedule.oneTimeDate?.let { dateMode.isChecked = true } ?: run { weeklyMode.isChecked = true }
        weekdayBoxes.forEach { (day, box) -> box.isChecked = day in schedule.weekdays }
        actionGroup.check(if (schedule.action == ScheduleAction.BLOCK) BLOCK_ACTION_ID else ALLOW_ACTION_ID)
        updateModeVisibility()
        updateFormLabels()
    }

    private fun toggleSchedule(schedule: BlockSchedule) {
        val updatedSchedule = schedule.copy(enabled = !schedule.enabled)
        scheduleStore.write(scheduleStore.read().map { if (it.id == schedule.id) updatedSchedule else it })
        ScheduleCoordinator.reconcile(this)
        renderSchedules()
        renderStatus()
        if (updatedSchedule.enabled) requestExactAlarmAccessIfNeeded()
    }

    private fun deleteSchedule(schedule: BlockSchedule) {
        scheduleStore.write(scheduleStore.read().filterNot { it.id == schedule.id })
        if (editingId == schedule.id) editingId = null
        ScheduleCoordinator.reconcile(this)
        renderSchedules()
        renderStatus()
    }

    private fun renderSchedules() {
        if (!::schedulesList.isInitialized) return
        schedulesList.removeAllViews()
        val schedules = scheduleStore.read()
        if (schedules.isEmpty()) {
            schedulesList.addView(TextView(this).apply { text = getString(R.string.no_schedules) })
            return
        }
        schedules.forEach { schedule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, padding, 0, padding)
            }
            val names = visibleApps.filter { it.packageName in schedule.packageNames }.joinToString { it.displayName }
            val recurrence = schedule.oneTimeDate?.format(DATE_FORMAT)
                ?: schedule.weekdays.sortedBy { it.value }.joinToString { it.name.take(3) }
            card.addView(TextView(this).apply {
                text = "${schedule.name} · ${schedule.action.name.lowercase()} · $recurrence · ${schedule.startTime.format(TIME_FORMAT)}–${schedule.endTime.format(TIME_FORMAT)}\n$names${if (schedule.enabled) "" else " · disabled"}"
                textSize = 15f
            })
            val buttons = LinearLayout(this@ScheduleActivity).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this@ScheduleActivity).apply {
                text = getString(if (schedule.enabled) R.string.disable_schedule else R.string.enable_schedule)
                setOnClickListener { toggleSchedule(schedule) }
            })
            buttons.addView(Button(this@ScheduleActivity).apply {
                text = getString(R.string.edit_schedule)
                setOnClickListener { editSchedule(schedule) }
            })
            buttons.addView(Button(this@ScheduleActivity).apply {
                text = getString(R.string.delete_schedule)
                setOnClickListener { deleteSchedule(schedule) }
            })
            card.addView(buttons)
            schedulesList.addView(card)
        }
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized) return
        val next = ScheduleEvaluator.nextBoundaryLabel(scheduleStore.read(), java.time.Instant.now(), java.time.ZoneId.systemDefault())
        val nextText = next?.format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.getDefault()))
            ?: getString(R.string.no_upcoming_boundary)
        val statusText = when {
            AppBlockingVpnService.isVpnActive -> getString(R.string.schedule_vpn_active_unverified)
            ScheduleRuntimeStore(this).status == ScheduleRuntimeStore.STATUS_AWAITING_VPN_CONSENT -> getString(R.string.schedule_vpn_consent_pending)
            ScheduleRuntimeStore(this).status == ScheduleRuntimeStore.STATUS_BACKGROUND_START_PENDING -> getString(R.string.schedule_background_pending)
            else -> getString(R.string.schedule_not_active)
        }
        statusView.text = "$statusText\n${getString(R.string.next_schedule_boundary, nextText)}"
    }

    private fun requestScheduleEnforcement() {
        if (requestExactAlarmAccessIfNeeded()) return
        val hasBlockingSchedule = scheduleStore.read().any { it.enabled && it.action == ScheduleAction.BLOCK }
        if (!hasBlockingSchedule && ScheduleCoordinator.effectiveBlockedPackages(this).isEmpty()) {
            Toast.makeText(this, R.string.no_block_rules_to_enable, Toast.LENGTH_LONG).show()
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

    /** Returns true when the user must visit the system's exact-alarm access screen. */
    private fun requestExactAlarmAccessIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val hasEnabledSchedule = scheduleStore.read().any { it.enabled }
        if (!hasEnabledSchedule) return false
        val alarmManager = getSystemService(android.app.AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return false
        Toast.makeText(this, R.string.schedule_exact_alarm_access_needed, Toast.LENGTH_LONG).show()
        return runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            true
        }.getOrDefault(false)
    }

    private fun continueConsentFlow() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            startActivityForResult(consentIntent, VPN_CONSENT_REQUEST)
        } else {
            ScheduleCoordinator.reconcile(this)
            renderStatus()
        }
    }

    companion object {
        private const val VPN_CONSENT_REQUEST = 2001
        private const val NOTIFICATION_PERMISSION_REQUEST = 2002
        private const val BLOCK_ACTION_ID = 2101
        private const val ALLOW_ACTION_ID = 2102
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    }
}
