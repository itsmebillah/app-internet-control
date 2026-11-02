package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class FocusModeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<FocusMode> {
        val encoded = preferences.getString(MODES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { add(decode(array.getJSONObject(index))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeMode(): FocusMode? {
        val activeId = preferences.getString(ACTIVE_MODE_KEY, null) ?: return null
        return read().firstOrNull { it.id == activeId }
    }

    fun save(mode: FocusMode) {
        val modes = read().filterNot { it.id == mode.id } + mode
        writeModes(modes)
    }

    fun activate(id: String?) {
        val validId = id?.takeIf { selectedId -> read().any { it.id == selectedId } }
        preferences.edit().putString(ACTIVE_MODE_KEY, validId).apply()
    }

    fun delete(id: String) {
        writeModes(read().filterNot { it.id == id })
        if (preferences.getString(ACTIVE_MODE_KEY, null) == id) activate(null)
    }

    private fun writeModes(modes: List<FocusMode>) {
        val array = JSONArray()
        modes.forEach { mode ->
            array.put(JSONObject().apply {
                put("id", mode.id)
                put("name", mode.name)
                put("packages", JSONArray(mode.packageNames.sorted()))
            })
        }
        preferences.edit().putString(MODES_KEY, array.toString()).apply()
    }

    private fun decode(json: JSONObject): FocusMode {
        val packages = json.getJSONArray("packages").let { values ->
            buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
        }
        return FocusMode(json.getString("id"), json.getString("name"), packages)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        private const val PREFERENCES_NAME = "focus_modes"
        private const val MODES_KEY = "modes"
        private const val ACTIVE_MODE_KEY = "active_mode_id"
    }
}
