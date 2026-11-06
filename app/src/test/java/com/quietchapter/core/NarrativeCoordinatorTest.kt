package com.quietchapter.core

import org.junit.Assert.*
import org.junit.Test

class NarrativeCoordinatorTest {
    private fun pending()=PlaybackCoordinator().apply { userPlay(); assertEquals(PlaybackAction.NONE,onNavigation(NavigationEvent(0,true,7),0,true));assertEquals("PAUSE_PENDING",state) }
    @Test fun defaultStillPausesImmediately() { val c=PlaybackCoordinator();c.userPlay();assertEquals(PlaybackAction.PAUSE,c.onNavigation(NavigationEvent(0,true,7),0));assertEquals("AUTO_PAUSED",c.state) }
    @Test fun commitOnlyOnce() { val c=pending();assertEquals(PlaybackAction.PAUSE,c.commitScheduledPause());assertEquals(PlaybackAction.NONE,c.commitScheduledPause()) }
    @Test fun manualPauseCancelsAuthority() { val c=pending();c.userPause();assertEquals(PlaybackAction.NONE,c.commitScheduledPause());c.onNavigation(NavigationEvent(1000,true,60),1000);assertEquals(PlaybackAction.NONE,c.onNavigation(NavigationEvent(3100,true,60),3100));assertEquals("USER_PAUSED",c.state) }
    @Test fun focusInterruptsWithoutWaiting() { val c=pending();assertEquals(PlaybackAction.PAUSE,c.interrupt("focus"));assertEquals(PlaybackAction.NONE,c.commitScheduledPause());assertEquals("INTERRUPTED",c.state) }
    @Test fun sourceLossCancelsUnexecutedPredictionWithoutPlay() { val c=pending();assertEquals(PlaybackAction.NONE,c.onNavigation(NavigationEvent(100,false,null,signal=NavigationSignal.DISCONNECTED),100));assertEquals("NORMAL",c.state);assertEquals(PlaybackAction.NONE,c.commitScheduledPause()) }
    @Test fun expiredPredictionNeverCreatesResume() { val c=pending();assertEquals(PlaybackAction.NONE,c.onTick(5001));assertEquals("NORMAL",c.state);assertEquals(PlaybackAction.NONE,c.commitScheduledPause()) }
    @Test fun invalidObservationCancelsPrediction() { val c=pending();c.contextRejected(false);assertEquals("NORMAL",c.state);assertEquals(PlaybackAction.NONE,c.commitScheduledPause()) }
    @Test fun clearBeforeBoundaryDoesNotIssueAnotherPlay() { val c=pending();c.onNavigation(NavigationEvent(1000,true,60),1000);assertEquals(PlaybackAction.NONE,c.onNavigation(NavigationEvent(3100,true,60),3100));assertEquals("NORMAL",c.state) }
    @Test fun userPlayWhilePendingDoesNotResetDecision() { val c=pending();assertEquals(PlaybackAction.NONE,c.userPlay());assertEquals("PAUSE_PENDING",c.state) }
    @Test fun conflictingTimestampCancelsPending() { val c=pending();c.onNavigation(NavigationEvent(0,true,60),1);assertEquals("NORMAL",c.state);assertEquals(PlaybackAction.NONE,c.commitScheduledPause()) }
    @Test fun freshClearCancelsPendingImmediately() { val c=pending();assertEquals(PlaybackAction.NONE,c.onNavigation(NavigationEvent(100,true,60),100));assertEquals("NORMAL",c.state) }
    @Test fun exactDuplicateKeepsOriginalPending() { val c=pending();assertEquals(PlaybackAction.NONE,c.onNavigation(NavigationEvent(0,true,7),1));assertEquals("PAUSE_PENDING",c.state) }
    @Test fun cancelledScheduleCanAcceptFreshImminentInput() { val c=pending();c.cancelScheduledPause();assertEquals(PlaybackAction.PAUSE,c.onNavigation(NavigationEvent(100,true,0),100)) }
}
