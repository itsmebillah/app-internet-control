package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WifiAccessRuleStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<WifiAccessRule> = synchronized(STORE_LOCK) { readUnlocked() }

    /** Adds against the latest persisted list, preserving concurrent rule edits in this process. */
    fun add(rule: WifiAccessRule): Boolean = synchronized(STORE_LOCK) {
        persist(readUnlocked() + rule)
    }

    /** Sets an explicit state against the latest list; safe to repeat and does not toggle stale state. */
    fun setEnabled(ruleId: String, enabled: Boolean): Boolean = synchronized(STORE_LOCK) {
        val current = readUnlocked()
        if (current.none { it.id == ruleId }) return@synchronized false
        persist(WifiAccessRuleMutations.setEnabled(current, ruleId, enabled))
    }

    /** Deletes only the identified rule from the latest persisted list. */
    fun delete(ruleId: String): Boolean = synchronized(STORE_LOCK) {
        val current = readUnlocked()
        if (current.none { it.id == ruleId }) return@synchronized false
        persist(current.filterNot { it.id == ruleId })
    }

    private fun readUnlocked(): List<WifiAccessRule> {
        val encoded = preferences.getString(RULES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { add(WifiAccessRuleCodec.decode(array.getJSONObject(index))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(rules: List<WifiAccessRule>): Boolean {
        val array = JSONArray().apply { rules.forEach { put(WifiAccessRuleCodec.encode(it)) } }
        return preferences.edit().putString(RULES_KEY, array.toString()).commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "wifi_access_rules"
        private const val RULES_KEY = "rules"
        private val STORE_LOCK = Any()
    }
}

object WifiAccessRuleMutations {
    fun setEnabled(rules: List<WifiAccessRule>, ruleId: String, enabled: Boolean): List<WifiAccessRule> =
        rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
}

internal object WifiAccessRuleCodec {
    fun encode(rule: WifiAccessRule) = JSONObject().apply {
        put("id", rule.id)
        put("networkId", rule.network.networkId)
        put("ssid", rule.network.ssid)
        rule.network.securityType?.let { put("securityType", it) }
        put("packages", JSONArray(rule.packageNames.sorted()))
        put("enabled", rule.enabled)
    }

    fun decode(json: JSONObject): WifiAccessRule {
        val packages = json.getJSONArray("packages").let { values ->
            buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
        }
        val network = WifiNetworkIdentity(
            networkId = json.getInt("networkId"),
            ssid = json.getString("ssid"),
            securityType = if (json.has("securityType") && !json.isNull("securityType")) json.getInt("securityType") else null,
        )
        return WifiAccessRule(
            id = json.getString("id"),
            network = network,
            packageNames = packages,
            enabled = json.optBoolean("enabled", true),
        )
    }
}
