package com.casthub.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class HistoryEntry(val key: String, val title: String, val positionMs: Long, val durationMs: Long, val updated: Long) {
    val resumable get() = positionMs >= 5_000 && durationMs > 0 && positionMs < durationMs - 3_000
}

/** Keep exact-URL hashes, not signed URLs, headers or tokens. Bounded local history. */
class PlaybackHistory(context: Context) {
    private val prefs = context.getSharedPreferences("casthub_history", Context.MODE_PRIVATE)
    fun entries(): List<HistoryEntry> = synchronized(lock) {
        runCatching {
            val array = JSONArray(prefs.getString("entries", "[]"))
            (0 until array.length()).map { i -> array.getJSONObject(i).let {
                HistoryEntry(it.getString("key"), it.getString("title"), it.getLong("position"), it.getLong("duration"), it.getLong("updated"))
            } }
        }.getOrDefault(emptyList())
    }
    fun find(uri: String) = entries().firstOrNull { it.key == key(uri) }
    fun save(uri: String, title: String?, position: Long, duration: Long) = synchronized(lock) {
        if (duration <= 0 || position < 0) return@synchronized
        val id = key(uri)
        val entry = HistoryEntry(id, DiagnosticRedactor.redact(title ?: "未命名视频").take(160), position.coerceAtMost(duration), duration, System.currentTimeMillis())
        val array = JSONArray()
        (listOf(entry) + entries().filter { it.key != id }).take(50).forEach {
            array.put(JSONObject().put("key", it.key).put("title", it.title).put("position", it.positionMs).put("duration", it.durationMs).put("updated", it.updated))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }
    fun clear() = synchronized(lock) { prefs.edit().clear().apply() }
    companion object {
        private val lock = Any()
        fun key(uri: String): String = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
