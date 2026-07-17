package com.longdev.xiaoling.storage

import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.toApprovalExpiryPolicyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRequestPolicyTest {
    @Test
    fun pendingApprovalsDoNotExpireByTimestampDuringInteractiveWait() {
        val expiredTimestamp = 1L
        val requests = listOf(
            approval("pending-expired-field", ApprovalRequestStatus.PENDING, expiredTimestamp),
            approval("pending-no-expiry", ApprovalRequestStatus.PENDING, APPROVAL_REQUEST_NO_EXPIRY_AT),
            approval("approved", ApprovalRequestStatus.APPROVED, APPROVAL_REQUEST_NO_EXPIRY_AT),
            approval("cancelled", ApprovalRequestStatus.CANCELLED, APPROVAL_REQUEST_NO_EXPIRY_AT),
        )

        val pending = activePendingApprovalRequests(requests)

        assertEquals(listOf("pending-expired-field", "pending-no-expiry"), pending.map { it.id })
        assertTrue(pending.all { it.status == ApprovalRequestStatus.PENDING })
    }

    @Test
    fun noActiveExpiryTimestampHasBusinessLabel() {
        assertEquals("无主动过期", APPROVAL_REQUEST_NO_EXPIRY_AT.toApprovalExpiryPolicyLabel())
    }

    private fun approval(
        id: String,
        status: ApprovalRequestStatus,
        expiresAt: Long,
    ): ApprovalRequestRecord {
        return ApprovalRequestRecord(
            id = id,
            runId = "run-1",
            conversationId = "conversation-1",
            toolCallId = "tool-call-1",
            toolName = "memory.remember",
            toolDescription = "写入长期记忆",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("note" to "user likes compact UI"),
            status = status,
            decisionReason = null,
            createdAt = 100L,
            expiresAt = expiresAt,
            decidedAt = null,
        )
    }
}
