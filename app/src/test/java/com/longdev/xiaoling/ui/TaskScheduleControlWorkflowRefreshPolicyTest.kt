package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScheduleControlWorkflowRefreshPolicyTest {
    @Test
    fun verifiedPauseAndResumeRefreshWorkflowSnapshot() {
        assertTrue(shouldRefreshWorkflowsAfterTaskScheduleControl(contextFor("tasks.pause", "周期计划已暂停，后续不会生成新的执行实例")))
        assertTrue(shouldRefreshWorkflowsAfterTaskScheduleControl(contextFor("tasks.resume", "周期计划已恢复")))
    }

    @Test
    fun unverifiedOrModelLikeControlDoesNotRefresh() {
        assertFalse(
            shouldRefreshWorkflowsAfterTaskScheduleControl(
                contextFor(
                    toolName = "tasks.pause",
                    stateText = "周期计划已暂停，后续不会生成新的执行实例",
                    status = AgentVerificationStatus.READABLE_ONLY,
                ),
            ),
        )
        assertFalse(
            shouldRefreshWorkflowsAfterTaskScheduleControl(
                contextFor("tasks.resume", "模型声称周期计划已恢复"),
            ),
        )
    }

    private fun contextFor(
        toolName: String,
        stateText: String,
        status: AgentVerificationStatus = AgentVerificationStatus.VERIFIED,
    ): VerifiedAgentContext {
        val result = "任务“每日回顾”：$stateText。每日。"
        val execution = VerifiedToolExecution(
            toolName = toolName,
            arguments = mapOf("name" to "每日回顾"),
            success = true,
            verificationStatus = status,
            rawResult = result,
        )
        return VerifiedAgentContext(
            runId = "run-schedule-refresh",
            toolName = toolName,
            arguments = execution.arguments,
            success = true,
            verificationStatus = status,
            rawResult = result,
            toolExecutions = listOf(execution),
        )
    }
}
