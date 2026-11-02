package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class UserProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<UserProfile> {
        val encoded = preferences.getString(PROFILES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { add(decode(array.getJSONObject(index))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun activeProfile(): UserProfile? {
        val id = preferences.getString(ACTIVE_PROFILE_KEY, null) ?: return null
        return read().firstOrNull { it.id == id }
    }

    fun save(profile: UserProfile) {
        write(read().filterNot { it.id == profile.id } + profile)
    }

    fun replaceAll(profiles: List<UserProfile>, activeId: String?) {
        write(profiles)
        activate(activeId)
    }

    fun activate(id: String?) {
        val validId = id?.takeIf { candidate -> read().any { it.id == candidate } }
        preferences.edit().putString(ACTIVE_PROFILE_KEY, validId).apply()
    }

    fun delete(id: String) {
        write(read().filterNot { it.id == id })
        if (preferences.getString(ACTIVE_PROFILE_KEY, null) == id) activate(null)
    }

    private fun write(profiles: List<UserProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("blocked", JSONArray(profile.blockedPackageNames.sorted()))
                put("allowed", JSONArray(profile.allowedPackageNames.sorted()))
            })
        }
        preferences.edit().putString(PROFILES_KEY, array.toString()).apply()
    }

    private fun decode(json: JSONObject): UserProfile = UserProfile(
        id = json.getString("id"),
        name = json.getString("name"),
        blockedPackageNames = json.getJSONArray("blocked").toStringSet(),
        allowedPackageNames = json.getJSONArray("allowed").toStringSet(),
    )

    private fun JSONArray.toStringSet() = buildSet {
        for (index in 0 until length()) add(getString(index))
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        private const val PREFERENCES_NAME = "user_profiles"
        private const val PROFILES_KEY = "profiles"
        private const val ACTIVE_PROFILE_KEY = "active_profile_id"
    }
}
