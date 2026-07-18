package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunResumePolicyTest {
    @Test
    fun waitingApprovalWithoutExecutionCanResumeInPlace() {
        val assessment = AgentRunResumePolicy.assess(detail(status = AgentRunStatus.WAITING_APPROVAL))

        assertEquals(AgentRunResumeKind.APPROVAL_WAIT, assessment.kind)
        assertTrue(assessment.canResumeInPlace)
    }

    @Test
    fun executionStartedRequiresSafeRestart() {
        val assessment = AgentRunResumePolicy.assess(
            detail(
                status = AgentRunStatus.WAITING_APPROVAL,
                steps = listOf(
                    step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED),
                    step("approval", AgentStepStatus.RUNNING, sequence = 2),
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertFalse(assessment.canResumeInPlace)
    }

    @Test
    fun nonApprovalStatusRequiresSafeRestart() {
        val assessment = AgentRunResumePolicy.assess(detail(status = AgentRunStatus.THINKING))

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("等待用户审批"))
    }

    @Test
    fun committedIdempotentToolAwaitingVerificationCanResumeInPlace() {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val call = ToolCall(
            id = "tool-call-note-recovery",
            name = definition.name,
            arguments = mapOf("title" to "恢复笔记", "content" to "只重新验证已提交结果"),
            risk = definition.risk,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = definition.name,
            content = "已创建并验证笔记：恢复笔记",
            durationMs = 10L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = call.id,
                operationId = "note-recovery",
                idempotencyKey = call.id,
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )
        val assessment = AgentRunResumePolicy.assess(
            detail = detail(
                status = AgentRunStatus.EXECUTING,
                steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING)),
                approvals = emptyList(),
                events = listOf(
                    event("tool.call.validated", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 1L),
                    event("tool.result", result, 2L),
                ),
            ),
            definitionLookup = { name -> definition.takeIf { it.name == name } },
        )

        assertEquals(AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION, assessment.kind)
        assertTrue(assessment.canResumeInPlace)
        assertNotNull(assessment.committedTool)
        assertEquals(call, assessment.committedTool?.toolCall)
        assertEquals(result, assessment.committedTool?.persistedResult)
    }

    private fun detail(
        status: AgentRunStatus,
        steps: List<AgentStepRecord> = emptyList(),
        approvals: List<ApprovalRequestRecord> = listOf(
            ApprovalRequestRecord(
                id = "approval-1",
                runId = "run-1",
                conversationId = "conversation-1",
                toolCallId = "tool-call-1",
                toolName = "notes.create",
                toolDescription = "创建笔记",
                risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("title" to "待确认"),
                status = ApprovalRequestStatus.PENDING,
                decisionReason = null,
                createdAt = 1L,
                expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
                decidedAt = null,
            ),
        ),
        events: List<RunEventRecord> = emptyList(),
    ) = AgentRunDetailRecord(
        snapshot = AgentRunSnapshot(
            run = AgentRunRecord(
                id = "run-1",
                conversationId = "conversation-1",
                userMessageId = "message-1",
                goal = "创建笔记",
                status = status,
                result = null,
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 1L,
                completedAt = null,
            ),
            steps = steps,
            events = events,
        ),
        approvals = approvals,
    )

    private fun step(type: String, status: AgentStepStatus, sequence: Int = 1) = AgentStepRecord(
        id = "step-$sequence",
        runId = "run-1",
        sequence = sequence,
        type = type,
        status = status,
        title = "执行工具",
        detail = "notes.create",
        createdAt = 1L,
        completedAt = null,
    )

    private fun event(type: String, metadata: RunEventMetadata, createdAt: Long) = RunEventRecord(
        id = "event-$createdAt",
        runId = "run-1",
        type = type,
        message = type,
        createdAt = createdAt,
        metadata = metadata,
    )
}
