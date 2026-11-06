package com.quietchapter.narrative

import org.junit.Assert.*
import org.junit.Test

class AdaptiveNarrativeOrchestratorTest {
    private val engine = AdaptiveNarrativeOrchestrator()
    private fun timeline(quality: NarrativeReliability = NarrativeReliability.VERIFIED,
        source: NarrativeSource = NarrativeSource.TRANSCRIPT_ALIGNED_SENTENCE,
        start: Long = 1_000, end: Long = 3_000) = NarrativeTimeline("a".repeat(64), "en", 20_000, "reviewed-v1",
        listOf(NarrativeChapter("one", 0, 10_000), NarrativeChapter("two", 10_000, 20_000)),
        listOf(NarrativeAnnotation("sentence", "one", start, end, NarrativeKind.SENTENCE, source, quality, "Original text.")))
    private fun plan(
        timeline: NarrativeTimeline = timeline(), chapter: String = "one", position: Long = 2_000,
        now: Long = 10_000, sourceTime: Long = now, eta: Int? = 8,
        generation: Long = 1, revision: Int = 2, admitted: Boolean = true,
    ) = engine.propose(timeline, chapter, position, now, sourceTime, eta, generation, revision, admitted)
    private fun poll(plan: SoftPausePlan, position: Long = 2_500, now: Long = 10_500,
        generation: Long = 1, revision: Int = 2, eligible: Boolean = true) =
        engine.poll(plan, position, now, generation, revision, eligible)

    @Test fun `proposal retains exact metadata identity and two monotonic clocks`() {
        assertEquals(SoftPausePlan(3_000, 13_000, 9_800, 1, 2, "one", "sentence", 10_000),
            plan(sourceTime = 9_800))
    }

    @Test fun `admission is required and no route authority is invented`() {
        assertNull(plan(admitted = false))
        assertNull(plan(generation = -1))
        assertNull(plan(revision = -1))
    }

    @Test fun `missing or imminent ETA does not delay reactive pause`() {
        for (eta in listOf(null, Int.MIN_VALUE, -1, 0, 1)) assertNull(plan(eta = eta))
    }

    @Test fun `minimum supported ETA includes one second safety margin`() {
        val proposed = plan(eta = 2)!!
        assertEquals(3_000L, proposed.targetMs)
        assertEquals(11_000L, proposed.deadlineElapsedMs)
        assertNull(plan(eta = 2, sourceTime = 9_999)) // Boundary would be one ms beyond safe window.
    }

    @Test fun `source age reduces the available interval instead of resetting event time`() {
        val proposed = plan(timeline = timeline(end = 2_500), eta = 2, sourceTime = 9_500)!!
        assertEquals(10_500L, proposed.deadlineElapsedMs)
        assertEquals(2_500L, proposed.targetMs)
        assertNull(plan(timeline = timeline(end = 2_501), eta = 2, sourceTime = 9_500))
    }

    @Test fun `prediction older than five hundred milliseconds is rejected`() {
        assertNotNull(plan(sourceTime = 9_500))
        assertNull(plan(sourceTime = 9_499))
    }

    @Test fun `future and negative clocks cannot create a schedule`() {
        assertNull(plan(sourceTime = 10_001))
        assertNull(plan(sourceTime = -1))
        assertNull(plan(now = -1, sourceTime = -1))
        assertNull(plan(position = -1))
    }

    @Test fun `large ETA is widened safely and wait remains capped to three seconds`() {
        val proposed = plan(eta = Int.MAX_VALUE)!!
        assertEquals(13_000L, proposed.deadlineElapsedMs)
        assertEquals(3_000L, proposed.targetMs)
    }

    @Test fun `near Long maximum clock cannot overflow deadline`() {
        assertNull(plan(now = Long.MAX_VALUE, sourceTime = Long.MAX_VALUE))
        assertNull(plan(position = Long.MAX_VALUE))
    }

    @Test fun `acoustic silence cannot provide a semantic soft pause`() {
        assertNull(plan(timeline = timeline(source = NarrativeSource.ACOUSTIC_VAD)))
    }

    @Test fun `low reliability annotations leave reactive behavior available`() {
        assertNull(plan(timeline = timeline(quality = NarrativeReliability.ADVISORY)))
        assertNull(plan(timeline = timeline(quality = NarrativeReliability.REJECTED)))
    }

    @Test fun `missing annotations and invalid maps cannot provide a prediction`() {
        val original = timeline()
        val empty = NarrativeTimeline(original.assetSha256, original.language, original.durationMs,
            original.analysisVersion, original.chapters, emptyList())
        assertNull(plan(timeline = empty))
        assertNull(plan(timeline = timeline(start = -1)))
    }

