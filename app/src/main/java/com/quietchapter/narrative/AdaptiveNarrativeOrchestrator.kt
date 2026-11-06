package com.quietchapter.narrative

/** Asset-absolute target. This is a proposal, never playback intent or route authority. */
data class SoftPausePlan(
    val targetMs: Long,
    val deadlineElapsedMs: Long,
    val sourceElapsedMs: Long,
    val routeGeneration: Long,
    val mediaRevision: Int,
    val chapterId: String,
    val annotationId: String,
    val createdElapsedMs: Long,
)

enum class SoftPauseDecision { WAIT, PAUSE_NOW, CANCEL }

/** The returned plan is pending only for WAIT; cancellation/pause consumes it. */
data class SoftPauseUpdate(val decision: SoftPauseDecision, val plan: SoftPausePlan?)

/**
 * Stateless bounded scheduler. A trusted context gate must supply admitted=true; this class
 * cannot establish native route identity, infer passage or ask for Play/audio focus. The caller
 * cancels on user/system interruption or seek and consumes a due plan exactly once. Immediate
 * platform interruptions always bypass this optional sentence-ending wait.
 */
class AdaptiveNarrativeOrchestrator {
    fun propose(
        timeline: NarrativeTimeline,
        chapterId: String,
        positionMs: Long,
        nowMs: Long,
        sourceElapsedMs: Long,
        etaSeconds: Int?,
        routeGeneration: Long,
        mediaRevision: Int,
        admitted: Boolean,
    ): SoftPausePlan? {
        if (!admitted || !timeline.hasVerifiedSentences || nowMs < 0 || sourceElapsedMs < 0 ||
            sourceElapsedMs > nowMs || routeGeneration < 0 || mediaRevision < 0 || positionMs < 0 ||
            chapterId.isBlank() || etaSeconds == null || etaSeconds < 2) return null
        val age = nowMs - sourceElapsedMs
        if (age > MAX_PREDICTION_AGE_MS) return null
        // Widen before multiplication; an Int.MAX_VALUE ETA must not wrap into a short wait.
        val available = etaSeconds.toLong() * 1_000 - SAFETY_MARGIN_MS - age
        val window = minOf(available, MAX_SOFT_DELAY_MS)
        if (window <= 0 || nowMs > Long.MAX_VALUE - window || positionMs > Long.MAX_VALUE - window) return null
        val boundary = timeline.softPause(positionMs, positionMs + window, chapterId, window) ?: return null
        return SoftPausePlan(boundary.positionMs, nowMs + window, sourceElapsedMs, routeGeneration,
            mediaRevision, chapterId, boundary.annotationId, nowMs)
    }

    fun poll(
        plan: SoftPausePlan,
        positionMs: Long,
        nowMs: Long,
        routeGeneration: Long,
        mediaRevision: Int,
        eligible: Boolean,
    ): SoftPauseDecision {
        if (!eligible || positionMs < 0 || nowMs < 0 || plan.targetMs < 0 ||
            plan.sourceElapsedMs < 0 || plan.createdElapsedMs < plan.sourceElapsedMs ||
            nowMs < plan.createdElapsedMs || plan.deadlineElapsedMs <= plan.createdElapsedMs ||
            plan.routeGeneration < 0 || plan.mediaRevision < 0 || plan.chapterId.isBlank() ||
            plan.annotationId.isBlank() || plan.routeGeneration != routeGeneration ||
            plan.mediaRevision != mediaRevision) return SoftPauseDecision.CANCEL
        if (plan.createdElapsedMs - plan.sourceElapsedMs > MAX_PREDICTION_AGE_MS ||
            plan.deadlineElapsedMs - plan.createdElapsedMs > MAX_SOFT_DELAY_MS ||
            nowMs - plan.sourceElapsedMs > CONTEXT_TTL_MS) return SoftPauseDecision.CANCEL
        // A stalled player may miss the ideal end. The deadline can require Pause, never Play.
        return if (positionMs >= plan.targetMs || nowMs >= plan.deadlineElapsedMs)
            SoftPauseDecision.PAUSE_NOW else SoftPauseDecision.WAIT
    }

    /** Fresh context may tighten or withdraw a prediction, never postpone it or renew its TTL. */
    fun update(
        plan: SoftPausePlan,
        positionMs: Long,
        nowMs: Long,
        sourceElapsedMs: Long,
        etaSeconds: Int?,
        routeGeneration: Long,
        mediaRevision: Int,
        admitted: Boolean,
    ): SoftPauseUpdate {
        val previous = poll(plan, positionMs, nowMs, routeGeneration, mediaRevision, admitted)
        if (previous == SoftPauseDecision.CANCEL || sourceElapsedMs < plan.sourceElapsedMs ||
            sourceElapsedMs > nowMs || nowMs - sourceElapsedMs > MAX_PREDICTION_AGE_MS ||
            etaSeconds == null || etaSeconds < 0 || etaSeconds > MAX_IMMINENT_ETA_SECONDS)
            return SoftPauseUpdate(SoftPauseDecision.CANCEL, null)
        // Withdrawn/invalid context above has priority over an obsolete due boundary.
        if (etaSeconds <= 1 || previous == SoftPauseDecision.PAUSE_NOW)
            return SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null)
        val available = etaSeconds.toLong() * 1_000 - SAFETY_MARGIN_MS - (nowMs - sourceElapsedMs)
        val window = minOf(available, MAX_SOFT_DELAY_MS)
        if (window <= 0) return SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null)
        if (nowMs > Long.MAX_VALUE - window) return SoftPauseUpdate(SoftPauseDecision.CANCEL, null)
        if (plan.targetMs - positionMs > window)
            return SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null)
        val revised = plan.copy(deadlineElapsedMs = minOf(plan.deadlineElapsedMs, nowMs + window))
        return SoftPauseUpdate(SoftPauseDecision.WAIT, revised)
    }

    private companion object {
        const val MAX_IMMINENT_ETA_SECONDS = 8
        const val MAX_PREDICTION_AGE_MS = 500L
        const val MAX_SOFT_DELAY_MS = 3_000L
        const val CONTEXT_TTL_MS = 5_000L
        const val SAFETY_MARGIN_MS = 1_000L
    }
}
