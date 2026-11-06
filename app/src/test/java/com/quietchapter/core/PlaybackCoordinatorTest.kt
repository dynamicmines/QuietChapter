package com.quietchapter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCoordinatorTest {
    private fun event(time: Long, eta: Int? = 60, active: Boolean = true) =
        NavigationEvent(time, active, eta)

    private fun PlaybackCoordinator.nav(time: Long, eta: Int? = 60, active: Boolean = true) =
        onNavigation(event(time, eta, active), time)

    private fun PlaybackCoordinator.clear(): PlaybackAction {
        assertEquals(PlaybackAction.NONE, nav(8_000))
        return nav(11_000)
    }

    @Test fun `fixture produces one clustered interruption and one resume`() {
        val coordinator = PlaybackCoordinator()
        assertEquals(PlaybackAction.PLAY, coordinator.userPlay())
        val commands = SimulatedNavigationSource().frames().map {
            coordinator.onNavigation(it.event, it.offsetMs)
        }.filter { it != PlaybackAction.NONE }
        assertEquals(listOf(PlaybackAction.PAUSE, PlaybackAction.PLAY), commands)
        assertEquals("NORMAL", coordinator.state)
    }

    @Test fun `navigation never starts initially idle playback`() {
        val coordinator = PlaybackCoordinator()
        SimulatedNavigationSource().frames().forEach {
            assertEquals(PlaybackAction.NONE, coordinator.onNavigation(it.event, it.offsetMs))
        }
        assertEquals("IDLE", coordinator.state)
    }

    @Test fun `explicit pause while automatically paused cancels resume`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        assertEquals(PlaybackAction.PAUSE, coordinator.userPause())
        assertEquals(PlaybackAction.NONE, coordinator.clear())
        assertEquals("USER_PAUSED", coordinator.state)
    }

    @Test fun `focus interruption during automatic pause stays latched after route clear`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        coordinator.interrupt("Audio focus lost")
        assertEquals(PlaybackAction.NONE, coordinator.clear())
        assertEquals("INTERRUPTED", coordinator.state)
        // Focus gain alone has no coordinator command; explicit Play is required.
        assertEquals(PlaybackAction.PLAY, coordinator.userPlay())
    }

    @Test fun `play cannot clear focus interruption while route is blocked`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        coordinator.interrupt("Audio focus lost")
        assertEquals(PlaybackAction.NONE, coordinator.userPlay())
        assertEquals(PlaybackAction.NONE, coordinator.clear())
        assertEquals("INTERRUPTED", coordinator.state)
    }

    @Test fun `ended error and background states never resume from route clear`() {
        listOf("ended", "error", "background").forEach { cause ->
            val coordinator = PlaybackCoordinator()
            coordinator.userPlay()
            coordinator.nav(3_000, 5)
            if (cause == "ended") coordinator.ended() else coordinator.interrupt(cause)
            assertEquals(cause, PlaybackAction.NONE, coordinator.clear())
            assertEquals(if (cause == "ended") "ENDED" else "INTERRUPTED", coordinator.state)
        }
    }

    @Test fun `route disappearance cancels pending resume without starting playback`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        assertEquals(PlaybackAction.PAUSE, coordinator.nav(4_000, null, false))
        assertEquals(PlaybackAction.NONE, coordinator.clear())
        assertEquals("INTERRUPTED", coordinator.state)
    }

    @Test fun `unknown maneuver data cancels pending resume`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        assertEquals(PlaybackAction.PAUSE, coordinator.nav(4_000, null))
        assertEquals(PlaybackAction.NONE, coordinator.clear())
    }

    @Test fun `stale event cancels pending resume and cannot start playback`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(1_000, 5)
        assertEquals(PlaybackAction.PAUSE, coordinator.onNavigation(event(2_000), 10_000))
        assertEquals(PlaybackAction.NONE, coordinator.nav(11_000))
        assertEquals("INTERRUPTED", coordinator.state)
    }

    @Test fun `explicit play during imminent maneuver waits for stable clear`() {
        val coordinator = PlaybackCoordinator()
        coordinator.nav(3_000, 5)
        assertEquals(PlaybackAction.PAUSE, coordinator.userPlay())
        assertEquals("AUTO_PAUSED", coordinator.state)
        assertEquals(PlaybackAction.PLAY, coordinator.clear())
    }

    @Test fun `silent stream expiry cancels pending resume without further events`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        assertEquals(PlaybackAction.NONE, coordinator.onTick(8_000))
        assertEquals(PlaybackAction.PAUSE, coordinator.onTick(8_001))
        assertEquals("INTERRUPTED", coordinator.state)
        assertEquals(PlaybackAction.NONE, coordinator.nav(9_000))
        assertEquals(PlaybackAction.NONE, coordinator.nav(12_000))
        assertEquals("INTERRUPTED", coordinator.state)
    }

    @Test fun `fresh event after a silent gap does not revive pending resume`() {
        val coordinator = PlaybackCoordinator()
        coordinator.userPlay()
        coordinator.nav(3_000, 5)
        assertEquals(PlaybackAction.PAUSE, coordinator.nav(10_000))
        assertEquals(PlaybackAction.NONE, coordinator.nav(13_000))
        assertEquals("INTERRUPTED", coordinator.state)
    }

    @Test fun `seek clamps lower upper and unknown duration bounds`() {
        assertEquals(0L, clampSeekPosition(-3_000, 60_000))
        assertEquals(60_000L, clampSeekPosition(70_000, 60_000))
        assertEquals(0L, clampSeekPosition(3_000, 0))
        assertEquals(3_000L, clampSeekPosition(3_000, -1))
        assertEquals(0L, clampSeekPosition(-1, -1))
    }
}
