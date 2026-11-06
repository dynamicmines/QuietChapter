package com.quietchapter.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextTest {
    private val gate = RoadContextGate()
    private val simulation = RoadContextObservation(ContextProvider.SIMULATOR,
        ContextDelivery.SIMULATED, 1_000, 1_100, ContextValidity.VALID)
    private val binding = NativeRouteBinding("provider-route", "version-1", RouteAuthority.VERIFIED_NATIVE_ROUTE)
    private val native = RoadContextObservation(ContextProvider.NATIVE_PLEOS,
        ContextDelivery.LIVE, 1_000, 1_100, ContextValidity.VALID, binding)

    @Test fun `fresh explicitly simulated observations are admitted in debug only`() {
        assertEquals(ContextAdmission(true, ContextAdmissionReason.SIMULATION_ADMITTED), gate.evaluate(simulation, 1_200, true))
        assertEquals(ContextAdmission(false, ContextAdmissionReason.RELEASE_SIMULATION_DISABLED), gate.evaluate(simulation, 1_200, false))
    }

    @Test fun `five second age limit is inclusive`() {
        assertTrue(gate.evaluate(simulation, 6_000, true).eligible)
        assertEquals(ContextAdmissionReason.STALE, gate.evaluate(simulation, 6_001, true).reason)
    }

    @Test fun `fresh receipt does not rejuvenate stale source evidence`() {
        assertEquals(ContextAdmissionReason.STALE,
            gate.evaluate(simulation.copy(receivedElapsedMs = 7_000), 7_000, true).reason)
    }

    @Test fun `future source receipt and inconsistent clocks are rejected`() {
        listOf(simulation.copy(sourceElapsedMs = 1_201), simulation.copy(receivedElapsedMs = 1_201),
            simulation.copy(sourceElapsedMs = 1_150, receivedElapsedMs = 1_100)).forEach {
            assertEquals(ContextAdmissionReason.FUTURE, gate.evaluate(it, 1_200, true).reason)
        }
    }

    @Test fun `negative monotonic times are invalid`() {
        listOf(simulation.copy(sourceElapsedMs = -1), simulation.copy(receivedElapsedMs = -1)).forEach {
            assertEquals(ContextAdmissionReason.INVALID_TIME, gate.evaluate(it, 1_200, true).reason)
        }
        assertEquals(ContextAdmissionReason.INVALID_TIME, gate.evaluate(simulation, -1, true).reason)
    }

    @Test fun `unknown invalid and unavailable data cannot enter navigation policy`() {
        ContextValidity.entries.filter { it != ContextValidity.VALID }.forEach {
            assertEquals(ContextAdmissionReason.INVALID_OBSERVATION,
                gate.evaluate(simulation.copy(validity = it), 1_200, true).reason)
        }
        assertFalse(gate.evaluate(RoadContextObservation(ContextProvider.SIMULATOR,
            ContextDelivery.SIMULATED, 1_000, 1_100), 1_200, true).eligible)
    }

    @Test fun `replayed evidence is never silently promoted to live or simulated policy input`() {
        ContextProvider.entries.forEach { provider ->
            assertEquals(ContextAdmissionReason.REPLAY_NOT_LIVE,
                gate.evaluate(simulation.copy(provider = provider, delivery = ContextDelivery.REPLAY), 1_200, true).reason)
        }
    }

    @Test fun `simulator cannot claim live delivery or native authority`() {
        assertEquals(ContextAdmissionReason.SOURCE_DELIVERY_MISMATCH,
            gate.evaluate(simulation.copy(delivery = ContextDelivery.LIVE), 1_200, true).reason)
        assertEquals(ContextAdmissionReason.SOURCE_DELIVERY_MISMATCH,
            gate.evaluate(simulation.copy(routeBinding = binding), 1_200, true).reason)
    }

    @Test fun `native provider cannot relabel simulated data as a native navigation feed`() {
        assertEquals(ContextAdmissionReason.SOURCE_DELIVERY_MISMATCH,
            gate.evaluate(native.copy(delivery = ContextDelivery.SIMULATED), 1_200, true).reason)
    }

    @Test fun `platform external and derived observations cannot assert native route ownership`() {
        listOf(ContextProvider.ANDROID_PLATFORM, ContextProvider.EXTERNAL, ContextProvider.DERIVED).forEach {
            assertEquals(ContextAdmissionReason.UNSUPPORTED_NAVIGATION_PROVIDER,
                gate.evaluate(native.copy(provider = it), 1_200, true).reason)
        }
    }

    @Test fun `default gate disables native adaptation even with an asserted binding`() {
        assertEquals(ContextAdmissionReason.NATIVE_CAPABILITY_DISABLED, gate.evaluate(native, 1_200, true).reason)
        assertFalse(gate.evaluate(native, 1_200, false).eligible)
    }

    @Test fun `future enabled native capability still requires verified nonempty route binding`() {
        val futureGate = RoadContextGate(nativeNavigationEnabled = true)
        listOf(null, binding.copy(authority = RouteAuthority.UNVERIFIED), binding.copy(routeId = " "),
            binding.copy(routeVersion = "")).forEach {
            assertEquals(ContextAdmissionReason.NATIVE_ROUTE_UNBOUND,
                futureGate.evaluate(native.copy(routeBinding = it), 1_200, false).reason)
        }
    }

    @Test fun `explicit future native capability and authoritative live binding are both required`() {
        assertEquals(ContextAdmission(true, ContextAdmissionReason.NATIVE_ADMITTED),
            RoadContextGate(nativeNavigationEnabled = true).evaluate(native, 1_200, false))
    }

    @Test fun `near Long max clocks do not overflow freshness arithmetic`() {
        val observation = simulation.copy(sourceElapsedMs = Long.MAX_VALUE - 1_000,
            receivedElapsedMs = Long.MAX_VALUE - 100)
        assertTrue(gate.evaluate(observation, Long.MAX_VALUE, true).eligible)
        assertEquals(ContextAdmissionReason.STALE,
            gate.evaluate(simulation.copy(sourceElapsedMs = 0, receivedElapsedMs = Long.MAX_VALUE), Long.MAX_VALUE, true).reason)
    }

    @Test fun `zero freshness allowance admits only the same instant`() {
        val instant = simulation.copy(sourceElapsedMs = 1_000, receivedElapsedMs = 1_000)
        assertTrue(RoadContextGate(0).evaluate(instant, 1_000, true).eligible)
        assertFalse(RoadContextGate(0).evaluate(instant, 1_001, true).eligible)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative freshness window is rejected`() { RoadContextGate(-1) }
}
