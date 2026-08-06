package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCancelWorkflowRefreshPolicyTest {
    @Test
    fun verifiedCancellationRefreshesWorkflowSnapshot() {
        assertTrue(shouldRefreshWorkflowsAfterTaskCancel(contextFor()))
    }

    @Test
    fun readableOnlyCancellationDoesNotRefresh() {
        assertFalse(
            shouldRefreshWorkflowsAfterTaskCancel(
                contextFor(status = AgentVerificationStatus.READABLE_ONLY),
            ),
        )
    }

    @Test
    fun modelLikeResultDoesNotRefresh() {
        assertFalse(shouldRefreshWorkflowsAfterTaskCancel(contextFor(rawResult = "模型声称任务已取消")))
    }

    @Test
    fun duplicateCancellationExecutionsDoNotRefresh() {
        val execution = contextFor().toolExecutions.single()
        assertFalse(
            shouldRefreshWorkflowsAfterTaskCancel(
                contextFor().copy(toolExecutions = listOf(execution, execution)),
            ),
        )
    }

    private fun contextFor(
        rawResult: String = "任务“每日回顾”：计划已取消，不会再执行。当前状态：CANCELLED。",
        status: AgentVerificationStatus = AgentVerificationStatus.VERIFIED,
    ): VerifiedAgentContext {
        return VerifiedAgentContext(
            runId = "run-cancel-refresh",
            toolName = "tasks.cancel",
            arguments = mapOf("name" to "每日回顾"),
            success = true,
            verificationStatus = status,
            rawResult = rawResult,
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "tasks.cancel",
                    arguments = mapOf("name" to "每日回顾"),
                    success = true,
                    verificationStatus = status,
                    rawResult = rawResult,
                ),
            ),
        )
    }
}
