package com.casthub.core

object TransportActions {
    fun modeName(mode: PlaybackMode) = when (mode) {
        PlaybackMode.REPEAT_ONE -> "REPEAT_ONE"
        PlaybackMode.REPEAT_ALL -> "REPEAT_ALL"
        else -> "NORMAL"
    }
    fun parseMode(value: String): PlaybackMode? = when (value) {
        "NORMAL" -> PlaybackMode.SEQUENTIAL
        "REPEAT_ONE" -> PlaybackMode.REPEAT_ONE
        "REPEAT_ALL" -> PlaybackMode.REPEAT_ALL
        else -> null
    }
    fun available(state: PlaybackState, hasMedia: Boolean, seekable: Boolean, previous: Boolean, next: Boolean): String {
        if (!hasMedia) return ""
        return buildList {
            add("Stop")
            if (state != PlaybackState.PLAYING && state != PlaybackState.BUFFERING && state != PlaybackState.ERROR) add("Play")
            if (state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING) add("Pause")
            if (seekable && state != PlaybackState.ERROR) add("Seek")
            if (previous) add("Previous")
            if (next) add("Next")
        }.joinToString(",")
    }
}
