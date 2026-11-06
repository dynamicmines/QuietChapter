package com.quietchapter.recovery

/** One interrupted listening position, never a grant of playback intent. Not persisted. */
class RecoveryTicket {
    private data class Position(val mediaId: String, val revision: Int, val positionMs: Long, val originalMs: Long = positionMs)
    private var position: Position? = null
    val pending: Boolean get() = position != null
    fun remember(mediaId: String, revision: Int, positionMs: Long) {
        position = if (mediaId.isNotEmpty() && positionMs >= 0) Position(mediaId, revision, positionMs) else null
    }
    fun clear() { position = null }
    /** Only the adapter may call this while actually paused, with no manual seek/media change. */
    fun settle(mediaId: String, revision: Int, stoppedPositionMs: Long) {
        val saved = position ?: return
        if (saved.mediaId == mediaId && saved.revision == revision && stoppedPositionMs >= 0 &&
            kotlin.math.abs(stoppedPositionMs - saved.originalMs) <= 1_000) position = saved.copy(positionMs = stoppedPositionMs)
    }
    /** Consume once on authorized recovery; identity/drift mismatch never requests a seek. */
    fun consume(mediaId: String, revision: Int, currentPositionMs: Long): Long? {
        val saved = position ?: return null
        position = null
        return saved.positionMs.takeIf { saved.mediaId == mediaId && saved.revision == revision &&
            currentPositionMs >= 0 && kotlin.math.abs(currentPositionMs - it) <= 1_000 }
    }
}
