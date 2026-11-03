package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Only selected settings, user profiles, schedules and focus modes are backed up. */
object CloudBackupSnapshot {
    fun capture(context: Context): JSONObject {
        val profiles = UserProfileStore(context)
        val focus = FocusModeStore(context)
        val settings = JSONObject()
            .put("backupSchemaVersion", BACKUP_SCHEMA_VERSION)
            .put("blockedPackages", JSONArray(AppSelectionStore(context).read().packageNames.sorted()))
            .put("activeProfileId", profiles.activeProfile()?.id)
            .put("activeFocusModeId", focus.activeMode()?.id)
        val profileRows = JSONArray().apply {
            profiles.read().sortedBy { it.id }.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("name", profile.name)
                    .put("blocked", JSONArray(profile.blockedPackageNames.sorted()))
                    .put("allowed", JSONArray(profile.allowedPackageNames.sorted())))
            }
        }
        val scheduleRows = JSONArray().apply {
            BlockScheduleStore(context).read().sortedBy { it.id }.forEach { schedule ->
                put(JSONObject().put("id", schedule.id).put("name", schedule.name)
                    .put("packages", JSONArray(schedule.packageNames.sorted())).put("action", schedule.action.name)
                    .put("startMinute", schedule.startTime.toSecondOfDay() / 60)
                    .put("endMinute", schedule.endTime.toSecondOfDay() / 60)
                    .put("weekdays", JSONArray(schedule.weekdays.map { it.value }.sorted()))
                    .put("oneTimeDate", schedule.oneTimeDate?.toString()).put("enabled", schedule.enabled))
            }
        }
        val focusRows = JSONArray().apply {
            focus.read().sortedBy { it.id }.forEach { mode ->
                put(JSONObject().put("id", mode.id).put("name", mode.name)
                    .put("packages", JSONArray(mode.packageNames.sorted())))
            }
        }
        return JSONObject().put("settings", settings)
            .put("profiles", JSONObject().put("items", profileRows))
            .put("schedules", JSONObject().put("items", scheduleRows))
            .put("focusModes", JSONObject().put("items", focusRows))
    }

    fun validate(snapshot: JSONObject) {
        prepareRestore(snapshot)
    }

    fun restore(context: Context, snapshot: JSONObject) {
        val restored = prepareRestore(snapshot)
        AppSelectionStore(context).write(restored.selection)
        UserProfileStore(context).replaceAll(restored.profiles, restored.activeProfileId)
        BlockScheduleStore(context).write(restored.schedules)
        FocusModeStore(context).replaceAll(restored.focusModes, restored.activeFocusModeId)
        ScheduleCoordinator.reconcile(context)
    }

    internal fun prepareRestore(snapshot: JSONObject): BackupRestorePlan {
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        require(settings.optInt("backupSchemaVersion") == BACKUP_SCHEMA_VERSION) {
            "This backup uses an unsupported data format. Local data was not changed."
        }
        val restoredSelection = AppSelection.of(settings.optJSONArray("blockedPackages").toStringSet())

        val profiles = snapshot.optJSONObject("profiles")?.optJSONArray("items").orEmptyArray()
        val restoredProfiles = (0 until profiles.length()).map { index ->
            val item = profiles.getJSONObject(index)
            UserProfile(item.getString("id"), item.getString("name"),
                item.optJSONArray("blocked").toStringSet(), item.optJSONArray("allowed").toStringSet())
        }
        val activeProfileId = settings.optString("activeProfileId").takeIf { it.isNotBlank() && it != "null" }

        val schedules = snapshot.optJSONObject("schedules")?.optJSONArray("items").orEmptyArray()
        val restoredSchedules = (0 until schedules.length()).map { index ->
            val item = schedules.getJSONObject(index)
            val date = item.optString("oneTimeDate").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
            BlockSchedule(
                id = item.getString("id"), name = item.getString("name"),
                packageNames = item.optJSONArray("packages").toStringSet(),
                action = ScheduleAction.valueOf(item.getString("action")),
                startTime = LocalTime.ofSecondOfDay(item.getInt("startMinute") * 60L),
                endTime = LocalTime.ofSecondOfDay(item.getInt("endMinute") * 60L),
                weekdays = item.optJSONArray("weekdays").let { values ->
                    buildSet { for (day in 0 until (values?.length() ?: 0)) add(DayOfWeek.of(values!!.getInt(day))) }
                },
                oneTimeDate = date,
                enabled = item.optBoolean("enabled", true),
            )
        }

        val modes = snapshot.optJSONObject("focusModes")?.optJSONArray("items").orEmptyArray()
        val restoredModes = (0 until modes.length()).map { index ->
            val item = modes.getJSONObject(index)
            FocusMode(item.getString("id"), item.getString("name"), item.optJSONArray("packages").toStringSet())
        }
        val activeFocusModeId = settings.optString("activeFocusModeId").takeIf { it.isNotBlank() && it != "null" }

        require(activeProfileId == null || restoredProfiles.any { it.id == activeProfileId }) {
            "Backup references a profile that is missing. Local data was not changed."
        }
        require(activeFocusModeId == null || restoredModes.any { it.id == activeFocusModeId }) {
            "Backup references a focus mode that is missing. Local data was not changed."
        }
        return BackupRestorePlan(restoredSelection, restoredProfiles, activeProfileId,
            restoredSchedules, restoredModes, activeFocusModeId)
    }

    internal data class BackupRestorePlan(
        val selection: AppSelection,
        val profiles: List<UserProfile>,
        val activeProfileId: String?,
        val schedules: List<BlockSchedule>,
        val focusModes: List<FocusMode>,
        val activeFocusModeId: String?,
    )

    private fun JSONArray?.orEmptyArray() = this ?: JSONArray()

    private fun JSONArray?.toStringSet(): Set<String> = this?.let { values ->
        buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
    }.orEmpty()

    private const val BACKUP_SCHEMA_VERSION = 1
}
