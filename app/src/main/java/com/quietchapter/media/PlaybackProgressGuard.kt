package com.quietchapter.media

/** Detects a stuck playback clock, not audibility. Buffering/playing transitions cannot reset it. */
class PlaybackProgressGuard(private val timeoutMs: Long = 8_000) {
    private var lastProgressAt: Long? = null
    private var position = 0L
    private var reported = false

    fun reset() { lastProgressAt = null; reported = false }

    /** Caller supplies actual player intent and a monotonic clock; never grants Play. */
    fun stalled(active: Boolean, positionMs: Long, nowMs: Long): Boolean {
        if (!active) { reset(); return false }
        val previous = lastProgressAt
        if (previous == null || nowMs < previous || positionMs >= position + 100 || positionMs < position - 1_000) {
            lastProgressAt = nowMs
            position = positionMs
            return false
        }
        if (!reported && nowMs - previous >= timeoutMs) {
            reported = true
            return true
        }
        return false
    }
}
