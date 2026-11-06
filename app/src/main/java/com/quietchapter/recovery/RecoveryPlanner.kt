package com.quietchapter.recovery

enum class RecoveryMode { EXACT, FIXED_BACKTRACK, ACOUSTIC, NARRATIVE }

enum class RecoveryStrategy { EXACT, FIXED_BACKTRACK, NARRATIVE_SENTENCE, ACOUSTIC_BOUNDARY, FIXED_FALLBACK, INVALID_CONTEXT }

data class RecoveryDecision(
    val targetPositionMs: Long,
    val strategy: RecoveryStrategy,
    val replayMs: Long,
)

/**
 * Pure listening-position planner. It never grants playback intent or calls Play/seek.
 *
 * All positions use the selected MediaItem's coordinate system. A clipped chapter normally
 * starts at zero; a chapter within a whole-book item may supply its absolute start position.
 * Negative duration means unknown. Acoustic positions describe silence, not semantic sentences;
 * callers must separately verify the index belongs to the current audio content.
 */
class RecoveryPlanner(
    private val maxBacktrackMs: Long = 5_000,
    private val fallbackMs: Long = 3_000,
    private val minReplayMs: Long = 600,
) {
    init {
        require(maxBacktrackMs >= 0)
        require(fallbackMs in 0..maxBacktrackMs)
        require(minReplayMs in 0..maxBacktrackMs)
    }

    fun plan(
        pausePositionMs: Long,
        durationMs: Long,
        chapterStartMs: Long = 0,
        boundariesMs: List<Long> = emptyList(),
        mode: RecoveryMode,
    ): RecoveryDecision {
        val position = pausePositionMs.coerceAtLeast(0).let {
            if (durationMs >= 0) it.coerceAtMost(durationMs) else it
        }
        if (pausePositionMs < 0 || chapterStartMs < 0 || chapterStartMs > position ||
            (durationMs >= 0 && pausePositionMs > durationMs)) {
            // A safe normalized target is diagnostic only: INVALID_CONTEXT never requests replay.
            return RecoveryDecision(position, RecoveryStrategy.INVALID_CONTEXT, 0)
        }
        if (mode == RecoveryMode.EXACT) return RecoveryDecision(position, RecoveryStrategy.EXACT, 0)

        // Subtraction of two nonnegative Long values cannot underflow below -Long.MAX_VALUE.
        val earliest = maxOf(chapterStartMs, position - maxBacktrackMs)
        val acoustic = mode == RecoveryMode.ACOUSTIC || mode == RecoveryMode.NARRATIVE
        if (acoustic) {
            // maxOrNull is deterministic for unordered/duplicated input and never mutates it.
            val boundary = boundariesMs.asSequence().filter {
                it >= earliest && it < position && position - it >= minReplayMs
            }.maxOrNull()
            if (boundary != null) {
                return RecoveryDecision(boundary, RecoveryStrategy.ACOUSTIC_BOUNDARY, position - boundary)
            }
        }
        val target = maxOf(chapterStartMs, position - fallbackMs)
        // A second interruption shortly after narrative recovery must not seek through
        // the same anchor again. The adapter advances this floor after each recovery.
        if (mode == RecoveryMode.NARRATIVE && position - target < minReplayMs)
            return RecoveryDecision(position, RecoveryStrategy.EXACT, 0)
        return RecoveryDecision(target,
            if (acoustic) RecoveryStrategy.FIXED_FALLBACK else RecoveryStrategy.FIXED_BACKTRACK,
            position - target)
    }
}
