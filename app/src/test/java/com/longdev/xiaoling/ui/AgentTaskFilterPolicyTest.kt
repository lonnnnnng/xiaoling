package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskFilterPolicyTest {
    @Test
    fun needsConfirmationIncludesOnlyRetryableRunsThatRequireUserConfirmation() {
        assertTrue(
            AgentTaskFilter.NEEDS_CONFIRMATION.matches(
                status = AgentRunStatus.CANCELLED,
                retryEligibility = AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            ),
        )
        assertFalse(
            AgentTaskFilter.NEEDS_CONFIRMATION.matches(
                status = AgentRunStatus.FAILED,
                retryEligibility = AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            ),
        )
        assertFalse(
            AgentTaskFilter.NEEDS_CONFIRMATION.matches(
                status = AgentRunStatus.WAITING_APPROVAL,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
        assertFalse(
            AgentTaskFilter.NEEDS_CONFIRMATION.matches(
                status = AgentRunStatus.EXECUTING,
                retryEligibility = AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            ),
        )
    }

    @Test
    fun retryableFilterKeepsBothDirectAndConfirmationRetriesVisible() {
        assertTrue(
            AgentTaskFilter.RETRYABLE.matches(
                status = AgentRunStatus.FAILED,
                retryEligibility = AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            ),
        )
        assertTrue(
            AgentTaskFilter.RETRYABLE.matches(
                status = AgentRunStatus.CANCELLED,
                retryEligibility = AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            ),
        )
    }

    @Test
    fun interruptedFilterIncludesOnlySettledFailureAndCancellation() {
        assertTrue(
            AgentTaskFilter.INTERRUPTED.matches(
                status = AgentRunStatus.FAILED,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
        assertTrue(
            AgentTaskFilter.INTERRUPTED.matches(
                status = AgentRunStatus.CANCELLED,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
        assertFalse(
            AgentTaskFilter.INTERRUPTED.matches(
                status = AgentRunStatus.EXECUTING,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
    }

    @Test
    fun activeAndCompletedFiltersPreserveExistingStatusBoundaries() {
        assertTrue(
            AgentTaskFilter.ACTIVE.matches(
                status = AgentRunStatus.EXECUTING,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
        assertFalse(
            AgentTaskFilter.ACTIVE.matches(
                status = AgentRunStatus.CANCELLED,
                retryEligibility = AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            ),
        )
        assertTrue(
            AgentTaskFilter.COMPLETED.matches(
                status = AgentRunStatus.COMPLETED,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
    }

    @Test
    fun allFilterRemainsTheExplicitFallbackForEmptyInterruptedView() {
        assertTrue(
            AgentTaskFilter.ALL.matches(
                status = AgentRunStatus.COMPLETED,
                retryEligibility = AgentTaskRetryEligibility.NotRetryable,
            ),
        )
    }
}
