package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceActionApprovalOverlayDecision
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayDecisionKind
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayRequest
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayRequester
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDeviceActionApprovalGateTest {
    @Test
    fun typeTextUsesOverlayWithoutPersistingOrDisplayingInputText() = runTest {
        val persistence = FakeApprovalPersistence()
        var overlayRequest: DeviceActionApprovalOverlayRequest? = null
        val gate = gate(
            persistence = persistence,
            requester = DeviceActionApprovalOverlayRequester { request ->
                overlayRequest = request
                DeviceActionApprovalOverlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.APPROVED,
                    "用户已在设备动作审批浮层批准",
                )
            },
        )

        val decision = gate.requestApproval(RUN_ID, typeTextCall(), typeTextDefinition())

        assertTrue(decision.approved)
        assertEquals(
            mapOf(
                "snapshot_id" to "snapshot-1",
                "ref" to "r1",
                "text_sha256" to "436fe0a3fa0af22183e6584a91e42c2921bf3e096a4dca139f866a8b8296d752",
                "text_length" to "18",
            ),
            persistence.createdToolCall?.arguments,
        )
        assertFalse(persistence.createdToolCall.toString().contains(TYPE_TEXT))
        assertEquals("输入 18 个字符，内容不展示", overlayRequest?.actionSummary)
        assertFalse(overlayRequest.toString().contains(TYPE_TEXT))
    }

    @Test
    fun tapRefUsesOverlayAndPersistsExactApprovedDecision() = runTest {
        val persistence = FakeApprovalPersistence()
        var overlayRequest: DeviceActionApprovalOverlayRequest? = null
        val gate = gate(
            persistence = persistence,
            requester = DeviceActionApprovalOverlayRequester { request ->
                overlayRequest = request
                DeviceActionApprovalOverlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.APPROVED,
                    "用户已在设备动作审批浮层批准",
                )
            },
        )

        val decision = gate.requestApproval(RUN_ID, tapCall(), tapDefinition())

        assertTrue(decision.approved)
        assertEquals(ApprovalRequestStatus.APPROVED, persistence.decisions.single().status)
        assertEquals("approval-1", overlayRequest?.approvalRequestId)
        assertEquals(RUN_ID, overlayRequest?.runId)
        assertEquals(TOOL_CALL_ID, overlayRequest?.toolCallId)
        assertEquals("点击当前页面的安全按钮", overlayRequest?.userIntent)
    }

    @Test
    fun nonDeviceActionKeepsExistingInteractiveApprovalPath() = runTest {
        val persistence = FakeApprovalPersistence()
        var overlayCalled = false
        var fallbackCalled = false
        val gate = gate(
            persistence = persistence,
            fallback = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    fallbackCalled = true
                    return ApprovalDecision(approved = true, reason = "会话卡已批准")
                }
            },
            requester = DeviceActionApprovalOverlayRequester {
                overlayCalled = true
                DeviceActionApprovalOverlayDecision(DeviceActionApprovalOverlayDecisionKind.APPROVED, "不应调用")
            },
        )

        val decision = gate.requestApproval(
            RUN_ID,
            ToolCall(id = "notes-call", name = "notes.create", arguments = mapOf("text" to "记录"), risk = ToolRisk.REQUIRES_APPROVAL),
            ToolDefinition(name = "notes.create", description = "创建笔记", risk = ToolRisk.REQUIRES_APPROVAL),
        )

        assertTrue(decision.approved)
        assertTrue(fallbackCalled)
        assertFalse(overlayCalled)
        assertEquals(0, persistence.createCount)
    }

    @Test
    fun unavailableOverlayPersistsCancelledAndFailsClosed() = runTest {
        val persistence = FakeApprovalPersistence()
        val gate = gate(
            persistence = persistence,
            requester = DeviceActionApprovalOverlayRequester {
                DeviceActionApprovalOverlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.SERVICE_DISCONNECTED,
                    "无障碍服务已断开",
                )
            },
        )

        val decision = gate.requestApproval(RUN_ID, tapCall(), tapDefinition())

        assertFalse(decision.approved)
        assertEquals(ApprovalRequestStatus.CANCELLED, persistence.decisions.single().status)
        assertEquals("无障碍服务已断开", decision.reason)
    }

    @Test
    fun cancellationPersistsCancelledBeforeItPropagates() = runTest {
        val persistence = FakeApprovalPersistence()
        val gate = gate(
            persistence = persistence,
            requester = DeviceActionApprovalOverlayRequester { throw CancellationException("test-cancel") },
        )

        try {
            gate.requestApproval(RUN_ID, tapCall(), tapDefinition())
            error("审批取消必须继续传播")
        } catch (_: CancellationException) {
            assertEquals(ApprovalRequestStatus.CANCELLED, persistence.decisions.single().status)
        }
    }

    @Test
    fun persistedDecisionIdentityDriftCannotGrantExecution() = runTest {
        val persistence = FakeApprovalPersistence().apply { driftDecidedToolCallId = true }
        val gate = gate(
            persistence = persistence,
            requester = DeviceActionApprovalOverlayRequester {
                DeviceActionApprovalOverlayDecision(DeviceActionApprovalOverlayDecisionKind.APPROVED, "用户已批准")
            },
        )

        val decision = gate.requestApproval(RUN_ID, tapCall(), tapDefinition())

        assertFalse(decision.approved)
        assertTrue(decision.reason.contains("审批身份"))
    }

    private fun gate(
        persistence: FakeApprovalPersistence,
        fallback: ApprovalGate = AutoApprovalGate(),
        requester: DeviceActionApprovalOverlayRequester,
    ) = WorkflowDeviceActionApprovalGate(
        conversationId = "conversation-1",
        userIntent = "点击当前页面的安全按钮",
        fallback = fallback,
        persistence = persistence,
        overlayRequester = requester,
    )

    private fun tapCall() = ToolCall(
        id = TOOL_CALL_ID,
        name = "device.tap_ref",
        arguments = mapOf("snapshot_id" to "snapshot-1", "ref" to "r1"),
        risk = ToolRisk.REQUIRES_APPROVAL,
    )

    private fun tapDefinition() = ToolDefinition(
        name = "device.tap_ref",
        description = "点击当前快照中的节点",
        risk = ToolRisk.REQUIRES_APPROVAL,
    )

    private fun typeTextCall() = ToolCall(
        id = TOOL_CALL_ID,
        name = "device.type_text",
        arguments = mapOf(
            "snapshot_id" to "snapshot-1",
            "ref" to "r1",
            "text" to TYPE_TEXT,
        ),
        risk = ToolRisk.REQUIRES_APPROVAL,
    )

    private fun typeTextDefinition() = ToolDefinition(
        name = "device.type_text",
        description = "向当前快照中的可编辑节点输入普通文本",
        risk = ToolRisk.REQUIRES_APPROVAL,
    )

    private class FakeApprovalPersistence : WorkflowDeviceActionApprovalPersistence {
        var createCount = 0
        var driftDecidedToolCallId = false
        var createdToolCall: ToolCall? = null
        val decisions = mutableListOf<Decision>()
        private lateinit var request: ApprovalRequestRecord

        override suspend fun createApprovalRequest(
            conversationId: String,
            runId: String,
            toolCall: ToolCall,
            definition: ToolDefinition,
        ): ApprovalRequestRecord {
            createCount += 1
            createdToolCall = toolCall
            request = ApprovalRequestRecord(
                id = "approval-1",
                runId = runId,
                conversationId = conversationId,
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                toolDescription = definition.description,
                risk = definition.risk,
                arguments = toolCall.arguments,
                status = ApprovalRequestStatus.PENDING,
                decisionReason = null,
                createdAt = 1_000L,
                expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
                decidedAt = null,
            )
            return request
        }

        override suspend fun decideApprovalRequest(
            requestId: String,
            status: ApprovalRequestStatus,
            reason: String,
        ): ApprovalRequestRecord? {
            decisions += Decision(status, reason)
            return request.copy(
                toolCallId = if (driftDecidedToolCallId) "other-call" else request.toolCallId,
                status = status,
                decisionReason = reason,
                decidedAt = 2_000L,
            )
        }

        data class Decision(val status: ApprovalRequestStatus, val reason: String)
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val TOOL_CALL_ID = "tool-call-1"
        const val TYPE_TEXT = "Workflow safe text"
    }
}
