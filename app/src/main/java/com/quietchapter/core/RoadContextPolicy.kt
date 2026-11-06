package com.quietchapter.core

enum class RoadContextIntent { IGNORE, NORMAL, PAUSE, RESUME, UNAVAILABLE }

/** Deterministic route heuristic; makes no claim about attention or road safety. */
class RoadContextPolicy(
    private val imminentThresholdSeconds: Int = 8,
    private val clearHysteresisMs: Long = 2_000,
    private val maximumAgeMs: Long = 5_000,
    private val resumeCooldownMs: Long = 5_000,
) {
    init {
        require(imminentThresholdSeconds >= 0)
        require(clearHysteresisMs >= 0)
        require(maximumAgeMs >= 0)
        require(resumeCooldownMs >= 0)
    }

    var routeBlocked = false
        private set
    var health = "NO_DATA"
        private set
    private var lastEvent: NavigationEvent? = null
    private var clearSinceMs: Long? = null
    private var clearReceivedMs: Long? = null
    private var pauseAllowedAfterMs = -1L

    val imminentObservation: Boolean get() = health == "AVAILABLE" && lastEvent?.let {
        it.routeActive && it.maneuverEtaSeconds != null && it.maneuverEtaSeconds <= imminentThresholdSeconds
    } == true

    /** Freshness is visible even when ordinary playback needs no action. */
    fun expire(nowMs: Long): Boolean {
        val last = lastEvent ?: return false
        if (health == "EXPIRED" || nowMs <= last.elapsedMs ||
            nowMs - last.elapsedMs <= maximumAgeMs) return false
        unavailable("EXPIRED")
        return true
    }

    fun evaluate(event: NavigationEvent, nowMs: Long): RoadContextIntent {
        if (event.elapsedMs < 0 || event.elapsedMs > nowMs || event.routeGeneration < 0 ||
            (event.maneuverEtaSeconds != null && event.maneuverEtaSeconds < 0)) return unreliable()
        val last = lastEvent
        if (last != null && event.routeGeneration < last.routeGeneration) return RoadContextIntent.IGNORE
        if (last != null && event.routeGeneration == last.routeGeneration && event.elapsedMs <= last.elapsedMs) {
            if (event == last) return RoadContextIntent.IGNORE // Repeats never extend freshness.
            // Lifecycle loss is conservative even when callbacks share a millisecond or reorder.
            if (event.signal != NavigationSignal.UPDATE) {
                if (event.elapsedMs == last.elapsedMs) lastEvent = event
                return unavailable(event.signal.name)
            }
            return unreliable() // Conflicting/reordered evidence breaks the clear observation span.
        }
        if (nowMs - event.elapsedMs > maximumAgeMs) return unavailable("STALE")
        val wasBlocked = routeBlocked
        val changedRoute = last != null && event.routeGeneration > last.routeGeneration
        val lostPendingResume = changedRoute && wasBlocked
        if (changedRoute) {
            routeBlocked = false
            resetClear()
        }
        lastEvent = event
        if (event.signal != NavigationSignal.UPDATE) return unavailable(event.signal.name)
        if (!event.routeActive || event.maneuverEtaSeconds == null) return unavailable("UNAVAILABLE")
        health = "AVAILABLE"

        if (event.maneuverEtaSeconds <= imminentThresholdSeconds) {
            // A heuristic listening cooldown prevents immediate pause/play flutter.
            // Only fresh later events may trigger a new pause after this window.
            resetClear()
            if (nowMs >= pauseAllowedAfterMs) routeBlocked = true
            return when {
                lostPendingResume -> RoadContextIntent.UNAVAILABLE
                routeBlocked && !wasBlocked -> RoadContextIntent.PAUSE
                else -> RoadContextIntent.NORMAL
            }
        }
        if (lostPendingResume) return RoadContextIntent.UNAVAILABLE
        if (!routeBlocked) return RoadContextIntent.NORMAL
        val clearSince = clearSinceMs
        val receivedSince = clearReceivedMs
        if (clearSince == null || receivedSince == null) {
            clearSinceMs = event.elapsedMs
            clearReceivedMs = nowMs
            return RoadContextIntent.NORMAL
        }
        // A delayed batch cannot compress the local stable-clear observation window.
        if (event.elapsedMs - clearSince < clearHysteresisMs ||
            nowMs - receivedSince < clearHysteresisMs) return RoadContextIntent.NORMAL
        routeBlocked = false
        resetClear()
        pauseAllowedAfterMs = nowMs + resumeCooldownMs
        return RoadContextIntent.RESUME
    }

    /** Admission rejection carries no invented navigation payload. Break the clear span. */
    fun rejectedObservation(stale: Boolean): RoadContextIntent =
        if (stale) unavailable("STALE") else unreliable()

    /** No audio pause occurred; discard an advisory schedule without granting playback. */
    fun cancelDeferredPause() { routeBlocked = false; resetClear() }

    /** Suppressed resume proposals must not create a cooldown without playback. */
    fun resumeSuppressed() { pauseAllowedAfterMs = -1L }

    /** Listening cooldown applies to automatic playback, never to a new explicit intent. */
    fun userPlaybackRequested() {
        pauseAllowedAfterMs = -1L
        val last = lastEvent
        if (health == "AVAILABLE" && last?.maneuverEtaSeconds != null &&
            last.maneuverEtaSeconds <= imminentThresholdSeconds) routeBlocked = true
    }

    private fun resetClear() {
        clearSinceMs = null
        clearReceivedMs = null
    }

    private fun unreliable(): RoadContextIntent {
        health = "UNRELIABLE"
        resetClear()
        return RoadContextIntent.IGNORE
    }

    private fun unavailable(reason: String): RoadContextIntent {
        health = reason
        routeBlocked = false
        resetClear()
        return RoadContextIntent.UNAVAILABLE
    }
}
