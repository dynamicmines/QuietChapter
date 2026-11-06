package com.quietchapter.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressGuardTest {
    @Test fun `explicit small seek just before timeout gets a fresh interval`() {
        val guard = PlaybackProgressGuard()
        assertFalse(guard.stalled(true, 787, 0))
        assertFalse(guard.stalled(true, 787, 7_900))
        guard.reset() // Actual player SEEK event, including a sub-second rewind to0.
        assertFalse(guard.stalled(true, 0, 7_999))
        assertFalse(guard.stalled(true, 0, 8_000))
        assertFalse(guard.stalled(true, 0, 15_998))
        assertTrue(guard.stalled(true, 0, 15_999))
    }

    @Test fun `captured Pleos 787ms stall trips once despite playing transitions`() {
        val guard = PlaybackProgressGuard()
        // TEST_ONLY replay of pleos-volume-repeat.log; actual raw intent stayed true.
        assertFalse(guard.stalled(true, 787, 445_503))
        for (time in listOf(446_508L, 447_511L, 448_513L, 449_516L, 450_518L, 451_520L, 452_523L))
            assertFalse(guard.stalled(true, 787, time))
        assertTrue(guard.stalled(true, 787, 453_525))
        assertFalse(guard.stalled(true, 787, 454_527))
    }
    @Test fun `normal coarse clock progress never trips`() {
        val guard = PlaybackProgressGuard()
        for (seconds in 0L..120) assertFalse(guard.stalled(true, seconds * 1000, seconds * 1000))
    }
    @Test fun `manual focus navigation pause and ended intent cannot trip`() {
        val guard = PlaybackProgressGuard()
        assertFalse(guard.stalled(true, 1000, 0))
        assertFalse(guard.stalled(false, 1000, 60_000))
        assertFalse(guard.stalled(true, 1000, 61_000))
        assertFalse(guard.stalled(true, 1000, 68_999))
        assertTrue(guard.stalled(true, 1000, 69_000))
    }
    @Test fun `small timestamp jitter does not hide a stuck clock`() {
        val guard = PlaybackProgressGuard()
        assertFalse(guard.stalled(true, 787, 0))
        assertFalse(guard.stalled(true, 788, 4_000))
        assertTrue(guard.stalled(true, 786, 8_000))
    }
    @Test fun `rewind or explicit reset starts a new observation without playback`() {
        val guard = PlaybackProgressGuard()
        assertFalse(guard.stalled(true, 12_000, 0))
        assertFalse(guard.stalled(true, 9_000, 7_999))
        assertFalse(guard.stalled(true, 9_000, 8_000))
        guard.reset()
        assertFalse(guard.stalled(true, 9_000, 30_000))
    }
}
