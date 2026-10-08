package com.casthub.core

import java.util.UUID

enum class PlaybackMode(val label: String) {
    SINGLE("单曲播放"), REPEAT_ONE("单曲循环"), SEQUENTIAL("队列顺序播放"), REPEAT_ALL("队列循环");
    companion object {
        fun fromName(name: String?): PlaybackMode = entries.firstOrNull { it.name == name } ?: SINGLE
    }
}

/** Local list: only received URLs and explicitly added items, never an invented phone playlist. */
class PlaybackQueue {
    data class Entry(val media: MediaInfo, val metaXml: String = "", val id: String = UUID.randomUUID().toString())
    private val items = mutableListOf<Entry>()
    private var currentId: String? = null
    var mode = PlaybackMode.SINGLE
    val isEmpty: Boolean @Synchronized get() = items.isEmpty()
    val current: Entry? @Synchronized get() = items.firstOrNull { it.id == currentId }
    @Synchronized fun entries(): List<Entry> = items.toList()
    @Synchronized fun add(media: MediaInfo, metaXml: String = ""): Entry {
        require(items.size < 100) { "队列最多 100 条" }
        return Entry(media, metaXml).also(items::add)
    }
    @Synchronized fun onPlayed(media: MediaInfo, metaXml: String = ""): Entry {
        val index = items.indexOfFirst { it.media.uri == media.uri }
        val entry = if (index >= 0) items[index].copy(media = media, metaXml = metaXml).also { items[index] = it }
            else {
                if (items.size >= 100) items.firstOrNull { it.id != currentId }?.let { items.remove(it) }
                add(media, metaXml)
            }
        currentId = entry.id
        return entry
    }
    @Synchronized fun select(id: String): Entry? = items.firstOrNull { it.id == id }?.also {
        currentId = id
        if (it.media.uri == nextUri) { nextUri = ""; nextMetaXml = "" }
    }
    @Synchronized fun step(delta: Int): Entry? {
        val index = items.indexOfFirst { it.id == currentId }
        val target = index + delta
        return if (index >= 0 && target in items.indices) select(items[target].id) else null
    }
    @Synchronized fun remove(id: String): Boolean {
        if (id == currentId) return false // Caller must stop or switch first.
        val removed = items.firstOrNull { it.id == id } ?: return false
        if (removed.media.uri == nextUri) { nextUri = ""; nextMetaXml = "" }
        return items.remove(removed)
    }
    @Synchronized fun move(id: String, delta: Int): Boolean {
        val index = items.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in items.indices) return false
        items.add(target, items.removeAt(index))
        return true
    }
    @Volatile var nextUri = ""
        private set
    @Volatile var nextMetaXml = ""
        private set
    @Synchronized fun setNext(uri: String, metaXml: String) {
        nextUri = uri
        nextMetaXml = metaXml
        if (uri.isNotBlank() && items.none { it.media.uri == uri }) add(MediaInfo(uri), metaXml)
    }
    @Synchronized fun onEndedHandled(): Entry? = when (mode) {
        PlaybackMode.SINGLE -> null
        PlaybackMode.REPEAT_ONE -> current
        PlaybackMode.SEQUENTIAL -> step(1)
        PlaybackMode.REPEAT_ALL -> step(1) ?: items.firstOrNull()?.let { select(it.id) }
    }
    @Synchronized fun clear() { items.clear(); currentId = null; nextUri = ""; nextMetaXml = "" }
    @Synchronized fun summary(): String = "${mode.label}：${items.indexOfFirst { it.id == currentId } + 1}/${items.size}"
}
