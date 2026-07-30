package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentApprovalUiStateTest {
    @Test
    fun currentProcessApprovalDisplaysEphemeralArgumentsWithoutChangingPersistedRecord() {
        val persistedArguments = mapOf(
            "snapshot_id" to "snapshot-current",
            "ref" to "r1",
            "text_sha256" to "a9479104e48af2c58b1c68bbadbb38d4143c934508229270f7e84b282f59ff89",
            "text_length" to "16",
        )
        val toolCall = ToolCall(
            id = "tool-call-type-text",
            name = "device.type_text",
            arguments = mapOf(
                "snapshot_id" to "snapshot-current",
                "ref" to "r1",
                "text" to "Direct safe text",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val request = ApprovalRequestRecord(
            id = "approval-type-text",
            runId = "run-type-text",
            conversationId = "conversation-type-text",
            toolCallId = "tool-call-type-text",
            toolName = "device.type_text",
            toolDescription = "向普通文本框输入非敏感文本",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = persistedArguments,
            status = ApprovalRequestStatus.PENDING,
            decisionReason = null,
            createdAt = 1L,
            expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
            decidedAt = null,
        )

        val currentProcess = AgentApprovalUiState.fromCurrentProcess(
            request = request,
            toolCall = toolCall,
        )
        val restoredProcess = AgentApprovalUiState.from(request)

        assertEquals(toolCall.arguments, currentProcess.arguments)
        assertEquals(persistedArguments, restoredProcess.arguments)
        assertEquals(persistedArguments, request.arguments)
        assertThrows(IllegalArgumentException::class.java) {
            AgentApprovalUiState.fromCurrentProcess(
                request = request,
                toolCall = toolCall.copy(id = "tool-call-other"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentApprovalUiState.fromCurrentProcess(
                request = request,
                toolCall = toolCall.copy(name = "device.tap_ref"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentApprovalUiState.fromCurrentProcess(
                request = request,
                toolCall = toolCall.copy(risk = ToolRisk.SAFE),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentApprovalUiState.fromCurrentProcess(
                request = request,
                toolCall = toolCall.copy(arguments = toolCall.arguments + ("ref" to "r2")),
            )
        }
    }
}
