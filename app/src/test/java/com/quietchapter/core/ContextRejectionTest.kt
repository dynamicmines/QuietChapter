package com.quietchapter.core

import org.junit.Assert.*
import org.junit.Test

class ContextRejectionTest {
    @Test fun rejectedObservationBreaksClearSpan() {
        val c=PlaybackCoordinator();c.userPlay()
        c.onNavigation(NavigationEvent(0,true,5),0)
        c.onNavigation(NavigationEvent(1000,true,60),1000)
        assertEquals(PlaybackAction.NONE,c.contextRejected(false))
        assertEquals(PlaybackAction.NONE,c.onNavigation(NavigationEvent(3100,true,60),3100))
        assertEquals("AUTO_PAUSED",c.state)
        assertEquals(PlaybackAction.PLAY,c.onNavigation(NavigationEvent(5200,true,60),5200))
    }
    @Test fun staleAdmissionRevokesAutomaticIntent() {
        val c=PlaybackCoordinator();c.userPlay();c.onNavigation(NavigationEvent(0,true,5),0)
        assertEquals(PlaybackAction.PAUSE,c.contextRejected(true));assertEquals("INTERRUPTED",c.state)
        c.onNavigation(NavigationEvent(1000,true,60),1000);c.onNavigation(NavigationEvent(3100,true,60),3100)
        assertEquals("INTERRUPTED",c.state)
    }
}
