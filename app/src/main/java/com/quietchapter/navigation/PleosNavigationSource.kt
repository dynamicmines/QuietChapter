package com.quietchapter.navigation

/** SDK-independent future input port. No SDK binding, event emitter or live adapter exists yet. */
interface PleosNavigationSource : AutoCloseable {
    fun connect(observer: (PleosNavigationSnapshot) -> Unit)
    override fun close()
}

enum class PleosConnectionHealth { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/** Names/values are retained from the SDK, never guessed numeric maneuver mappings. */
data class PleosTbtEntry(
    val rawTurnTypeName: String,
    val rawTurnTypeValue: Long?,
    val rawDistanceMeters: Double,
    val description: String,
) {
    val usableDistanceMeters: Double?
        get() = rawDistanceMeters.takeIf { it.isFinite() && it >= 0 }
}

/** List is ordered-as-received; no guaranteed ordering, horizon, ETA or turn spacing is inferred. */
data class PleosNavigationSnapshot(
    val receivedElapsedMs: Long,
    val localRouteEpoch: Long,
    val connectionHealth: PleosConnectionHealth,
    val routeActive: Boolean?,
    val rawNavigationStatus: String?,
    val rawNaviMode: String?,
    val tbtEntries: List<PleosTbtEntry>,
) {
    /** False means only 'not reported as simulation', never verified live navigation. */
    val reportsSdkSimulation: Boolean get() = rawNaviMode == "SIMULATION_GUIDING"
}
