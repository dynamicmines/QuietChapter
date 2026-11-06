package com.quietchapter.narrative

import java.util.Collections

enum class NarrativeSource { ACOUSTIC_VAD, TRANSCRIPT_ALIGNED_SENTENCE, AUTHORED_CHAPTER }
enum class NarrativeReliability { VERIFIED, ADVISORY, REJECTED }
enum class NarrativeKind { SPEECH, SILENCE, SENTENCE, PARAGRAPH, SCENE, DIALOGUE, DENSE_NARRATION, CHAPTER }

data class NarrativeChapter(val id: String, val startMs: Long, val endMs: Long)

data class NarrativeAnnotation(
    val id: String,
    val chapterId: String,
    val startMs: Long,
    val endMs: Long,
    val kind: NarrativeKind,
    val source: NarrativeSource,
    val reliability: NarrativeReliability,
    val text: String? = null,
)

data class NarrativeBoundary(
    val annotationId: String,
    val chapterId: String,
    val positionMs: Long,
    val source: NarrativeSource,
)

/**
 * Cached asset-absolute metadata, never playback authority. The caller must verify the asset
 * hash and translate clipped-media positions before asking for a boundary. VERIFIED describes
 * validated transcript alignment, not semantic comprehension or a safety guarantee. Acoustic
 * silence cannot become a sentence by changing its kind. Model probabilities stay tool-side.
 */
class NarrativeTimeline(
    val assetSha256: String,
    val language: String,
    val durationMs: Long,
    val analysisVersion: String,
    chapters: List<NarrativeChapter>,
    annotations: List<NarrativeAnnotation>,
) {
    val chapters: List<NarrativeChapter> = Collections.unmodifiableList(chapters.toList())
    val annotations: List<NarrativeAnnotation> = Collections.unmodifiableList(annotations.toList())
    private val chapterById = this.chapters.associateBy { it.id }
    val valid: Boolean = validate()
    private val sentences = if (valid && !language.substringBefore("-").equals("und", ignoreCase = true)) this.annotations.filter {
        it.kind == NarrativeKind.SENTENCE && it.source == NarrativeSource.TRANSCRIPT_ALIGNED_SENTENCE &&
            it.reliability == NarrativeReliability.VERIFIED
    }.groupBy { it.chapterId } else emptyMap()

    val hasVerifiedSentences: Boolean get() = sentences.isNotEmpty()

    /** Latest sufficiently earlier heard sentence start, bounded to this authored chapter. */
    fun recovery(
        positionMs: Long,
        chapterId: String,
        maxBacktrackMs: Long = 5_000,
        minReplayMs: Long = 600,
        heardFromPositionMs: Long? = null,
    ): NarrativeBoundary? {
        if (!valid || maxBacktrackMs < 0 || minReplayMs < 0 || minReplayMs > maxBacktrackMs) return null
        val chapter = currentChapter(positionMs, chapterId) ?: return null
        if (positionMs < minReplayMs || (heardFromPositionMs != null &&
                (heardFromPositionMs < 0 || heardFromPositionMs > positionMs))) return null
        val candidates = sentences[chapterId] ?: return null
        val index = upperBound(candidates) { it.startMs <= positionMs - minReplayMs } - 1
        if (index < 0) return null
        val sentence = candidates[index]
        if (sentence.startMs < maxOf(chapter.startMs, positionMs - maxBacktrackMs,
                heardFromPositionMs ?: chapter.startMs)) return null
        return NarrativeBoundary(sentence.id, chapterId, sentence.startMs, sentence.source)
    }

    /** First validated sentence end before a caller-supplied deadline; no inferred route ETA. */
    fun softPause(
        positionMs: Long,
        deadlinePositionMs: Long,
        chapterId: String,
        maxLookaheadMs: Long = 8_000,
    ): NarrativeBoundary? {
        if (!valid || maxLookaheadMs < 0 || deadlinePositionMs <= positionMs) return null
        currentChapter(positionMs, chapterId) ?: return null
        val candidates = sentences[chapterId] ?: return null
        val index = upperBound(candidates) { it.endMs <= positionMs }
        if (index >= candidates.size) return null
        val sentence = candidates[index]
        if (sentence.endMs > deadlinePositionMs || sentence.endMs - positionMs > maxLookaheadMs) return null
        return NarrativeBoundary(sentence.id, chapterId, sentence.endMs, sentence.source)
    }

    private fun currentChapter(positionMs: Long, chapterId: String): NarrativeChapter? {
        val chapter = chapterById[chapterId] ?: return null
        // At a shared chapter endpoint, the following chapter owns the position. The final
        // asset endpoint is allowed for an explicit recovery query, never a Play request.
        return chapter.takeIf { positionMs >= it.startMs && (positionMs < it.endMs ||
            (positionMs == durationMs && it.endMs == durationMs)) }
    }

    private fun validate(): Boolean {
        if (!assetSha256.matches(Regex("[a-fA-F0-9]{64}")) ||
            !language.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) ||
            durationMs <= 0 || analysisVersion.isBlank() || chapters.isEmpty()) return false
        if (chapterById.size != chapters.size || chapters.first().startMs != 0L ||
            chapters.last().endMs != durationMs) return false
        if (chapters.any { it.id.isBlank() || it.startMs < 0 || it.startMs >= it.endMs || it.endMs > durationMs } ||
            chapters.zipWithNext().any { (a, b) -> a.endMs != b.startMs }) return false
        if (annotations.map { it.id }.toSet().size != annotations.size) return false
        for (a in annotations) {
            val chapter = chapterById[a.chapterId] ?: return false
            if (a.id.isBlank() || a.startMs < chapter.startMs || a.startMs >= chapter.endMs ||
                a.startMs >= a.endMs || a.endMs > durationMs) return false
            val advisoryCrossing = a.kind == NarrativeKind.SENTENCE && a.reliability == NarrativeReliability.ADVISORY
            if (a.endMs > chapter.endMs && !advisoryCrossing) return false
            val compatible = when (a.source) {
                NarrativeSource.ACOUSTIC_VAD -> a.kind in listOf(NarrativeKind.SPEECH, NarrativeKind.SILENCE)
                NarrativeSource.AUTHORED_CHAPTER -> a.kind == NarrativeKind.CHAPTER
                NarrativeSource.TRANSCRIPT_ALIGNED_SENTENCE -> a.kind !in listOf(NarrativeKind.SPEECH, NarrativeKind.SILENCE, NarrativeKind.CHAPTER)
            }
            if (a.source == NarrativeSource.AUTHORED_CHAPTER &&
                (a.startMs != chapter.startMs || a.endMs != chapter.endMs)) return false
            if (!compatible || (a.kind == NarrativeKind.SENTENCE &&
                    a.reliability == NarrativeReliability.VERIFIED && a.text.isNullOrBlank())) return false
        }
        val verifiedSentences = annotations.filter {
            it.kind == NarrativeKind.SENTENCE && it.reliability == NarrativeReliability.VERIFIED
        }
        return verifiedSentences.zipWithNext().none { (a, b) -> a.endMs > b.startMs }
    }

    /** First index after the monotone true prefix; lookups do not rescan a whole audiobook. */
    private fun upperBound(items: List<NarrativeAnnotation>, predicate: (NarrativeAnnotation) -> Boolean): Int {
        var low = 0
        var high = items.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (predicate(items[middle])) low = middle + 1 else high = middle
        }
        return low
    }
}
