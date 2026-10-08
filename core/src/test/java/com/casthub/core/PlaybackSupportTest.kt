package com.casthub.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackSupportTest {
    @Test fun bufferingHasBoundedTimeout() {
        val detector = StallDetector()
        assertFalse(detector.sample(0, 5000, true, false, true, false, 60000, false))
        assertFalse(detector.sample(29999, 5000, true, false, true, false, 60000, false))
        assertTrue(detector.sample(30000, 5000, true, false, true, false, 60000, false))
        assertFalse(detector.sample(30001, 5000, true, false, true, false, 60000, false))
    }
    @Test fun freezeDetectedButPauseSuppressionSeekAndEndExcluded() {
        val detector = StallDetector()
        assertFalse(detector.sample(0, 2000, false, true, true, false, 60000, false))
        assertTrue(detector.sample(15000, 2000, false, true, true, false, 60000, false))
        assertFalse(detector.sample(16000, 2000, true, false, false, false, 60000, false))
        assertFalse(detector.sample(100000, 2000, true, false, false, false, 60000, false))
        assertFalse(detector.sample(100001, 2000, false, true, true, true, 60000, false))
        assertFalse(detector.sample(100002, 2000, false, true, true, false, 60000, false))
        assertFalse(detector.sample(110000, 12000, false, true, true, false, 60000, false))
        assertFalse(detector.sample(120000, 12000, false, true, true, false, 60000, false))
        assertFalse(detector.sample(140000, 60000, false, true, true, false, 60000, false))
    }
    @Test fun liveBufferingCanRecoverWithoutKnownDuration() {
        val detector = StallDetector()
        assertFalse(detector.sample(0, 0, true, false, true, false, -1, true))
        assertTrue(detector.sample(30000, 0, true, false, true, false, -1, true))
    }
    @Test fun transportModesAndActionsAreTruthful() {
        assertEquals(PlaybackMode.REPEAT_ONE, TransportActions.parseMode("REPEAT_ONE"))
        assertEquals(PlaybackMode.SEQUENTIAL, TransportActions.parseMode("NORMAL"))
        assertNull(TransportActions.parseMode("SHUFFLE"))
        assertEquals("", TransportActions.available(PlaybackState.IDLE, false, false, false, false))
        assertEquals("Stop,Pause,Seek,Next", TransportActions.available(PlaybackState.PLAYING, true, true, false, true))
        assertEquals("Stop,Play,Previous", TransportActions.available(PlaybackState.PAUSED, true, false, true, false))
        assertEquals("Stop,Next", TransportActions.available(PlaybackState.ERROR, true, true, false, true))
    }
    @Test fun historyUsesExactUrlHashAndResumableBounds() {
        val key = PlaybackHistory.key("https://example/video?token=secret")
        assertEquals(64, key.length)
        assertFalse(key.contains("secret"))
        assertNotEquals(key, PlaybackHistory.key("https://example/video?token=new"))
        assertFalse(HistoryEntry(key, "Test", 4999, 30000, 0).resumable)
        assertTrue(HistoryEntry(key, "Test", 10000, 30000, 0).resumable)
        assertFalse(HistoryEntry(key, "Test", 28000, 30000, 0).resumable)
    }
}
