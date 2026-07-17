package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTaskRetryPolicyTest {
    @Test
    fun failedRunWithoutSuccessfulToolResultIsRetryableWithoutConfirmation() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(status = AgentRunStatus.FAILED),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun cancelledRunIsRetryableForRecovery() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(status = AgentRunStatus.CANCELLED),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun successfulWriteToolRequiresConfirmationBeforeRetry() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.FAILED,
                events = listOf(
                    event(
                        type = "tool.call.validated",
                        metadata = RunEventMetadata.ToolCall(
                            id = "tool-call-1",
                            toolName = "memory.remember",
                            risk = ToolRisk.REQUIRES_APPROVAL,
                            arguments = mapOf("content" to "用户喜欢紧凑界面"),
                        ),
                    ),
                    event(
                        type = "tool.result",
                        metadata = RunEventMetadata.ToolResult(
                            toolName = "memory.remember",
                            content = "已保存",
                            durationMs = 20,
                            success = true,
                            verified = true,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun budgetExhaustedRunIsRetryable() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(status = AgentRunStatus.BUDGET_EXHAUSTED),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun interruptedExecutionRequiresConfirmationBeforeRecovery() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.CANCELLED,
                events = listOf(
                    event(
                        type = "run.recovered",
                        metadata = RunEventMetadata.Recovery(
                            fromStatus = AgentRunStatus.EXECUTING,
                            toStatus = AgentRunStatus.CANCELLED,
                            reason = "应用重启后终止上次未完成 Agent 任务",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun cancelledToolExecutionRequiresConfirmationBeforeRetry() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.CANCELLED,
                steps = listOf(
                    step(type = AgentStepTypes.TOOL_EXECUTE, status = AgentStepStatus.CANCELLED),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun failedToolVerificationRequiresConfirmationBeforeRetry() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                steps = listOf(
                    step(type = AgentStepTypes.TOOL_VERIFY, status = AgentStepStatus.FAILED),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    private fun detail(
        status: AgentRunStatus,
        events: List<RunEventRecord> = emptyList(),
        steps: List<AgentStepRecord> = emptyList(),
    ): AgentRunDetailRecord {
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = "run-source",
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    goal = "重试任务",
                    status = status,
                    result = null,
                    errorMessage = "模拟失败",
                    createdAt = 1L,
                    updatedAt = 2L,
                    completedAt = 2L,
                ),
                steps = steps,
                events = events,
            ),
            approvals = emptyList(),
        )
    }

    private fun event(type: String, metadata: RunEventMetadata): RunEventRecord {
        return RunEventRecord(
            id = "event-$type",
            runId = "run-source",
            type = type,
            message = type,
            createdAt = 1L,
            metadata = metadata,
        )
    }

    private fun step(type: String, status: AgentStepStatus): AgentStepRecord {
        return AgentStepRecord(
            id = "step-$type",
            runId = "run-source",
            sequence = 1,
            type = type,
            status = status,
            title = type,
            detail = status.name,
            createdAt = 1L,
            completedAt = 2L,
        )
    }
}
