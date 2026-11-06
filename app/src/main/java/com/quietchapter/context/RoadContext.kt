package com.quietchapter.context

enum class ContextProvider { NATIVE_PLEOS, ANDROID_PLATFORM, EXTERNAL, DERIVED, SIMULATOR }
enum class ContextDelivery { LIVE, REPLAY, SIMULATED }
enum class ContextValidity { VALID, UNKNOWN, INVALID, UNAVAILABLE }
enum class RouteAuthority { UNVERIFIED, VERIFIED_NATIVE_ROUTE }

/**
 * Future adapter proof, not a destination/waypoint match or an independent route identifier.
 * The adapter must invalidate it on route replacement, cancellation, source loss or clock gaps.
 * Merely constructing this value does not verify an SDK contract or enable native adaptation.
 */
data class NativeRouteBinding(
    val routeId: String,
    val routeVersion: String,
    val authority: RouteAuthority = RouteAuthority.UNVERIFIED,
)

/**
 * Provenance accompanies a fact, independently of the fact's eventual policy payload.
 * Both clocks must be mapped to the application's monotonic elapsed-time domain. A newly
 * received cached observation cannot become fresh by changing only receivedElapsedMs.
 */
data class RoadContextObservation(
    val provider: ContextProvider,
    val delivery: ContextDelivery,
    val sourceElapsedMs: Long,
    val receivedElapsedMs: Long,
    val validity: ContextValidity = ContextValidity.UNKNOWN,
    val routeBinding: NativeRouteBinding? = null,
)

enum class ContextAdmissionReason {
    SIMULATION_ADMITTED, NATIVE_ADMITTED, INVALID_TIME, FUTURE, STALE,
    INVALID_OBSERVATION, REPLAY_NOT_LIVE, SOURCE_DELIVERY_MISMATCH,
    RELEASE_SIMULATION_DISABLED, UNSUPPORTED_NAVIGATION_PROVIDER,
    NATIVE_CAPABILITY_DISABLED, NATIVE_ROUTE_UNBOUND,
}

data class ContextAdmission(val eligible: Boolean, val reason: ContextAdmissionReason)

/**
 * Admission to navigation policy only; this gate neither converts facts into an ETA nor issues
 * playback actions. Android audio focus remains a separate platform interruption owned by
 * Media3, not a navigation fact needing route authority. Vehicle speed alone is not congestion.
 *
 * Native adaptation stays disabled by default, including when a caller supplies a binding.
 * Enabling it is a future evidence-backed integration decision, not a runtime SDK discovery.
 */
class RoadContextGate(
    private val maximumAgeMs: Long = 5_000,
    private val nativeNavigationEnabled: Boolean = false,
) {
    init { require(maximumAgeMs >= 0) }

    fun evaluate(
        observation: RoadContextObservation,
        nowMs: Long,
        isDebugBuild: Boolean,
    ): ContextAdmission {
        val source = observation.sourceElapsedMs
        val received = observation.receivedElapsedMs
        if (nowMs < 0 || source < 0 || received < 0) return reject(ContextAdmissionReason.INVALID_TIME)
        if (source > nowMs || received > nowMs || source > received) return reject(ContextAdmissionReason.FUTURE)
        if (nowMs - source > maximumAgeMs || nowMs - received > maximumAgeMs) return reject(ContextAdmissionReason.STALE)
        if (observation.validity != ContextValidity.VALID) return reject(ContextAdmissionReason.INVALID_OBSERVATION)
        if (observation.delivery == ContextDelivery.REPLAY) return reject(ContextAdmissionReason.REPLAY_NOT_LIVE)

        return when (observation.provider) {
            ContextProvider.SIMULATOR -> when {
                observation.delivery != ContextDelivery.SIMULATED || observation.routeBinding != null ->
                    reject(ContextAdmissionReason.SOURCE_DELIVERY_MISMATCH)
                !isDebugBuild -> reject(ContextAdmissionReason.RELEASE_SIMULATION_DISABLED)
                else -> ContextAdmission(true, ContextAdmissionReason.SIMULATION_ADMITTED)
            }
            ContextProvider.NATIVE_PLEOS -> {
                if (observation.delivery != ContextDelivery.LIVE) return reject(ContextAdmissionReason.SOURCE_DELIVERY_MISMATCH)
                if (!nativeNavigationEnabled) return reject(ContextAdmissionReason.NATIVE_CAPABILITY_DISABLED)
                val binding = observation.routeBinding
                if (binding == null || binding.authority != RouteAuthority.VERIFIED_NATIVE_ROUTE ||
                    binding.routeId.isBlank() || binding.routeVersion.isBlank()) {
                    reject(ContextAdmissionReason.NATIVE_ROUTE_UNBOUND)
                } else ContextAdmission(true, ContextAdmissionReason.NATIVE_ADMITTED)
            }
            ContextProvider.ANDROID_PLATFORM, ContextProvider.EXTERNAL, ContextProvider.DERIVED ->
                reject(ContextAdmissionReason.UNSUPPORTED_NAVIGATION_PROVIDER)
        }
    }

    private fun reject(reason: ContextAdmissionReason) = ContextAdmission(false, reason)
}
