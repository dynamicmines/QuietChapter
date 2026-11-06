package com.quietchapter.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** All traces are SIMULATED. Source time and receipt time share a monotonic clock. */
class NavigationResilienceTest {
    private fun event(time: Long, eta: Int? = 60, generation: Long = 0,
        signal: NavigationSignal = NavigationSignal.UPDATE, active: Boolean = true) =
        NavigationEvent(time, active, eta, routeGeneration = generation, signal = signal)

    private class Trace {
        val c = PlaybackCoordinator()
        val actions = mutableListOf<PlaybackAction>()
        init { assertEquals(PlaybackAction.PLAY, c.userPlay()) }
        fun at(received: Long, event: NavigationEvent, expected: PlaybackAction = PlaybackAction.NONE,
            state: String? = null) {
            val actual = c.onNavigation(event, received)
            actions += actual
            println("SIMULATED received=$received event=$event expected=$expected actual=$actual state=${c.state} health=${c.navigationHealth}")
            assertEquals("At $received: $event", expected, actual)
            state?.let { assertEquals(it, c.state) }
        }
    }

    @Test fun `delayed batch cannot compress clear observation into one millisecond`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(4_000, event(1_000))
        t.at(4_001, event(3_000), state = "AUTO_PAUSED")
        t.at(6_000, event(6_000), PlaybackAction.PLAY, "NORMAL")
        assertEquals(1, t.actions.count { it == PlaybackAction.PLAY })
    }

    @Test fun `conflicting duplicate breaks clear evidence`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(1_001, event(1_000, 3))
        t.at(3_000, event(3_000), state = "AUTO_PAUSED")
        t.at(5_000, event(5_000), PlaybackAction.PLAY)
    }

    @Test fun `malformed and future inputs each break clear evidence`() {
        for (invalid in listOf(event(2_000, -1), event(8_000), event(-1), event(2_000, generation = -1))) {
            val t = Trace()
            t.at(0, event(0, 5), PlaybackAction.PAUSE)
            t.at(1_000, event(1_000))
            t.at(2_000, invalid)
            t.at(3_000, event(3_000), state = "AUTO_PAUSED")
            t.at(5_000, event(5_000), PlaybackAction.PLAY)
        }
    }

    @Test fun `reordered current route data breaks clear span without playing`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(2_000, event(2_000))
        t.at(3_000, event(1_000, 5))
        t.at(4_000, event(4_000), state = "AUTO_PAUSED")
        t.at(6_000, event(6_000), PlaybackAction.PLAY)
    }

    @Test fun `exact duplicate storm neither extends freshness nor starts playback`() {
        val t = Trace()
        val pause = event(0, 5)
        t.at(0, pause, PlaybackAction.PAUSE)
        repeat(100) { t.at(50L * it + 1, pause) }
        assertEquals(PlaybackAction.PAUSE, t.c.onTick(5_001))
        assertEquals("EXPIRED", t.c.navigationHealth)
        t.at(6_000, event(6_000))
        t.at(8_000, event(8_000), state = "INTERRUPTED")
        assertEquals(0, t.actions.count { it == PlaybackAction.PLAY })
    }

    @Test fun `missing clear update expires pending resume instead of reviving it`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(6_001, event(6_001), PlaybackAction.PAUSE, "INTERRUPTED")
        t.at(8_001, event(8_001), state = "INTERRUPTED")
    }

    @Test fun `late stale packet invalidates pending resume`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(4_000, event(4_000))
        t.at(10_001, event(4_500), PlaybackAction.PAUSE, "INTERRUPTED")
        t.at(11_000, event(11_000))
        t.at(13_000, event(13_000), state = "INTERRUPTED")
    }

    @Test fun `unknown data cannot bridge a clear span or auto restart`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(2_000, event(2_000, null), PlaybackAction.PAUSE, "INTERRUPTED")
        t.at(3_000, event(3_000))
        t.at(5_000, event(5_000), state = "INTERRUPTED")
    }

    @Test fun `reroute cancellation and disconnection require explicit recovery`() {
        for (signal in listOf(NavigationSignal.REROUTING, NavigationSignal.CANCELLED, NavigationSignal.DISCONNECTED)) {
            val t = Trace()
            t.at(0, event(0, 5), PlaybackAction.PAUSE)
            t.at(1_000, event(1_000))
            t.at(2_000, event(2_000, signal = signal), PlaybackAction.PAUSE, "INTERRUPTED")
            t.at(3_000, event(3_000))
            t.at(5_000, event(5_000), state = "INTERRUPTED")
            assertEquals(PlaybackAction.PLAY, t.c.userPlay())
        }
    }

    @Test fun `new route cannot inherit old route resume permission`() {
        for (eta in listOf(5, 60)) {
            val t = Trace()
            t.at(0, event(0, 5), PlaybackAction.PAUSE)
            t.at(1_000, event(1_000))
            t.at(3_000, event(3_000, eta, 1), PlaybackAction.PAUSE, "INTERRUPTED")
            t.at(4_000, event(4_000, generation = 1))
            t.at(6_000, event(6_000, generation = 1), state = "INTERRUPTED")
        }
    }

    @Test fun `late prior route packets cannot cancel or clear current route`() {
        val t = Trace()
        t.at(0, event(0, 5, 1), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000, generation = 1))
        t.at(2_000, event(2_000, generation = 0, signal = NavigationSignal.CANCELLED))
        t.at(3_000, event(3_000, generation = 1), PlaybackAction.PLAY)
    }

    @Test fun `successive maneuvers group into exactly one pause and resume`() {
        val t = Trace()
        t.at(0, event(0, 7), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000, 3))
        t.at(2_000, event(2_000))
        t.at(3_000, event(3_000, 6))
        t.at(4_000, event(4_000, 2))
        t.at(5_000, event(5_000))
        t.at(6_999, event(6_999))
        t.at(7_000, event(7_000), PlaybackAction.PLAY)
        assertEquals(listOf(PlaybackAction.PAUSE, PlaybackAction.PLAY), t.actions.filter { it != PlaybackAction.NONE })
    }

    @Test fun `unavailable data cannot bypass five second listening cooldown`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(3_000, event(3_000), PlaybackAction.PLAY)
        t.at(3_500, event(3_500, null))
        t.at(3_600, event(3_600, 5), state = "NORMAL")
        t.at(7_999, event(7_999, 4))
        t.at(8_000, event(8_000, 3), PlaybackAction.PAUSE)
    }

    @Test fun `manual pause suppresses resume and cannot create phantom cooldown`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.c.userPause()
        t.at(1_000, event(1_000))
        t.at(3_000, event(3_000), state = "USER_PAUSED")
        t.at(3_001, event(3_001, 4))
        assertEquals(PlaybackAction.PAUSE, t.c.userPlay())
        assertEquals("AUTO_PAUSED", t.c.state)
    }

    @Test fun `manual pause survives every source failure and event order`() {
        for (manualFirst in listOf(true, false)) for (signal in NavigationSignal.entries) {
            val t = Trace()
            t.at(0, event(0, 5), PlaybackAction.PAUSE)
            if (manualFirst) t.c.userPause()
            t.at(1_000, event(1_000, signal = signal),
                if (!manualFirst && signal != NavigationSignal.UPDATE) PlaybackAction.PAUSE else PlaybackAction.NONE)
            if (!manualFirst) t.c.userPause()
            t.at(2_000, event(2_000))
            t.at(4_000, event(4_000), state = "USER_PAUSED")
        }
    }

    @Test fun `focus interruption survives reconnect and route changes in both orders`() {
        for (focusFirst in listOf(true, false)) {
            val t = Trace()
            if (focusFirst) t.c.interrupt("Focus unavailable")
            t.at(0, event(0, 5), if (focusFirst) PlaybackAction.NONE else PlaybackAction.PAUSE)
            if (!focusFirst) t.c.interrupt("Focus unavailable")
            t.at(1_000, event(1_000, signal = NavigationSignal.DISCONNECTED))
            t.at(2_000, event(2_000, generation = 1))
            t.at(4_000, event(4_000, generation = 1), state = "INTERRUPTED")
        }
    }

    @Test fun `ordinary playback exposes expiry without inventing a pause or resume`() {
        val t = Trace()
        t.at(0, event(0))
        assertEquals(PlaybackAction.NONE, t.c.onTick(5_001))
        assertEquals("EXPIRED", t.c.navigationHealth)
        assertEquals("NORMAL", t.c.state)
        t.at(6_000, event(6_000, signal = NavigationSignal.DISCONNECTED))
        t.at(8_000, event(8_000), state = "NORMAL")
        assertEquals(0, t.actions.count { it != PlaybackAction.NONE })
    }

    @Test fun `cold idle and completed playback never start from recovery events`() {
        for (completed in listOf(true, false)) {
            val c = PlaybackCoordinator()
            if (completed) { c.userPlay(); c.ended() }
            val events = listOf(event(0, 5), event(1_000, signal = NavigationSignal.REROUTING),
                event(2_000, generation = 1), event(4_000, generation = 1))
            events.forEach { assertEquals(PlaybackAction.NONE, c.onNavigation(it, it.elapsedMs)) }
            assertEquals(if (completed) "ENDED" else "IDLE", c.state)
        }
    }
    @Test fun `new explicit intent cannot bypass a cooldown-suppressed imminent maneuver`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(3_000, event(3_000), PlaybackAction.PLAY)
        t.at(3_001, event(3_001, 4))
        t.c.userPause()
        assertEquals(PlaybackAction.PAUSE, t.c.userPlay())
        assertEquals("AUTO_PAUSED", t.c.state)
    }

    @Test fun `explicit recovery starts a new intent without inheriting listening cooldown`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(3_000, event(3_000), PlaybackAction.PLAY)
        t.c.userPause()
        assertEquals(PlaybackAction.PLAY, t.c.userPlay())
        t.at(3_001, event(3_001, 5), PlaybackAction.PAUSE)
    }

    @Test fun `lifecycle loss at tied or reordered timestamps still invalidates intent`() {
        for (signal in listOf(NavigationSignal.REROUTING, NavigationSignal.CANCELLED, NavigationSignal.DISCONNECTED)) {
            for (time in listOf(1_000L, 500L)) {
                val t = Trace()
                t.at(0, event(0, 5), PlaybackAction.PAUSE)
                t.at(1_000, event(1_000))
                t.at(1_000, event(time, signal = signal), PlaybackAction.PAUSE, "INTERRUPTED")
                t.at(2_000, event(2_000))
                t.at(4_000, event(4_000), state = "INTERRUPTED")
            }
        }
    }

    @Test fun `explicit parked scenario restart resets cooldown without granting play intent`() {
        val t = Trace()
        t.at(0, event(0, 5), PlaybackAction.PAUSE)
        t.at(1_000, event(1_000))
        t.at(3_000, event(3_000), PlaybackAction.PLAY)
        t.c.resetNavigationCooldown()
        t.at(3_001, event(3_001, 5), PlaybackAction.PAUSE)
        t.c.userPause()
        t.c.resetNavigationCooldown()
        t.at(4_000, event(4_000))
        t.at(6_000, event(6_000), state = "USER_PAUSED")
        val idle = PlaybackCoordinator()
        idle.resetNavigationCooldown()
        assertEquals("IDLE", idle.state)
    }

    @Test fun `paced approach and two successive turns group into one interruption`() {
        val t = Trace()
        // Virtual seconds: a countdown, then a second turn six seconds beyond the first.
        // No real road/distance or SDK completion inference is represented.
        val countdown = listOf(0L to 20, 2L to 18, 6L to 14, 10L to 10,
            12L to 8, 14L to 6, 17L to 3, 19L to 1, 20L to 6,
            22L to 4, 25L to 1, 26L to 30, 28L to 28, 31L to 25, 34L to 22)
        countdown.forEach { (seconds, eta) ->
            t.at(seconds * 1_000, event(seconds * 1_000, eta), when (seconds) {
                12L -> PlaybackAction.PAUSE
                28L -> PlaybackAction.PLAY
                else -> PlaybackAction.NONE
            })
        }
        t.at(36_000, event(36_000, null, active = false), state = "NORMAL")
        assertEquals(listOf(PlaybackAction.PAUSE, PlaybackAction.PLAY),
            t.actions.filter { it != PlaybackAction.NONE })
    }

}
