package com.itsmebillah.appinternetcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class TemporaryBlockStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<TemporaryBlock> {
        val encoded = preferences.getString(BLOCKS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { add(decode(array.getJSONObject(index))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun write(blocks: List<TemporaryBlock>) {
        val array = JSONArray()
        blocks.forEach { array.put(encode(it)) }
        preferences.edit().putString(BLOCKS_KEY, array.toString()).apply()
    }

    fun expire(now: Instant): List<TemporaryBlock> {
        val current = read()
        val remaining = current.filter { it.expiresAt > now }
        if (remaining.size != current.size) write(remaining)
        return remaining
    }

    private fun encode(block: TemporaryBlock) = JSONObject().apply {
        put("id", block.id)
        put("packages", JSONArray(block.packageNames.sorted()))
        put("startedAt", block.startedAt.toEpochMilli())
        put("expiresAt", block.expiresAt.toEpochMilli())
    }

    private fun decode(json: JSONObject): TemporaryBlock {
        val packages = json.getJSONArray("packages").let { values ->
            buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
        }
        return TemporaryBlock(
            id = json.getString("id"),
            packageNames = packages,
            startedAt = Instant.ofEpochMilli(json.getLong("startedAt")),
            expiresAt = Instant.ofEpochMilli(json.getLong("expiresAt")),
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "temporary_blocks"
        private const val BLOCKS_KEY = "active_blocks"
    }
}
