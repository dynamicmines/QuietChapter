package com.quietchapter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptionReliabilityTest {
    @Test fun `completion clears raw player intent once so a seek cannot restart it`() {
        val c = PlaybackCoordinator()
        c.userPlay()
        assertEquals(PlaybackAction.PAUSE, c.ended())
        assertEquals(PlaybackAction.NONE, c.ended())
        c.onNavigation(NavigationEvent(1_000, true, 5), 1_000)
        c.onNavigation(NavigationEvent(4_000, true, 60), 4_000)
        assertEquals(PlaybackAction.NONE, c.onNavigation(NavigationEvent(7_000, true, 60), 7_000))
    }
    @Test fun `repeated play while automatically paused does not duplicate pause actions`() {
        val c = PlaybackCoordinator()
        c.onNavigation(NavigationEvent(1_000, true, 5), 1_000)
        assertEquals(PlaybackAction.PAUSE, c.userPlay())
        repeat(50) { assertEquals(PlaybackAction.NONE, c.userPlay()) }
        assertEquals("AUTO_PAUSED", c.state)
    }

    @Test fun `repeated play and pause commands are idempotent`() {
        val c = PlaybackCoordinator()
        assertEquals(PlaybackAction.PLAY, c.userPlay())
        assertEquals(PlaybackAction.NONE, c.userPlay())
        assertEquals(PlaybackAction.PAUSE, c.userPause())
        assertEquals(PlaybackAction.NONE, c.userPause())
    }

    @Test fun `late focus callbacks preserve manual pause and emit no duplicate pauses`() {
        val c = PlaybackCoordinator()
        c.userPlay()
        c.userPause()
        repeat(50) { assertEquals(PlaybackAction.NONE, c.interrupt("Focus suppressed")) }
        assertEquals("USER_PAUSED", c.state)
        c.onNavigation(NavigationEvent(1_000, true, 5), 1_000)
        c.onNavigation(NavigationEvent(5_000, true, 60), 5_000)
        assertEquals(PlaybackAction.NONE, c.onNavigation(NavigationEvent(8_000, true, 60), 8_000))
    }

    @Test fun `duplicate interruption and late callback after completion are harmless`() {
        val c = PlaybackCoordinator()
        c.userPlay()
        assertEquals(PlaybackAction.PAUSE, c.interrupt("Focus lost"))
        assertEquals(PlaybackAction.NONE, c.interrupt("Focus lost"))
        c.ended()
        assertEquals(PlaybackAction.NONE, c.interrupt("Late focus callback"))
        assertEquals("ENDED", c.state)
    }

    @Test fun `both event orders stay paused across repeated clear events`() {
        for (navigationFirst in listOf(true, false)) {
            for (manual in listOf(true, false)) {
                val c = PlaybackCoordinator()
                c.userPlay()
                if (!navigationFirst) c.interrupt("Focus lost")
                c.onNavigation(NavigationEvent(1_000, true, 5), 1_000)
                if (navigationFirst) c.interrupt("Focus lost")
                if (manual) c.userPause()
                for (time in 2_000L..20_000L step 1_000) {
                    assertEquals(PlaybackAction.NONE, c.onNavigation(NavigationEvent(time, true, 60), time))
                }
                assertEquals(if (manual) "USER_PAUSED" else "INTERRUPTED", c.state)
            }
        }
    }

    @Test fun `duplicate clear events issue exactly one resume`() {
        val c = PlaybackCoordinator()
        c.userPlay()
        c.onNavigation(NavigationEvent(1_000, true, 5), 1_000)
        c.onNavigation(NavigationEvent(4_000, true, 60), 4_000)
        assertEquals(PlaybackAction.PLAY, c.onNavigation(NavigationEvent(7_000, true, 60), 7_000))
        repeat(100) {
            assertEquals(PlaybackAction.NONE, c.onNavigation(NavigationEvent(7_000, true, 60), 7_000))
        }
    }

    @Test fun `new imminent event immediately after resume does not cause flutter`() {
        val p = RoadContextPolicy()
        assertEquals(RoadContextIntent.PAUSE, p.evaluate(NavigationEvent(0, true, 5), 0))
        p.evaluate(NavigationEvent(3_000, true, 60), 3_000)
        assertEquals(RoadContextIntent.RESUME, p.evaluate(NavigationEvent(5_000, true, 60), 5_000))
        assertEquals(RoadContextIntent.NORMAL, p.evaluate(NavigationEvent(5_001, true, 5), 5_001))
        assertEquals(RoadContextIntent.NORMAL, p.evaluate(NavigationEvent(9_999, true, 5), 9_999))
        assertEquals(RoadContextIntent.PAUSE, p.evaluate(NavigationEvent(10_000, true, 5), 10_000))
    }
}
