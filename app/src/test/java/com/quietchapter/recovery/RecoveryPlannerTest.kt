package com.quietchapter.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPlannerTest {
    private val planner = RecoveryPlanner()

    @Test fun `exact mode does not rewind despite available acoustic boundaries`() {
        assertEquals(RecoveryDecision(10_000, RecoveryStrategy.EXACT, 0),
            planner.plan(10_000, 20_000, boundariesMs = listOf(8_000), mode = RecoveryMode.EXACT))
    }

    @Test fun `fixed mode ignores acoustic index and replays three seconds`() {
        assertEquals(RecoveryDecision(7_000, RecoveryStrategy.FIXED_BACKTRACK, 3_000),
            planner.plan(10_000, 20_000, boundariesMs = listOf(8_000), mode = RecoveryMode.FIXED_BACKTRACK))
    }

    @Test fun `acoustic mode chooses the latest eligible preceding boundary`() {
        assertEquals(RecoveryDecision(8_200, RecoveryStrategy.ACOUSTIC_BOUNDARY, 1_800),
            planner.plan(10_000, 20_000, boundariesMs = listOf(6_000, 8_200, 9_500, 10_100), mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `acoustic boundary at minimum replay distance is eligible`() {
        assertEquals(RecoveryDecision(9_400, RecoveryStrategy.ACOUSTIC_BOUNDARY, 600),
            planner.plan(10_000, 20_000, boundariesMs = listOf(9_400), mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `acoustic boundary at maximum backtrack distance is eligible`() {
        assertEquals(RecoveryDecision(5_000, RecoveryStrategy.ACOUSTIC_BOUNDARY, 5_000),
            planner.plan(10_000, 20_000, boundariesMs = listOf(5_000), mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `unavailable or unusable boundaries fall back instead of skipping ahead`() {
        listOf(emptyList(), listOf(-1, 0, 4_999, 9_401, 10_000, 10_001, Long.MAX_VALUE)).forEach { boundaries ->
            assertEquals(RecoveryDecision(7_000, RecoveryStrategy.FIXED_FALLBACK, 3_000),
                planner.plan(10_000, 20_000, boundariesMs = boundaries, mode = RecoveryMode.ACOUSTIC))
        }
    }

    @Test fun `duplicates and reordering do not change or mutate the acoustic decision`() {
        val unordered = mutableListOf(8_200L, -1, 6_000, 8_200, 15_000, 7_000)
        val original = unordered.toList()
        val first = planner.plan(10_000, 20_000, boundariesMs = unordered, mode = RecoveryMode.ACOUSTIC)
        assertEquals(first, planner.plan(10_000, 20_000, boundariesMs = unordered.reversed(), mode = RecoveryMode.ACOUSTIC))
        assertEquals(8_200, first.targetPositionMs)
        assertEquals(original, unordered)
    }

    @Test fun `whole book fixed recovery cannot cross the current chapter`() {
        assertEquals(RecoveryDecision(24_000, RecoveryStrategy.FIXED_BACKTRACK, 2_000),
            planner.plan(26_000, 45_374, chapterStartMs = 24_000, mode = RecoveryMode.FIXED_BACKTRACK))
    }

    @Test fun `whole book acoustic recovery ignores earlier chapters`() {
        assertEquals(RecoveryDecision(25_000, RecoveryStrategy.ACOUSTIC_BOUNDARY, 1_000),
            planner.plan(26_000, 45_374, chapterStartMs = 24_000,
                boundariesMs = listOf(23_000, 24_000, 25_000, 25_500), mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `clipped chapter recovery uses relative positions`() {
        assertEquals(RecoveryDecision(900, RecoveryStrategy.ACOUSTIC_BOUNDARY, 1_100),
            planner.plan(2_000, 21_374, boundariesMs = listOf(900), mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `a short chapter prefix replays only available content`() {
        assertEquals(RecoveryDecision(0, RecoveryStrategy.FIXED_FALLBACK, 250),
            planner.plan(250, 10_000, mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `chapter start and empty content never produce negative positions`() {
        RecoveryMode.entries.forEach { mode ->
            val result = planner.plan(0, 0, boundariesMs = listOf(-1, 0, 1), mode = mode)
            assertEquals(0, result.targetPositionMs)
            assertEquals(0, result.replayMs)
        }
    }

    @Test fun `unknown duration retains position instead of clamping to zero`() {
        listOf(-1L, Long.MIN_VALUE + 1).forEach { duration ->
            assertEquals(RecoveryDecision(7_000, RecoveryStrategy.FIXED_FALLBACK, 3_000),
                planner.plan(10_000, duration, mode = RecoveryMode.ACOUSTIC))
        }
    }

    @Test fun `contradictory chapter or pause bounds disable replay`() {
        val inputs = listOf(
            Triple(-1L, 20_000L, 0L), Triple(10_000L, 20_000L, -1L),
            Triple(10_000L, 20_000L, 10_001L), Triple(21_000L, 20_000L, 0L),
            Triple(10_000L, 20_000L, 30_000L),
        )
        inputs.forEach { (position, duration, chapter) ->
            val result = planner.plan(position, duration, chapter, listOf(8_000), RecoveryMode.ACOUSTIC)
            assertEquals(RecoveryStrategy.INVALID_CONTEXT, result.strategy)
            assertEquals(0, result.replayMs)
            assertTrue(result.targetPositionMs in 0..duration)
        }
    }

    @Test fun `near Long max positions do not overflow backward windows`() {
        assertEquals(RecoveryDecision(Long.MAX_VALUE - 1_000, RecoveryStrategy.ACOUSTIC_BOUNDARY, 1_000),
            planner.plan(Long.MAX_VALUE, -1,
                boundariesMs = listOf(-1, Long.MIN_VALUE, Long.MAX_VALUE - 1_000, Long.MAX_VALUE),
                mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `largest valid configured window does not overflow`() {
        assertEquals(RecoveryDecision(0, RecoveryStrategy.FIXED_BACKTRACK, Long.MAX_VALUE),
            RecoveryPlanner(Long.MAX_VALUE, Long.MAX_VALUE, 0)
                .plan(Long.MAX_VALUE, -1, mode = RecoveryMode.FIXED_BACKTRACK))
    }

    @Test fun `zero configured window does not replay even in acoustic mode`() {
        assertEquals(RecoveryDecision(100, RecoveryStrategy.FIXED_FALLBACK, 0),
            RecoveryPlanner(0, 0, 0).plan(100, -1, boundariesMs = listOf(99, 100), mode = RecoveryMode.ACOUSTIC))
    }

    @Test fun `short repeated narrative interruption does not seek the recent anchor again`() {
        val d=planner.plan(11480,45374,11450,emptyList(),RecoveryMode.NARRATIVE)
        assertEquals(11480L,d.targetPositionMs);assertEquals(RecoveryStrategy.EXACT,d.strategy)
    }

    @Test fun `narrative fallback cannot walk back through an already used anchor`() {
        val d=planner.plan(12200,45374,11450,listOf(9920),RecoveryMode.NARRATIVE)
        assertEquals(11450L,d.targetPositionMs);assertEquals(750L,d.replayMs)
    }

    @Test fun `invalid configuration is rejected`() {
        listOf(Triple(-1L, 0L, 0L), Triple(100L, -1L, 0L), Triple(100L, 101L, 0L),
            Triple(100L, 0L, -1L), Triple(100L, 0L, 101L)).forEach { (max, fallback, min) ->
            assertTrue(runCatching { RecoveryPlanner(max, fallback, min) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `repeated planning is deterministic and obeys position bounds across modes`() {
        for (position in 0L..30_000L step 317) {
            val chapter = if (position >= 24_000) 24_000L else 0L
            RecoveryMode.entries.forEach { mode ->
                val candidates = listOf(position - 200, position - 1_200, position - 4_500, position + 100)
                val first = planner.plan(position, 45_374, chapter, candidates, mode)
                assertEquals(first, planner.plan(position, 45_374, chapter, candidates, mode))
                assertTrue(first.targetPositionMs in chapter..position)
                assertEquals(position - first.targetPositionMs, first.replayMs)
                assertTrue(first.replayMs in 0..5_000)
            }
        }
    }
}
