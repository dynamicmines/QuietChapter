package com.quietchapter.narrative

import org.junit.Assert.*
import org.junit.Test

class NarrativeTimelineTest {
    private val chapters = listOf(NarrativeChapter("one", 0, 10_000), NarrativeChapter("two", 10_000, 20_000))
    private fun sentence(id: String, start: Long, end: Long, chapter: String = "one",
        reliability: NarrativeReliability = NarrativeReliability.VERIFIED,
        source: NarrativeSource = NarrativeSource.TRANSCRIPT_ALIGNED_SENTENCE,
        text: String? = "An original sentence.") =
        NarrativeAnnotation(id, chapter, start, end, NarrativeKind.SENTENCE, source, reliability, text)
    private fun map(
        annotations: List<NarrativeAnnotation> = listOf(sentence("a", 1_000, 3_000), sentence("b", 3_500, 6_000),
            sentence("c", 6_500, 9_500), sentence("d", 10_500, 12_000, "two"), sentence("e", 12_500, 17_000, "two")),
        chapters: List<NarrativeChapter> = this.chapters,
        hash: String = "a".repeat(64), language: String = "en", duration: Long = 20_000,
        version: String = "original-transcript+alignment-reviewed-v1",
    ) = NarrativeTimeline(hash, language, duration, version, chapters, annotations)

    @Test fun `latest sufficiently heard sentence start is selected`() {
        val timeline = map()
        assertTrue(timeline.valid)
        assertTrue(timeline.hasVerifiedSentences)
        assertEquals(NarrativeBoundary("b", "one", 3_500, NarrativeSource.TRANSCRIPT_ALIGNED_SENTENCE),
            timeline.recovery(5_500, "one"))
    }

    @Test fun `too recent start is skipped in favor of an earlier heard sentence`() {
        assertEquals(1_000L, map().recovery(3_900, "one")?.positionMs)
        assertNull(map().recovery(1_100, "one"))
    }

    @Test fun `recovery never selects future narration or crosses authored chapter`() {
        assertEquals(10_500L, map().recovery(12_000, "two")?.positionMs)
        assertNull(map().recovery(10_100, "two"))
        assertNull(map().recovery(10_100, "one"))
        assertNull(map().recovery(9_900, "two"))
    }

    @Test fun `seek before a new interruption limits recovery to content heard after the seek`() {
        val timeline = map()
        assertEquals(3_500L, timeline.recovery(5_500, "one", heardFromPositionMs = 3_500)?.positionMs)
        assertNull(timeline.recovery(5_500, "one", heardFromPositionMs = 4_000))
        assertEquals(6_500L, timeline.recovery(8_000, "one", heardFromPositionMs = 6_500)?.positionMs)
    }

    @Test fun `paused-point and invalid listening floors cannot grant replay`() {
        val timeline = map()
        for (floor in listOf(-1L, 5_500, 5_501, Long.MAX_VALUE)) {
            assertNull(timeline.recovery(5_500, "one", heardFromPositionMs = floor))
        }
    }

    @Test fun `listening floor and chapter floor both constrain recovery`() {
        val timeline = map()
        assertEquals(10_500L, timeline.recovery(12_000, "two", heardFromPositionMs = 0)?.positionMs)
        assertNull(timeline.recovery(12_000, "two", heardFromPositionMs = 11_000))
        assertEquals(12_500L, timeline.recovery(14_000, "two", heardFromPositionMs = 11_000)?.positionMs)
    }

    @Test fun `shared endpoint belongs to following chapter and final endpoint remains queryable`() {
        assertNull(map().recovery(10_000, "one"))
        assertNull(map().recovery(10_000, "two"))
        assertNull(map().recovery(20_001, "two"))
        assertNull(map().softPause(20_000, Long.MAX_VALUE, "two"))
    }

    @Test fun `long sentence without a bounded earlier start returns fallback signal`() {
        val timeline = map(listOf(sentence("long", 100, 9_900)))
        assertNull(timeline.recovery(9_000, "one"))
        assertEquals(100L, timeline.recovery(5_100, "one")?.positionMs)
    }

