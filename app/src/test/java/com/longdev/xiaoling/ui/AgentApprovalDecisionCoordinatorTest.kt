package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.ApprovalDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalDecisionCoordinatorTest {
    @Test
    fun claimedDecisionCompletesRegisteredWaiterOnlyOnce() = runTest {
        val coordinator = AgentApprovalDecisionCoordinator()
        val ticket = coordinator.register(
            requestId = "approval-1",
            conversationId = "conversation-1",
        )

        val claim = coordinator.claim("approval-1")

        assertNotNull(claim)
        assertNull(coordinator.claim("approval-1"))
        val decision = ApprovalDecision(approved = true, reason = "用户已批准")
        assertTrue(coordinator.complete(checkNotNull(claim), decision))
        assertEquals(decision, ticket.awaitDecision())
        assertFalse(coordinator.complete(claim, decision))
    }

    @Test
    fun failedPersistenceReleasesClaimForRetryWithoutCompletingWaiter() = runTest {
        val coordinator = AgentApprovalDecisionCoordinator()
        val ticket = coordinator.register(
            requestId = "approval-1",
            conversationId = "conversation-1",
        )
        val failedClaim = checkNotNull(coordinator.claim("approval-1"))

        assertTrue(coordinator.release(failedClaim))

        val retryClaim = coordinator.claim("approval-1")
        assertNotNull(retryClaim)
        assertFalse(coordinator.release(failedClaim))
        val decision = ApprovalDecision(approved = false, reason = "用户已拒绝")
        assertTrue(coordinator.complete(checkNotNull(retryClaim), decision))
        assertEquals(decision, ticket.awaitDecision())
    }

    @Test
    fun replacingWaiterCancelsOldTicketAndStaleTicketCannotAffectNewWaiter() = runTest {
        val coordinator = AgentApprovalDecisionCoordinator()
        val oldTicket = coordinator.register("approval-old", "conversation-old")
        val oldClaim = checkNotNull(coordinator.claim("approval-old"))

        val newTicket = coordinator.register("approval-new", "conversation-new")

        val oldFailure = runCatching { oldTicket.awaitDecision() }.exceptionOrNull()
        assertTrue(oldFailure is CancellationException)
        val staleDecision = ApprovalDecision(approved = true, reason = "过期决定")
        assertFalse(coordinator.complete(oldClaim, staleDecision))
        assertFalse(coordinator.clear(oldTicket))

        val newClaim = checkNotNull(coordinator.claim("approval-new"))
        val newDecision = ApprovalDecision(approved = false, reason = "当前决定")
        assertTrue(coordinator.complete(newClaim, newDecision))
        assertEquals(newDecision, newTicket.awaitDecision())
    }

    @Test
    fun cancellingActiveWaiterInvalidatesClaimUntilItsOwnCleanup() = runTest {
        val coordinator = AgentApprovalDecisionCoordinator()
        val ticket = coordinator.register("approval-1", "conversation-1")
        val claim = checkNotNull(coordinator.claim("approval-1"))

        assertTrue(coordinator.cancelActive())

        val failure = runCatching { ticket.awaitDecision() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(coordinator.cancelActive())
        assertNull(coordinator.claim("approval-1"))
        assertFalse(
            coordinator.complete(
                claim,
                ApprovalDecision(approved = true, reason = "迟到决定"),
            ),
        )
        assertTrue(coordinator.clear(ticket))
    }

    @Test
    fun stalePersistenceResultCancelsClaimedWaiterInsteadOfExecutingTool() = runTest {
        val coordinator = AgentApprovalDecisionCoordinator()
        val ticket = coordinator.register("approval-1", "conversation-1")
        val claim = checkNotNull(coordinator.claim("approval-1"))

        assertTrue(coordinator.cancel(claim))

        val failure = runCatching { ticket.awaitDecision() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertNull(coordinator.claim("approval-1"))
        assertFalse(
            coordinator.complete(
                claim,
                ApprovalDecision(approved = true, reason = "未持久化决定"),
            ),
        )
        assertTrue(coordinator.clear(ticket))
    }
}
