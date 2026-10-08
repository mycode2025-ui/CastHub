package com.casthub.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test fun reorderPreservesCurrentAndNavigation() {
        val q = PlaybackQueue()
        val a = q.onPlayed(MediaInfo("http://a/1"))
        val b = q.add(MediaInfo("http://a/2"))
        val c = q.add(MediaInfo("http://a/3"))
        assertTrue(q.move(a.id, 1))
        assertEquals(a.id, q.current?.id)
        assertEquals(c.id, q.step(1)?.id)
        assertEquals(a.id, q.step(-1)?.id)
        assertTrue(q.remove(b.id))
        assertFalse(q.remove(a.id))
        assertFalse(q.move(a.id, -1))
    }
    @Test fun endModesAndReconnect() {
        val q = PlaybackQueue()
        val a = q.onPlayed(MediaInfo("http://a/1"))
        assertEquals(a.id, q.onPlayed(MediaInfo("http://a/1", "new title")).id)
        val b = q.add(MediaInfo("http://a/2"))
        assertNull(q.onEndedHandled())
        q.mode = PlaybackMode.REPEAT_ONE
        assertEquals(a.id, q.onEndedHandled()?.id)
        q.mode = PlaybackMode.SEQUENTIAL
        assertEquals(b.id, q.onEndedHandled()?.id)
        assertNull(q.onEndedHandled())
        q.mode = PlaybackMode.REPEAT_ALL
        assertEquals(a.id, q.onEndedHandled()?.id)
        q.clear(); assertNull(q.current); assertTrue(q.isEmpty)
    }
    @Test fun nextUriIsActuallyAvailableAndBounded() {
        val q = PlaybackQueue()
        q.onPlayed(MediaInfo("http://a/0"))
        q.setNext("http://a/1", "metadata")
        q.setNext("http://a/1", "metadata")
        assertEquals(2, q.entries().size)
        repeat(98) { q.add(MediaInfo("http://a/${it+2}")) }
        assertThrows(IllegalArgumentException::class.java) { q.add(MediaInfo("http://a/101")) }
    }
    @Test fun retriesAlwaysStopAtThree() {
        val budget = RetryBudget()
        assertEquals(2000L, budget.nextDelay())
        assertEquals(4000L, budget.nextDelay())
        assertEquals(8000L, budget.nextDelay())
        assertNull(budget.nextDelay())
        budget.reset(); assertEquals(2000L, budget.nextDelay())
    }
    @Test fun subtitleOffsetsShiftStartsEndsAndGaps() {
        val cue = androidx.media3.common.text.Cue.Builder().setText("test").build()
        val times = longArrayOf(1000000, 2000000, 4000000, 5000000)
        val cues = listOf(listOf(cue), emptyList(), listOf(cue), emptyList())
        val late = ShiftedSubtitle(times, cues, 500000)
        assertTrue(late.getCues(1499999).isEmpty())
        assertEquals("test", late.getCues(1500000).single().text.toString())
        assertTrue(late.getCues(2500000).isEmpty())
        assertEquals(2, late.getNextEventTimeIndex(2500000))
        val early = ShiftedSubtitle(times, cues, -500000)
        assertEquals("test", early.getCues(500000).single().text.toString())
        assertEquals(-1, early.getNextEventTimeIndex(4500000))
    }
}
