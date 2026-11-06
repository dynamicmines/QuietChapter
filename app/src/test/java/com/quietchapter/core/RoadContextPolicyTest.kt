package com.quietchapter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadContextPolicyTest {
    private fun event(time: Long, eta: Int = 60) = NavigationEvent(time, true, eta)

    @Test fun `duplicate out of order future and malformed events are ignored`() {
        val policy = RoadContextPolicy()
        assertEquals(RoadContextIntent.PAUSE, policy.evaluate(event(3_000, 5), 3_000))
        listOf(event(3_000), event(2_000), event(6_000), event(4_000, -1)).forEach {
            assertEquals(RoadContextIntent.IGNORE, policy.evaluate(it, 5_000))
        }
        assertEquals(true, policy.routeBlocked)
    }

    @Test fun `new imminent event resets clear hysteresis and groups interruption`() {
        val policy = RoadContextPolicy()
        assertEquals(RoadContextIntent.PAUSE, policy.evaluate(event(0, 5), 0))
        assertEquals(RoadContextIntent.NORMAL, policy.evaluate(event(1_000), 1_000))
        assertEquals(RoadContextIntent.NORMAL, policy.evaluate(event(2_000, 4), 2_000))
        assertEquals(RoadContextIntent.NORMAL, policy.evaluate(event(3_000), 3_000))
        assertEquals(RoadContextIntent.NORMAL, policy.evaluate(event(4_999), 4_999))
        assertEquals(RoadContextIntent.RESUME, policy.evaluate(event(5_000), 5_000))
        assertEquals(false, policy.routeBlocked)
    }

    @Test fun `distant ordinary maneuver does not interrupt`() {
        val policy = RoadContextPolicy()
        assertEquals(RoadContextIntent.NORMAL, policy.evaluate(event(0), 0))
        assertEquals(false, policy.routeBlocked)
    }

    @Test fun `fixture is deterministic and all source labels explicitly simulated`() {
        val source = SimulatedNavigationSource()
        assertEquals(source.frames(), source.frames())
        assertEquals(listOf(0L, 3_000L, 5_000L, 8_000L, 11_000L, 14_000L),
            source.frames().map { it.offsetMs })
        source.frames().forEach {
            assertEquals(it.offsetMs, it.event.elapsedMs)
            assertEquals("SIMULATED", it.event.sourceLabel)
        }
    }
}
