package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class BlockScheduleStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<BlockSchedule> {
        val encoded = preferences.getString(SCHEDULES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { add(decode(array.getJSONObject(index))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun write(schedules: List<BlockSchedule>) {
        val array = JSONArray()
        schedules.forEach { schedule -> array.put(encode(schedule)) }
        preferences.edit().putString(SCHEDULES_KEY, array.toString()).apply()
    }

    private fun encode(schedule: BlockSchedule) = JSONObject().apply {
        put("id", schedule.id)
        put("name", schedule.name)
        put("packages", JSONArray(schedule.packageNames.sorted()))
        put("action", schedule.action.name)
        put("startMinute", schedule.startTime.toSecondOfDay() / 60)
        put("endMinute", schedule.endTime.toSecondOfDay() / 60)
        put("weekdays", JSONArray(schedule.weekdays.map { it.value }.sorted()))
        put("oneTimeDate", schedule.oneTimeDate?.toString())
        put("enabled", schedule.enabled)
    }

    private fun decode(json: JSONObject): BlockSchedule {
        val packages = json.getJSONArray("packages").let { values ->
            buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
        }
        val weekdays = json.getJSONArray("weekdays").let { values ->
            buildSet { for (index in 0 until values.length()) add(DayOfWeek.of(values.getInt(index))) }
        }
        val date = json.optString("oneTimeDate").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
        return BlockSchedule(
            id = json.getString("id"),
            name = json.getString("name"),
            packageNames = packages,
            action = ScheduleAction.valueOf(json.getString("action")),
            startTime = LocalTime.ofSecondOfDay(json.getInt("startMinute") * 60L),
            endTime = LocalTime.ofSecondOfDay(json.getInt("endMinute") * 60L),
            weekdays = weekdays,
            oneTimeDate = date,
            enabled = json.optBoolean("enabled", true),
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "block_schedules"
        private const val SCHEDULES_KEY = "scheduled_rules"
    }
}
