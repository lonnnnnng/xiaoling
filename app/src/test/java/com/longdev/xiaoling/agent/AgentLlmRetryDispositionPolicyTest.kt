package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLlmRetryDispositionPolicyTest {
    @Test
    fun transientPlanningFailureBeforeAnyToolCanStartIndependentRun() {
        assertEquals(
            AgentLlmRetryDisposition.RETRY_WITHOUT_CONFIRMATION,
            AgentLlmRetryDispositionPolicy.classify(
                phase = AgentLlmPhase.PLAN,
                kind = AgentLlmFailureKind.CONNECTION,
                completedToolCount = 0,
            ),
        )
    }

    @Test
    fun transientPlanningFailureAfterVerifiedToolRequiresConfirmation() {
        assertEquals(
            AgentLlmRetryDisposition.RETRY_WITH_CONFIRMATION,
            AgentLlmRetryDispositionPolicy.classify(
                phase = AgentLlmPhase.PLAN,
                kind = AgentLlmFailureKind.TIMEOUT,
                completedToolCount = 1,
            ),
        )
    }

    @Test
    fun providerConfigurationFailureNeverClaimsDirectRetry() {
        assertEquals(
            AgentLlmRetryDisposition.CONFIGURATION_REQUIRED,
            AgentLlmRetryDispositionPolicy.classify(
                phase = AgentLlmPhase.PLAN,
                kind = AgentLlmFailureKind.AUTHENTICATION,
                completedToolCount = 0,
            ),
        )
    }

    @Test
    fun summaryFailureIsCompletedByLocalFallback() {
        assertEquals(
            AgentLlmRetryDisposition.LOCAL_FALLBACK_COMPLETED,
            AgentLlmRetryDispositionPolicy.classify(
                phase = AgentLlmPhase.SUMMARIZE,
                kind = AgentLlmFailureKind.CONNECTION,
                completedToolCount = 1,
            ),
        )
    }
}
