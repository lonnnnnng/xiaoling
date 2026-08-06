package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCancelCompletionPresentationTest {
    @Test
    fun scheduledCancellationShowsCommittedStateWithoutInternalIds() {
        val presentation = presentTaskCancelCompletion(contextFor("计划已取消，不会再执行。当前状态：CANCELLED。"))

        assertEquals("assistant", presentation?.role)
        assertTrue(presentation?.text.orEmpty().contains("任务已取消：每日回顾"))
        assertTrue(presentation?.text.orEmpty().contains("旧运行记录保持不变"))
        assertFalse(presentation?.text.orEmpty().contains("workflow-run-private"))
    }

    @Test
    fun stoppedAndStopRequestedUseDistinctStableMessages() {
        val stopped = presentTaskCancelCompletion(contextFor("后台任务已停止，关联 Agent、工作流和调度实例已收敛。"))
        val requested = presentTaskCancelCompletion(contextFor("已请求停止后台任务，停止意图已持久化，稍后可在任务中心查看终态。"))

        assertTrue(stopped?.text.orEmpty().startsWith("后台任务已停止："))
        assertTrue(requested?.text.orEmpty().startsWith("已请求停止任务："))
    }

    @Test
    fun unverifiedOrUnknownCancellationDoesNotCreateFinalMessage() {
        assertNull(
            presentTaskCancelCompletion(
                contextFor(
                    rawResult = "计划已取消，不会再执行。",
                    status = AgentVerificationStatus.READABLE_ONLY,
                ),
            ),
        )
        assertNull(presentTaskCancelCompletion(contextFor("模型声称已经取消")))
    }

    @Test
    fun duplicateCancelExecutionsFailClosedAndNameIsSingleLine() {
        val context = contextFor("计划已取消，不会再执行。", name = "  每日\n回顾  ")
            .copy(toolExecutions = listOf(
                contextFor("计划已取消，不会再执行。", name = "每日回顾").toolExecutions.single(),
                contextFor("计划已取消，不会再执行。", name = "每日回顾").toolExecutions.single(),
            ))

        assertNull(presentTaskCancelCompletion(context))
    }

    private fun contextFor(
        rawResult: String,
        name: String = "每日回顾",
        status: AgentVerificationStatus = AgentVerificationStatus.VERIFIED,
    ): VerifiedAgentContext {
        return VerifiedAgentContext(
            runId = "run-cancel-1",
            toolName = "tasks.cancel",
            arguments = mapOf("name" to name),
            success = true,
            verificationStatus = status,
            rawResult = rawResult,
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "tasks.cancel",
                    arguments = mapOf("name" to name),
                    success = true,
                    verificationStatus = status,
                    rawResult = rawResult,
                ),
            ),
        )
    }
}