    @Test fun `minimum and maximum replay limits are inclusive`() {
        val timeline = map(listOf(sentence("a", 1_000, 8_000)))
        assertEquals(1_000L, timeline.recovery(1_600, "one")?.positionMs)
        assertEquals(1_000L, timeline.recovery(6_000, "one")?.positionMs)
        assertNull(timeline.recovery(6_001, "one"))
    }

    @Test fun `missing annotations leave valid metadata without an invented sentence`() {
        val timeline = map(emptyList())
        assertTrue(timeline.valid)
        assertNull(timeline.recovery(5_000, "one"))
        assertNull(timeline.softPause(1_000, 8_000, "one"))
    }

    @Test fun `advisory and rejected model annotations do not drive recovery or scheduled pause`() {
        for (quality in listOf(NarrativeReliability.ADVISORY, NarrativeReliability.REJECTED)) {
            val timeline = map(listOf(sentence("a", 1_000, 3_000, reliability = quality)))
            assertTrue(timeline.valid)
            assertNull(timeline.recovery(2_500, "one"))
            assertNull(timeline.softPause(1_500, 4_000, "one"))
        }
    }

    @Test fun `real acoustic intervals are retained without becoming semantic boundaries`() {
        val timeline = map(listOf(
            NarrativeAnnotation("speech", "one", 1_000, 3_000, NarrativeKind.SPEECH,
                NarrativeSource.ACOUSTIC_VAD, NarrativeReliability.VERIFIED),
            NarrativeAnnotation("quiet", "one", 3_000, 3_500, NarrativeKind.SILENCE,
                NarrativeSource.ACOUSTIC_VAD, NarrativeReliability.VERIFIED)))
        assertTrue(timeline.valid)
        assertNull(timeline.recovery(4_000, "one"))
        assertNull(timeline.softPause(2_000, 8_000, "one"))
    }

    @Test fun `silence relabeled as a sentence invalidates provenance instead of implying understanding`() {
        val timeline = map(listOf(sentence("fake", 1_000, 3_000, source = NarrativeSource.ACOUSTIC_VAD)))
        assertFalse(timeline.valid)
        assertNull(timeline.recovery(4_000, "one"))
        assertNull(timeline.softPause(2_000, 8_000, "one"))
    }

    @Test fun `authored chapter markers do not substitute for sentence analysis`() {
        val timeline = map(listOf(NarrativeAnnotation("chapter", "one", 0, 10_000,
            NarrativeKind.CHAPTER, NarrativeSource.AUTHORED_CHAPTER, NarrativeReliability.VERIFIED)))
        assertTrue(timeline.valid)
        assertNull(timeline.recovery(2_000, "one"))
        assertNull(timeline.softPause(2_000, 10_000, "one"))
    }

    @Test fun `optional scene dialogue and density annotations are not stopping authority`() {
        val annotations = listOf(NarrativeKind.SCENE, NarrativeKind.DIALOGUE, NarrativeKind.DENSE_NARRATION,
            NarrativeKind.PARAGRAPH).mapIndexed { index, kind ->
            NarrativeAnnotation("optional$index", "one", 1_000, 3_000, kind,
                NarrativeSource.TRANSCRIPT_ALIGNED_SENTENCE, NarrativeReliability.ADVISORY, "Text annotation")
        }
        val timeline = map(annotations)
        assertTrue(timeline.valid)
        assertNull(timeline.recovery(4_000, "one"))
        assertNull(timeline.softPause(2_000, 8_000, "one"))
    }

    @Test fun `soft pause selects earliest suitable future sentence end before deadline`() {
        assertEquals(3_000L, map().softPause(1_500, 8_000, "one")?.positionMs)
        assertEquals(6_000L, map().softPause(3_000, 8_000, "one")?.positionMs)
        assertNull(map().softPause(4_000, 5_999, "one"))
        assertEquals(6_000L, map().softPause(4_000, 6_000, "one")?.positionMs)
    }

