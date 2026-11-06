package com.quietchapter.core

enum class PlaybackAction { NONE, PLAY, PAUSE }

/** Sole authority for automatic resume; Android playback/focus is handled by the adapter. */
class PlaybackCoordinator(private val policy: RoadContextPolicy = RoadContextPolicy()) {
    var state: String = "IDLE"
        private set
    var interruptionReason: String? = null
        private set
    private var wantsPlayback = false
    private var interrupted = false
    val navigationHealth: String get() = policy.health

    /** Explicit parked fixture restart only; does not change playback intent or route block. */
    fun resetNavigationCooldown() = policy.resumeSuppressed()

    fun userPlay(): PlaybackAction {
        if (state in listOf("NORMAL", "PAUSE_PENDING") && wantsPlayback && !interrupted) return PlaybackAction.NONE
        policy.userPlaybackRequested()
        if (policy.routeBlocked) {
            if (interrupted || (state == "AUTO_PAUSED" && wantsPlayback)) return PlaybackAction.NONE
            wantsPlayback = true
            state = "AUTO_PAUSED"
            return PlaybackAction.PAUSE
        }
        wantsPlayback = true
        interrupted = false
        interruptionReason = null
        state = "NORMAL"
        return PlaybackAction.PLAY
    }

    fun userPause(): PlaybackAction {
        if (state == "USER_PAUSED") return PlaybackAction.NONE
        wantsPlayback = false
        state = "USER_PAUSED"
        return PlaybackAction.PAUSE
    }

    /** Focus loss, noisy output, background, failure or route loss all invalidate resume. */
    fun interrupt(reason: String): PlaybackAction {
        wantsPlayback = false
        interrupted = true
        interruptionReason = reason
        // Listener notifications can be repeated or arrive after explicit Pause/completion.
        if (state == "USER_PAUSED" || state == "ENDED" || state == "INTERRUPTED") {
            return PlaybackAction.NONE
        }
        state = "INTERRUPTED"
        return PlaybackAction.PAUSE
    }

    fun ended(): PlaybackAction {
        if (state == "ENDED") return PlaybackAction.NONE
        wantsPlayback = false
        state = "ENDED"
        return PlaybackAction.PAUSE
    }

    fun onTick(nowMs: Long): PlaybackAction =
        if (policy.expire(nowMs)) {
            if (state == "AUTO_PAUSED") interrupt("Navigation expired")
            else { cancelScheduledPause(); PlaybackAction.NONE }
        } else PlaybackAction.NONE

    fun contextRejected(stale: Boolean): PlaybackAction {
        val rejected = policy.rejectedObservation(stale)
        return if (rejected == RoadContextIntent.UNAVAILABLE && state == "AUTO_PAUSED")
            interrupt("Navigation rejected as stale") else { cancelScheduledPause(); PlaybackAction.NONE }
    }

    fun commitScheduledPause(): PlaybackAction {
        if (state != "PAUSE_PENDING" || !wantsPlayback || interrupted) return PlaybackAction.NONE
        state = "AUTO_PAUSED"
        return PlaybackAction.PAUSE
    }

    fun cancelScheduledPause() {
        if (state == "PAUSE_PENDING") { state = "NORMAL"; policy.cancelDeferredPause() }
    }

    fun onNavigation(event: NavigationEvent, nowMs: Long, deferAutomaticPause: Boolean = false): PlaybackAction {
        val expiryAction = onTick(nowMs)
        val action = when (policy.evaluate(event, nowMs)) {
            RoadContextIntent.PAUSE -> {
                if (wantsPlayback && !interrupted && state == "NORMAL") {
                    state = if (deferAutomaticPause) "PAUSE_PENDING" else "AUTO_PAUSED"
                    if (deferAutomaticPause) PlaybackAction.NONE else PlaybackAction.PAUSE
                } else PlaybackAction.NONE
            }
            RoadContextIntent.RESUME -> {
                if (wantsPlayback && !interrupted && state == "PAUSE_PENDING") {
                    state = "NORMAL"
                    policy.resumeSuppressed()
                    PlaybackAction.NONE
                } else if (wantsPlayback && !interrupted && state == "AUTO_PAUSED") {
                    state = "NORMAL"
                    PlaybackAction.PLAY
                } else {
                    policy.resumeSuppressed()
                    PlaybackAction.NONE
                }
            }
            RoadContextIntent.UNAVAILABLE -> {
                if (state == "AUTO_PAUSED") interrupt("Navigation unavailable")
                else { cancelScheduledPause(); PlaybackAction.NONE }
            }
            RoadContextIntent.IGNORE, RoadContextIntent.NORMAL -> PlaybackAction.NONE
        }
        if (state == "PAUSE_PENDING" && !policy.imminentObservation) cancelScheduledPause()
        return if (expiryAction == PlaybackAction.PAUSE) expiryAction else action
    }
}

/** Unknown duration is represented by a negative value. */
fun clampSeekPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs >= 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)
