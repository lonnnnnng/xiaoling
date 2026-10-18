package com.longdev.xiaoling.ui

import org.junit.Assert.*
import org.junit.Test

class ConversationSendScopeTest {
    @Test fun oldCompletedResponseCannotEnterAnotherConversationOrReturnToSameConversation() {
        val scope=ConversationSendScope(); val a=scope.begin("A")
        assertTrue(scope.accepts(a,"A")); assertFalse(scope.accepts(a,"B"))
        assertTrue(scope.invalidate()); val b=scope.begin("B"); scope.invalidate(); val newA=scope.begin("A")
        assertFalse(scope.accepts(a,"A")); assertFalse(scope.accepts(b,"A")); assertTrue(scope.accepts(newA,"A"))
        scope.finish(a); assertTrue(scope.accepts(newA,"A")); scope.finish(newA); assertFalse(scope.accepts(newA,"A"))
    }
    @Test fun agentLaunchRevokesCompletedChatOwnerBeforeItsLateFinally() {
        val scope=ConversationSendScope(); val completed=scope.begin("A")
        assertTrue(scope.invalidate()); assertFalse(scope.accepts(completed,"A"))
        scope.finish(completed); assertFalse(scope.accepts(completed,"A"))
    }
}