    @Test fun `no timely boundary means no soft pause proposal`() {
        val timeline = map(listOf(sentence("long", 0, 9_999)))
        assertNull(timeline.softPause(0, 10_000, "one"))
        assertNull(timeline.softPause(0, Long.MAX_VALUE, "one"))
        assertEquals(9_999L, timeline.softPause(2_000, 10_000, "one")?.positionMs)
    }

    @Test fun `deadline and current-position errors fail closed`() {
        val timeline = map()
        for (position in listOf(-1L, Long.MIN_VALUE, 20_001, Long.MAX_VALUE)) {
            assertNull(timeline.recovery(position, "one"))
            assertNull(timeline.softPause(position, Long.MAX_VALUE, "one"))
        }
        assertNull(timeline.softPause(4_000, 4_000, "one"))
        assertNull(timeline.softPause(4_000, 3_999, "one"))
        assertNull(timeline.recovery(4_000, "missing"))
        assertNull(timeline.softPause(4_000, 8_000, "missing"))
    }

    @Test fun `invalid planner limits do not throw into playback`() {
        val timeline = map()
        assertNull(timeline.recovery(4_000, "one", maxBacktrackMs = -1))
        assertNull(timeline.recovery(4_000, "one", minReplayMs = -1))
        assertNull(timeline.recovery(4_000, "one", maxBacktrackMs = 500, minReplayMs = 600))
        assertNull(timeline.softPause(1_000, 8_000, "one", maxLookaheadMs = -1))
        assertNull(timeline.softPause(1_000, 8_000, "one", maxLookaheadMs = 0))
    }

    @Test fun `hash version duration and language prerequisites are strict`() {
        for (timeline in listOf(map(hash = "wrong"), map(hash = "z".repeat(64)), map(version = " "),
            map(duration = 0), map(duration = -1), map(language = ""), map(language = "not a language"))) {
            assertFalse(timeline.valid)
            assertNull(timeline.recovery(4_000, "one"))
        }
    }

    @Test fun `declared languages use validated alignment without punctuation or attention heuristics`() {
        for (language in listOf("en", "en-US", "ru", "ja", "zh-CN", "ar")) {
            val timeline = map(listOf(sentence("a", 1_000, 3_000, text = "ことば")), language = language)
            assertTrue(timeline.valid)
            assertEquals(1_000L, timeline.recovery(2_000, "one")?.positionMs)
            assertEquals(3_000L, timeline.softPause(2_000, 4_000, "one")?.positionMs)
        }
    }

    @Test fun `unknown language does not promote otherwise valid text into semantic readiness`() {
        for (language in listOf("und", "UND", "und-US", "und-Latn")) {
            val timeline = map(language = language)
            assertTrue(timeline.valid)
            assertFalse(timeline.hasVerifiedSentences)
            assertNull(timeline.recovery(4_000, "one"))
            assertNull(timeline.softPause(2_000, 8_000, "one"))
        }
    }

    @Test fun `chapters must cover the asset once in order without gaps or duplicate ids`() {
        val malformed = listOf(emptyList(), chapters.reversed(),
            listOf(NarrativeChapter("one", 1, 20_000)),
            listOf(NarrativeChapter("one", 0, 10_000), NarrativeChapter("one", 10_000, 20_000)),
            listOf(NarrativeChapter("one", 0, 9_999), NarrativeChapter("two", 10_000, 20_000)),
            listOf(NarrativeChapter("one", 0, 10_001), NarrativeChapter("two", 10_000, 20_000)),
            listOf(NarrativeChapter("one", 0, 19_999)))
        malformed.forEach { assertFalse(map(emptyList(), chapters = it).valid) }
    }