    @Test fun `long sentence without a timely natural boundary does not get a fabricated end`() {
        assertNull(plan(timeline = timeline(end = 9_000)))
        assertNull(plan(timeline = timeline(end = 5_001)))
        assertEquals(5_000L, plan(timeline = timeline(end = 5_000))?.targetMs)
    }

    @Test fun `chapter and current position must correspond to timeline`() {
        assertNull(plan(chapter = "missing"))
        assertNull(plan(chapter = "two"))
        assertNull(plan(chapter = ""))
        assertNull(plan(position = 10_000))
        assertNull(plan(position = 20_001))
    }

    @Test fun `already reached boundary is not scheduled again`() {
        assertNull(plan(position = 3_000))
        assertNull(plan(position = 3_500))
    }

    @Test fun `normal progress waits until a verified boundary then pauses`() {
        val proposed = plan()!!
        assertEquals(SoftPauseDecision.WAIT, poll(proposed, position = 2_999))
        assertEquals(SoftPauseDecision.PAUSE_NOW, poll(proposed, position = 3_000))
    }

    @Test fun `sampled position beyond target still requests pause instead of repeated scheduling`() {
        assertEquals(SoftPauseDecision.PAUSE_NOW, poll(plan()!!, position = 3_300))
    }

    @Test fun `deadline pauses a stalled clock without granting resume`() {
        val proposed = plan()!!
        assertEquals(SoftPauseDecision.WAIT, poll(proposed, position = 2_000, now = 12_999))
        assertEquals(SoftPauseDecision.PAUSE_NOW, poll(proposed, position = 2_000, now = 13_000))
    }

