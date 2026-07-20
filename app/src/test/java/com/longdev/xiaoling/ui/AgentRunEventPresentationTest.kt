package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.ToolExecutionReceipt
import com.longdev.xiaoling.agent.ToolExecutionReceiptStatus
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.knowledge.KnowledgeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunEventPresentationTest {
    @Test
    fun executionBudgetShowsConsumedTotalAndRemainingTime() {
        val presentation = presentAgentRunEvent(
            type = "run.execution_budget.updated",
            message = "模型规划执行预算：3500/120000ms",
            metadata = RunEventMetadata.ExecutionBudget(
                totalTimeoutMs = 120_000,
                consumedMs = 3_500,
            ),
        )

        assertEquals("执行预算更新", presentation.summary)
        assertEquals("3500ms", presentation.fields.single { it.label == "已消耗" }.value)
        assertEquals("120000ms", presentation.fields.single { it.label == "总预算" }.value)
        assertEquals("116500ms", presentation.fields.single { it.label == "剩余" }.value)
        assertNull(presentation.rawFallback)
    }

    @Test
    fun recoveryFailureShowsStableReasonAndSuggestedAction() {
        val presentation = presentAgentRunEvent(
            type = "run.recovery_failed",
            message = "恢复验证失败",
            metadata = RunEventMetadata.RecoveryFailure(
                toolName = "memory.remember",
                code = "MEMORY_CHANGED",
                reason = "原长期记忆业务字段已修改",
                suggestedAction = "请保留当前编辑结果，并创建新 Run 重新确认。",
            ),
        )

        assertEquals("恢复验证失败", presentation.summary)
        assertEquals("memory.remember", presentation.fields.single { it.label == "工具" }.value)
        assertEquals("MEMORY_CHANGED", presentation.fields.single { it.label == "错误码" }.value)
        assertEquals("原长期记忆业务字段已修改", presentation.fields.single { it.label == "原因" }.value)
        assertEquals("请保留当前编辑结果，并创建新 Run 重新确认。", presentation.fields.single { it.label == "建议" }.value)
        assertNull(presentation.rawFallback)
    }

    @Test
    fun llmRequestTelemetryIsPresentedWithoutPromptContent() {
        val presentation = presentAgentRunEvent(
            type = "llm.request.completed",
            message = "模型请求完成：plan",
            metadata = RunEventMetadata.LlmRequest(
                phase = com.longdev.xiaoling.agent.AgentLlmPhase.PLAN,
                model = "gpt-test",
                latencyMs = 1_250L,
                firstByteLatencyMs = 320L,
                promptBytes = 4_096,
                inputTokens = 120L,
                outputTokens = 30L,
                totalTokens = 150L,
            ),
        )

        assertEquals("模型请求完成", presentation.summary)
        assertEquals("PLAN", presentation.fields.single { it.label == "阶段" }.value)
        assertEquals("4096 B", presentation.fields.single { it.label == "Prompt" }.value)
        assertEquals("150", presentation.fields.single { it.label == "总 Token" }.value)
        assertNull(presentation.rawFallback)
    }

    @Test
    fun toolResultJsonIsPresentedAsStructuredFields() {
        val reference = KnowledgeReference(
            retrievalId = "knowledge-retrieval-event",
            documentId = "document-event",
            documentName = "事件审计.md",
            documentRevision = 3,
            chunkId = "chunk-event-r3-1",
            chunkSequence = 1,
            startOffset = 80,
            endOffset = 160,
        )
        val presentation = presentAgentRunEvent(
            type = "tool.result",
            message = "工具执行成功：fake.echo",
            metadata = RunEventMetadata.ToolResult(
                toolName = "fake.echo",
                success = true,
                content = "done",
                durationMs = 42,
                verified = null,
                knowledgeReferences = listOf(reference),
            ),
        )

        assertEquals("工具执行成功", presentation.summary)
        assertEquals("fake.echo", presentation.fields.single { it.label == "工具" }.value)
        assertEquals("done", presentation.fields.single { it.label == "结果" }.value)
        assertEquals("42ms", presentation.fields.single { it.label == "耗时" }.value)
        assertEquals("是", presentation.fields.single { it.label == "成功" }.value)
        val knowledge = presentation.fields.single { it.label == "知识引用" }.value
        assertTrue(knowledge.contains(reference.retrievalId))
        assertTrue(knowledge.contains("事件审计.md (document-event) · revision=3"))
        assertTrue(knowledge.contains("chunk=1"))
        assertTrue(knowledge.contains("offset=80-160"))
        assertNull(presentation.rawFallback)
    }

    @Test
    fun toolCallArgumentsRemainReadableWhenNestedJsonIsUsed() {
        val presentation = presentAgentRunEvent(
            type = "tool.call.proposed",
            message = "模型提出工具调用：fake.echo",
            metadata = RunEventMetadata.ToolCall(
                id = "fake-call-1",
                toolName = "fake.echo",
                risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("goal" to "hello", "path" to "/tmp/a"),
            ),
        )

        assertEquals("模型提出工具调用", presentation.summary)
        assertEquals("fake-call-1", presentation.fields.single { it.label == "调用" }.value)
        assertEquals("fake.echo", presentation.fields.single { it.label == "工具" }.value)
        assertEquals("goal=hello · path=/tmp/a", presentation.fields.single { it.label == "参数" }.value)
    }

    @Test
    fun toolResultReceiptShowsEvidenceWithoutExposingIdempotencyKey() {
        val presentation = presentAgentRunEvent(
            type = "tool.result",
            message = "工具执行成功：notes.create",
            metadata = RunEventMetadata.ToolResult(
                toolName = "notes.create",
                success = true,
                content = "已创建笔记",
                durationMs = 42,
                verified = true,
                toolCallId = "tool-call-1",
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = "tool-call-1",
                    operationId = "note-1",
                    idempotencyKey = "private-idempotency-key",
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            ),
        )

        assertEquals("tool-call-1", presentation.fields.single { it.label == "调用" }.value)
        assertEquals("note-1", presentation.fields.single { it.label == "操作" }.value)
        assertEquals("COMMITTED", presentation.fields.single { it.label == "回执状态" }.value)
        assertEquals("IDEMPOTENT_BY_KEY", presentation.fields.single { it.label == "重放声明" }.value)
        assertEquals("已记录", presentation.fields.single { it.label == "幂等证明" }.value)
        assertTrue(presentation.fields.none { it.value == "private-idempotency-key" })
    }

    @Test
    fun eventWithoutDecodableMetadataFallsBackToReadableMessage() {
        val raw = "工具执行结果：fake.echo"
        val presentation = presentAgentRunEvent(
            type = "tool.result",
            message = raw,
            metadata = null,
        )

        assertEquals("工具执行结果", presentation.summary)
        assertEquals(raw, presentation.rawFallback)
        assertEquals(emptyList<AgentRunEventField>(), presentation.fields)
    }

    @Test
    fun plainTextEventFallsBackToRawMessage() {
        val presentation = presentAgentRunEvent(
            type = "run.status",
            message = "THINKING",
            metadata = null,
        )

        assertEquals("Run 状态变化", presentation.summary)
        assertEquals("THINKING", presentation.rawFallback)
        assertEquals(emptyList<AgentRunEventField>(), presentation.fields)
    }

    @Test
    fun approvalRequestNoActiveExpiryIsPresentedAsPolicyLabel() {
        val presentation = presentAgentRunEvent(
            type = "approval.requested",
            message = "等待审批：memory.remember",
            metadata = RunEventMetadata.ApprovalRequest(
                id = "approval-1",
                toolName = "memory.remember",
                risk = ToolRisk.REQUIRES_APPROVAL,
                status = ApprovalRequestStatus.PENDING,
                expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
                arguments = mapOf("note" to "compact ui"),
                reason = null,
            ),
        )

        assertEquals("审批请求", presentation.summary)
        assertEquals("无主动过期", presentation.fields.single { it.label == "过期策略" }.value)
        assertNull(presentation.rawFallback)
    }
}