    @Test fun `bad annotation timestamps ids references and missing verified text invalidate the map`() {
        val bad = listOf(sentence("a", -1, 3_000), sentence("a", 3_000, 3_000),
            sentence("a", 3_000, 2_999), sentence("a", 9_000, 10_001),
            sentence("a", 10_500, 20_001, "two"), sentence("a", 1_000, 3_000, "missing"),
            sentence("", 1_000, 3_000), sentence("a", 1_000, 3_000, text = " "),
            sentence("a", 1_000, 3_000, text = null))
        bad.forEach { assertFalse(map(listOf(it)).valid) }
        assertFalse(map(listOf(sentence("same", 1_000, 3_000), sentence("same", 4_000, 5_000))).valid)
    }

    @Test fun `overlapping or reordered verified sentences invalidate all semantic decisions`() {
        for (annotations in listOf(listOf(sentence("a", 1_000, 4_000), sentence("b", 3_000, 6_000)),
            listOf(sentence("b", 4_000, 6_000), sentence("a", 1_000, 3_000)))) {
            val timeline = map(annotations)
            assertFalse(timeline.valid)
            assertNull(timeline.recovery(5_000, "one"))
            assertNull(timeline.softPause(2_000, 9_000, "one"))
        }
    }

    @Test fun `advisory sentence crossing a chapter is retained but never selected`() {
        val timeline = map(listOf(sentence("cross", 9_000, 11_000, reliability = NarrativeReliability.ADVISORY),
            sentence("next", 11_500, 14_000, "two")))
        assertTrue(timeline.valid)
        assertNull(timeline.recovery(9_900, "one"))
        assertNull(timeline.softPause(9_500, 12_000, "one"))
        assertEquals(11_500L, timeline.recovery(13_000, "two")?.positionMs)
    }

    @Test fun `authored chapter annotations must agree with their chapter range`() {
        val partial = NarrativeAnnotation("partial", "one", 1_000, 3_000, NarrativeKind.CHAPTER,
            NarrativeSource.AUTHORED_CHAPTER, NarrativeReliability.VERIFIED)
        assertFalse(map(listOf(partial)).valid)
    }

    @Test fun `invalid acoustic interval crossing chapter also fails closed`() {
        val timeline = map(listOf(NarrativeAnnotation("speech", "one", 9_000, 11_000, NarrativeKind.SPEECH,
            NarrativeSource.ACOUSTIC_VAD, NarrativeReliability.VERIFIED)))
        assertFalse(timeline.valid)
    }

    @Test fun `metadata is copied so preparation input mutation cannot affect driving decisions`() {
        val mutableChapters = chapters.toMutableList()
        val mutableAnnotations = mutableListOf(sentence("a", 1_000, 3_000))
        val timeline = map(mutableAnnotations, mutableChapters)
        mutableChapters.clear(); mutableAnnotations.clear()
        assertTrue(timeline.valid)
        assertEquals(1_000L, timeline.recovery(2_000, "one")?.positionMs)
        assertEquals(2, timeline.chapters.size)
        assertEquals(1, timeline.annotations.size)
    }

    @Test fun `repeated interruptions and queries are deterministic and do not mutate the timeline`() {
        val timeline = map()
        val expected = timeline.recovery(5_500, "one")
        repeat(1_000) {
            assertEquals(expected, timeline.recovery(5_500, "one"))
            assertEquals(6_000L, timeline.softPause(4_000, 8_000, "one")?.positionMs)
        }
        assertEquals(5, timeline.annotations.size)
    }

    @Test fun `seek-like position sweeps never skip unheard text or cross requested chapter`() {
        val timeline = map()
        for (position in 0L..20_000L step 100) {
            val chapter = if (position < 10_000) "one" else "two"
            timeline.recovery(position, chapter)?.let {
                assertTrue(it.positionMs <= position - 600)
                assertTrue(position - it.positionMs <= 5_000)
                assertTrue(it.positionMs >= if (chapter == "one") 0 else 10_000)
            }
            timeline.softPause(position, position + 8_000, chapter)?.let {
                assertTrue(it.positionMs > position && it.positionMs - position <= 8_000)
                assertTrue(it.positionMs <= if (chapter == "one") 10_000 else 20_000)
            }
        }
    }
}
