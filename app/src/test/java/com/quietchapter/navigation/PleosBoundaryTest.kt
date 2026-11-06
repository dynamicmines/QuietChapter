package com.quietchapter.navigation

import org.junit.Assert.*
import org.junit.Test

class PleosBoundaryTest {
    @Test fun `invalid distances stay unknown instead of becoming zero distance maneuvers`() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertNull(PleosTbtEntry("UNKNOWN", null, distance, "").usableDistanceMeters)
        }
        assertEquals(0.0, PleosTbtEntry("RAW_TEST_TYPE", null, 0.0, "").usableDistanceMeters!!, 0.0)
    }
    @Test fun `ordinary native mode does not imply runtime live proof and simulation remains explicit`() {
        val unknown = PleosNavigationSnapshot(0, 0, PleosConnectionHealth.DISCONNECTED, null, null, null, emptyList())
        assertNull(unknown.routeActive)
        assertFalse(unknown.reportsSdkSimulation)
        assertTrue(unknown.copy(rawNaviMode = "SIMULATION_GUIDING").reportsSdkSimulation)
    }
}
