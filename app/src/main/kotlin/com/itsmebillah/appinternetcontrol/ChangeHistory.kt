package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class ChangeSource { MANUAL, TIMER, SCHEDULE, FOCUS, OTHER }
enum class EnforcementOutcome { PENDING, NOT_ACTIVE, ACTIVE_UNVERIFIED, OVERRIDDEN }

data class ChangeEvent(
    val id: String,
    val timestamp: Instant,
    val packageName: String,
    val blocked: Boolean,
    val source: ChangeSource,
    val outcome: EnforcementOutcome,
)

data class EffectiveDecision(val blocked: Boolean, val source: ChangeSource)

object ChangeHistoryReducer {
    fun transitions(
        previous: Map<String, EffectiveDecision>,
        current: Map<String, EffectiveDecision>,
        timestamp: Instant,
        outcomeFor: (Boolean) -> EnforcementOutcome,
    ): List<ChangeEvent> = current.keys.sorted().mapNotNull { packageName ->
        val old = previous[packageName] ?: return@mapNotNull null
        val new = current.getValue(packageName)
        if (old.blocked == new.blocked) return@mapNotNull null
        ChangeEvent(
            id = "$packageName:${timestamp.toEpochMilli()}",
            timestamp = timestamp,
            packageName = packageName,
            blocked = new.blocked,
            source = if (new.blocked || new.source != ChangeSource.MANUAL) new.source else old.source,
            outcome = outcomeFor(new.blocked),
        )
    }
}

class ChangeHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<ChangeEvent> = decodeEvents(preferences.getString(EVENTS_KEY, null))

    fun append(events: List<ChangeEvent>) {
        if (events.isEmpty()) return
        val combined = (read() + events).takeLast(MAX_EVENTS)
        preferences.edit().putString(EVENTS_KEY, encodeEvents(combined)).apply()
    }

    fun clear() {
        preferences.edit().remove(EVENTS_KEY).apply()
    }

    fun readSnapshot(): Map<String, EffectiveDecision> = decodeSnapshot(preferences.getString(SNAPSHOT_KEY, null))

    fun writeSnapshot(snapshot: Map<String, EffectiveDecision>) {
        preferences.edit().putString(SNAPSHOT_KEY, encodeSnapshot(snapshot)).putBoolean(SNAPSHOT_INITIALIZED_KEY, true).apply()
    }

    fun hasSnapshot(): Boolean = preferences.getBoolean(SNAPSHOT_INITIALIZED_KEY, false)

    companion object {
        private const val PREFERENCES_NAME = "change_history"
        private const val EVENTS_KEY = "events"
        private const val SNAPSHOT_KEY = "effective_snapshot"
        private const val SNAPSHOT_INITIALIZED_KEY = "snapshot_initialized"
        private const val MAX_EVENTS = 500

        internal fun encodeEvents(events: List<ChangeEvent>): String = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id)
                    put("at", event.timestamp.toEpochMilli())
                    put("package", event.packageName)
                    put("blocked", event.blocked)
                    put("source", event.source.name)
                    put("outcome", event.outcome.name)
                })
            }
        }.toString()

        internal fun decodeEvents(encoded: String?): List<ChangeEvent> = runCatching {
            val array = JSONArray(encoded ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    runCatching {
                        val item = array.getJSONObject(index)
                        add(ChangeEvent(
                            id = item.getString("id"),
                            timestamp = Instant.ofEpochMilli(item.getLong("at")),
                            packageName = item.getString("package"),
                            blocked = item.getBoolean("blocked"),
                            source = sourceOrOther(item.getString("source")),
                            outcome = EnforcementOutcome.valueOf(item.getString("outcome")),
                        ))
                    }
                }
            }
        }.getOrDefault(emptyList())

        internal fun encodeSnapshot(snapshot: Map<String, EffectiveDecision>): String = JSONObject().apply {
            snapshot.forEach { (packageName, decision) ->
                put(packageName, JSONObject().apply {
                    put("blocked", decision.blocked)
                    put("source", decision.source.name)
                })
            }
        }.toString()

        internal fun decodeSnapshot(encoded: String?): Map<String, EffectiveDecision> = runCatching {
            val json = JSONObject(encoded ?: "{}")
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    runCatching {
                        val decision = json.getJSONObject(packageName)
                        put(packageName, EffectiveDecision(
                            blocked = decision.getBoolean("blocked"),
                            source = sourceOrOther(decision.getString("source")),
                        ))
                    }
                }
            }
        }.getOrDefault(emptyMap())

        private fun sourceOrOther(value: String): ChangeSource =
            runCatching { ChangeSource.valueOf(value) }.getOrDefault(ChangeSource.OTHER)
    }
}
