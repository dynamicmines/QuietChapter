package com.quietchapter.recovery

import org.junit.Assert.*
import org.junit.Test

class RecoveryTicketTest {
    @Test fun pausedClockAcknowledgementSettlesWithoutNewTicket() { val t=RecoveryTicket();t.remember("book",1,12300);t.settle("book",1,12347);assertEquals(12347L,t.consume("book",1,12347));assertFalse(t.pending) }
    @Test fun settleCannotReplaceIdentityOrLargeSeek() { val t=RecoveryTicket();t.remember("book",1,8000);t.settle("other",1,8050);t.settle("book",2,8050);t.settle("book",1,12000);assertEquals(8000L,t.consume("book",1,8000)) }
    @Test fun cancelledTicketCannotBeResurrectedByClockUpdate() { val t=RecoveryTicket();t.remember("book",1,8000);t.clear();t.settle("book",1,8001);assertFalse(t.pending) }

    @Test fun repeatedSettlementCannotCreepBeyondOriginalPause() { val t=RecoveryTicket();t.remember("book",1,8000);t.settle("book",1,8800);t.settle("book",1,9600);t.settle("book",1,10400);assertEquals(8800L,t.consume("book",1,8800)) }

    @Test fun consumeOnlyOnce() { val t=RecoveryTicket();t.remember("book",1,8000);assertEquals(8000L,t.consume("book",1,8050));assertNull(t.consume("book",1,8050)) }
    @Test fun manualOverrideClears() { val t=RecoveryTicket();t.remember("book",1,8000);t.clear();assertFalse(t.pending);assertNull(t.consume("book",1,8000)) }
    @Test fun mediaReplacementRejects() { val t=RecoveryTicket();t.remember("book",1,8000);assertNull(t.consume("chapter",1,8000)) }
    @Test fun revisionReplacementRejects() { val t=RecoveryTicket();t.remember("book",1,8000);assertNull(t.consume("book",2,8000)) }
    @Test fun unexpectedSeekRejectsAndConsumes() { val t=RecoveryTicket();t.remember("book",1,8000);assertNull(t.consume("book",1,10000));assertFalse(t.pending) }
    @Test fun processRecreationHasNoPendingIntent() { val old=RecoveryTicket();old.remember("book",1,8000);assertFalse(RecoveryTicket().pending) }
    @Test fun invalidPositionRejects() { val t=RecoveryTicket();t.remember("",1,8000);assertFalse(t.pending);t.remember("book",1,-1);assertFalse(t.pending) }
}
