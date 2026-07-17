package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunEventPresentationTest {
    @Test
    fun toolResultJsonIsPresentedAsStructuredFields() {
        val presentation = presentAgentRunEvent(
            type = "tool.result",
            message = "工具执行成功：fake.echo",
            metadata = RunEventMetadata.ToolResult(
                toolName = "fake.echo",
                success = true,
                content = "done",
                durationMs = 42,
                verified = null,
            ),
        )

        assertEquals("工具执行成功", presentation.summary)
        assertEquals("fake.echo", presentation.fields.single { it.label == "工具" }.value)
        assertEquals("done", presentation.fields.single { it.label == "结果" }.value)
        assertEquals("42ms", presentation.fields.single { it.label == "耗时" }.value)
        assertEquals("是", presentation.fields.single { it.label == "成功" }.value)
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
