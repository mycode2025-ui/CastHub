package com.casthub.core

/** Monotonic time only. Seeking, suspension, pause and natural end are not stalls. */
class StallDetector(private val bufferingTimeout: Long = 30_000, private val frozenTimeout: Long = 15_000) {
    private var since = -1L
    private var lastPosition = -1L
    private var lastBuffering = false
    fun reset() { since = -1; lastPosition = -1 }
    fun sample(now: Long, position: Long, buffering: Boolean, ready: Boolean,
               intendedPlay: Boolean, suppressed: Boolean, duration: Long, live: Boolean): Boolean {
        if (!intendedPlay || suppressed || (!buffering && !ready) ||
            (!live && duration > 0 && position >= duration - 1_000)) { reset(); return false }
        if (since < 0 || lastBuffering != buffering || position != lastPosition) since = now
        lastPosition = position
        lastBuffering = buffering
        if (now - since < if (buffering) bufferingTimeout else frozenTimeout) return false
        reset()
        return true
    }
}
