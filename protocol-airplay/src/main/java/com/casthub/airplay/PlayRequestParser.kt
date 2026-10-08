package com.casthub.airplay

import com.casthub.airplay.plist.Plist
import org.json.JSONObject
import java.net.URI

internal object PlayRequestParser {
    fun parse(body: ByteArray, headers: Map<String, String>): Pair<String, Double>? {
        val normalizedHeaders = headers.mapKeys { it.key.lowercase() }
        val text = body.toString(Charsets.UTF_8)
        val textParameters = text.lineSequence().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val parameters: Map<String, Any?> = when {
            !normalizedHeaders["content-location"].isNullOrBlank() -> normalizedHeaders
            text.trimStart().startsWith("{") -> runCatching {
                val json = JSONObject(text)
                json.keys().asSequence().associate { it.lowercase() to json.get(it) }
            }.getOrDefault(emptyMap())
            textParameters.containsKey("content-location") -> textParameters
            else -> (Plist.parse(body) as? Map<*, *>)?.entries
                ?.associate { it.key.toString().lowercase() to it.value }.orEmpty()
        }
        val url = parameters["content-location"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val position = parameters["start-position"]?.toString()?.toDoubleOrNull() ?: 0.0
        if (!position.isFinite() || position !in 0.0..1.0) return null
        return url to position
    }
}
