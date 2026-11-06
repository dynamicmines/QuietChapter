package com.quietchapter.core

/** Normalized source lifecycle; these values are not NaviHelper SDK contracts. */
enum class NavigationSignal { UPDATE, REROUTING, CANCELLED, DISCONNECTED }

/** Monotonic timestamps only. Missing maneuver data is unknown, never invented. */
data class NavigationEvent(
    val elapsedMs: Long,
    val routeActive: Boolean,
    val maneuverEtaSeconds: Int?,
    val sourceLabel: String = "SIMULATED",
    val routeGeneration: Long = 0,
    val signal: NavigationSignal = NavigationSignal.UPDATE,
)

data class SimulationFrame(val offsetMs: Long, val event: NavigationEvent)

/** A finite, reproducible input timeline for the first parked prototype. */
interface NavigationSource {
    val sourceLabel: String
    fun frames(): List<SimulationFrame>
}

class SimulatedNavigationSource : NavigationSource {
    override val sourceLabel = "SIMULATED"

    override fun frames(): List<SimulationFrame> = listOf(
        frame(0, true, 60),
        frame(3_000, true, 5),
        frame(5_000, true, 4),
        frame(8_000, true, 60),
        frame(11_000, true, 60),
        frame(14_000, false, null),
    )

    private fun frame(offsetMs: Long, active: Boolean, eta: Int?) =
        SimulationFrame(offsetMs, NavigationEvent(offsetMs, active, eta, sourceLabel))
}