    @Test fun `source loss or manual pause veto cancels even when boundary or deadline is due`() {
        val proposed = plan()!!
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, position = 3_000, eligible = false))
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, now = 13_000, eligible = false))
    }

    @Test fun `route replacement and seek or media revision invalidate a pending pause`() {
        val proposed = plan()!!
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, generation = 2))
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, generation = 0))
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, revision = 3))
    }

    @Test fun `expired context cancels before deadline handling and never implies completion`() {
        val proposed = plan()!!
        assertEquals(SoftPauseDecision.PAUSE_NOW, poll(proposed, now = 15_000))
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, now = 15_001))
    }

    @Test fun `regressed poll clock and negative position cancel`() {
        val proposed = plan(sourceTime = 9_800)!!
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, now = 9_999))
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, now = -1))
        assertEquals(SoftPauseDecision.CANCEL, poll(proposed, position = -1))
    }

    @Test fun `malformed or forged plan is rejected without throwing`() {
        val good = plan()!!
        val bad = listOf(good.copy(targetMs = -1), good.copy(sourceElapsedMs = -1),
            good.copy(createdElapsedMs = 9_999), good.copy(deadlineElapsedMs = 10_000),
            good.copy(deadlineElapsedMs = Long.MIN_VALUE), good.copy(deadlineElapsedMs = 13_001),
            good.copy(sourceElapsedMs = 9_499), good.copy(routeGeneration = -1),
            good.copy(mediaRevision = -1), good.copy(chapterId = ""), good.copy(annotationId = ""))
        bad.forEach { assertEquals(SoftPauseDecision.CANCEL, poll(it)) }
    }

    @Test fun `duplicate inputs are deterministic and do not extend freshness or mutate a plan`() {
        val original = plan()!!
        repeat(1_000) {
            assertEquals(original, plan())
            assertEquals(SoftPauseDecision.WAIT, poll(original))
        }
        assertEquals(13_000L, original.deadlineElapsedMs)
        assertEquals(10_000L, original.sourceElapsedMs)
    }

    @Test fun `a fresh tighter ETA cannot leave an unreachable sentence target scheduled`() {
        val original = plan(timeline = timeline(start = 6_800, end = 9_745), position = 6_850, eta = 7)!!
        val updated = engine.update(original, 6_950, 10_100, 10_100, 2, 1, 2, true)
        assertEquals(SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null), updated)
    }

    @Test fun `a tighter ETA that still fits shortens deadline and preserves original clocks`() {
        val original = plan()!!
        val updated = engine.update(original, 2_100, 10_100, 10_100, 2, 1, 2, true)
        assertEquals(SoftPauseDecision.WAIT, updated.decision)
        assertEquals(11_100L, updated.plan?.deadlineElapsedMs)
        assertEquals(original.sourceElapsedMs, updated.plan?.sourceElapsedMs)
        assertEquals(original.createdElapsedMs, updated.plan?.createdElapsedMs)
        assertEquals(original.targetMs, updated.plan?.targetMs)
        assertEquals(SoftPauseDecision.PAUSE_NOW, poll(updated.plan!!, position = 2_100, now = 11_100))
    }

    @Test fun `duplicate and later still imminent observations never extend old deadline`() {
        val original = plan()!!
        val duplicate = engine.update(original, 2_100, 10_100, 10_000, 8, 1, 2, true)
        val fresh = engine.update(original, 2_100, 10_100, 10_100, 8, 1, 2, true)
        assertEquals(SoftPauseUpdate(SoftPauseDecision.WAIT, original), duplicate)
        assertEquals(SoftPauseUpdate(SoftPauseDecision.WAIT, original), fresh)
        repeat(100) { assertEquals(fresh, engine.update(original, 2_100, 10_100, 10_100, 8, 1, 2, true)) }
    }

    @Test fun `far clear context withdraws even a now obsolete due prediction`() {
        val original = plan()!!
        assertEquals(SoftPauseUpdate(SoftPauseDecision.CANCEL, null),
            engine.update(original, 2_100, 10_100, 10_100, 9, 1, 2, true))
        assertEquals(SoftPauseUpdate(SoftPauseDecision.CANCEL, null),
            engine.update(original, 3_000, 10_100, 10_100, Int.MAX_VALUE, 1, 2, true))
    }

    @Test fun `current zero or one ETA requires immediate pause instead of waiting`() {
        val original = plan()!!
        for (eta in 0..1) assertEquals(SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null),
            engine.update(original, 2_100, 10_100, 10_100, eta, 1, 2, true))
    }

    @Test fun `invalid stale future and missing update data cannot keep a plan`() {
        val original = plan()!!
        for (source in listOf(-1L, 9_999, 10_101)) assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 2_100, 10_100, source, 2, 1, 2, true).decision)
        assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 2_100, 10_601, 10_100, 2, 1, 2, true).decision)
        for (eta in listOf(null, -1, Int.MIN_VALUE)) assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 2_100, 10_100, 10_100, eta, 1, 2, true).decision)
    }

    @Test fun `new route media revision or failed caller admission cancels an update`() {
        val original = plan()!!
        assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 2_100, 10_100, 10_100, 2, 2, 2, true).decision)
        assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 2_100, 10_100, 10_100, 2, 1, 3, true).decision)
        assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 3_000, 13_000, 13_000, 2, 1, 2, false).decision)
    }

    @Test fun `new observations cannot resurrect an expired original source TTL`() {
        val original = plan()!!
        val refreshed = engine.update(original, 2_500, 12_900, 12_900, 8, 1, 2, true).plan!!
        assertEquals(original, refreshed)
        assertEquals(SoftPauseDecision.CANCEL, poll(refreshed, now = 15_001))
        assertEquals(SoftPauseUpdate(SoftPauseDecision.CANCEL, null),
            engine.update(refreshed, 2_500, 15_001, 15_001, 8, 1, 2, true))
    }

    @Test fun `update rejects deadline addition overflow without extending old plan`() {
        val original = plan(now = Long.MAX_VALUE - 3_000, sourceTime = Long.MAX_VALUE - 3_000)!!
        assertEquals(Long.MAX_VALUE, original.deadlineElapsedMs)
        assertEquals(SoftPauseUpdate(SoftPauseDecision.CANCEL, null),
            engine.update(original, 2_100, Long.MAX_VALUE - 2_000, Long.MAX_VALUE - 2_000, 8, 1, 2, true))
    }

    @Test fun `an already due plan pauses only while fresh context remains valid`() {
        val original = plan()!!
        assertEquals(SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null),
            engine.update(original, 3_000, 10_100, 10_100, 2, 1, 2, true))
        assertEquals(SoftPauseUpdate(SoftPauseDecision.PAUSE_NOW, null),
            engine.update(original, 2_500, 13_000, 13_000, 2, 1, 2, true))
        assertEquals(SoftPauseDecision.CANCEL,
            engine.update(original, 3_000, 10_100, 10_101, 2, 1, 2, true).decision)
    }

    @Test fun `all earlier valid deadlines remain before the predicted event safety margin`() {
        for (eta in 2..20) for (age in 0L..500L step 100) {
            val proposed = plan(timeline = timeline(end = 2_200), eta = eta, sourceTime = 10_000 - age)!!
            assertTrue(proposed.deadlineElapsedMs <= proposed.sourceElapsedMs + eta.toLong() * 1_000 - 1_000)
            assertTrue(proposed.deadlineElapsedMs - proposed.createdElapsedMs <= 3_000)
            assertTrue(proposed.targetMs > 2_000)
        }
    }
}
