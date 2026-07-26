package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentRunSummary
import com.longdev.xiaoling.agent.AgentStepRecord
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentToolCallRecord
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.model.MessageAttachmentSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredAgentApprovalCoordinatorTest {
    @Test
    fun validRecoveredApprovalResumesTheOriginalRunOnce() = runTest {
        val detail = validDetail()
        val summary = summary()
        var resumeCount = 0
        val coordinator = coordinator(loadDetail = { detail })

        val outcome = coordinator.approve(approvalUiState()) { resolvedDetail, approval, attachments ->
            resumeCount += 1
            assertEquals(detail, resolvedDetail)
            assertEquals("approval-1", approval.id)
            assertEquals(MessageAttachmentSelection(), attachments)
            summary
        }

        assertEquals(1, resumeCount)
        assertEquals(
            RecoveredAgentApprovalOutcome.Completed(detail, summary),
            outcome,
        )
    }

    @Test
    fun changedRecoveryEvidenceFailsClosedBeforeLoadingAttachmentsOrResuming() = runTest {
        val changed = validDetail().copy(
            approvals = validDetail().approvals.map {
                it.copy(arguments = mapOf("title" to "参数已变化"))
            },
        )
        var attachmentLoadCount = 0
        var resumeCount = 0
        val coordinator = coordinator(
            loadDetail = { changed },
            loadAttachments = {
                attachmentLoadCount += 1
                MessageAttachmentSelection()
            },
        )

        val outcome = coordinator.approve(approvalUiState()) { _, _, _ ->
            resumeCount += 1
            summary()
        }

        assertTrue(outcome is RecoveredAgentApprovalOutcome.Stale)
        assertEquals(0, attachmentLoadCount)
        assertEquals(0, resumeCount)
    }

    @Test
    fun attachmentFailureKeepsThePersistedApprovalRetryable() = runTest {
        val detail = validDetail()
        val coordinator = coordinator(
            loadDetail = { detail },
            loadAttachments = { error("Room 附件读取失败") },
        )

        val outcome = coordinator.approve(approvalUiState()) { _, _, _ -> summary() }

        assertEquals(
            RecoveredAgentApprovalOutcome.StillPending(
                detail = detail,
                message = "Room 附件读取失败",
            ),
            outcome,
        )
    }

    @Test
    fun staleRejectionNeverFailsTheRunWhenApprovalDecisionWasNotPersisted() = runTest {
        val detail = validDetail()
        var rejectCallCount = 0
        val coordinator = coordinator(
            loadDetail = { detail },
            rejectApproval = {
                rejectCallCount += 1
                null
            },
        )

        val outcome = coordinator.reject(approvalUiState())

        assertTrue(outcome is RecoveredAgentApprovalOutcome.Stale)
        assertEquals(1, rejectCallCount)
    }

    @Test
    fun rejectionPersistsDenialBeforeFailingTheOriginalRun() = runTest {
        var current = validDetail()
        val calls = mutableListOf<String>()
        val coordinator = coordinator(
            loadDetail = { current },
            rejectApproval = { rejection ->
                calls += "approval:DENIED"
                val denied = current.approvals.single()
                    .takeIf { it.id == rejection.requestId }
                    ?.copy(status = ApprovalRequestStatus.DENIED)
                    ?: return@coordinator null
                current = current.copy(approvals = listOf(denied))
                calls += "run:FAILED"
                current = current.copy(
                    snapshot = current.snapshot.copy(
                        run = current.snapshot.run.copy(
                            id = rejection.runId,
                            status = AgentRunStatus.FAILED,
                            errorMessage = rejection.reason,
                        ),
                    ),
                )
                current
            },
        )

        val outcome = coordinator.reject(approvalUiState())

        assertEquals(
            listOf("approval:DENIED", "run:FAILED"),
            calls,
        )
        assertTrue(outcome is RecoveredAgentApprovalOutcome.Rejected)
        assertEquals(AgentRunStatus.FAILED, (outcome as RecoveredAgentApprovalOutcome.Rejected).detail.snapshot.run.status)
    }

    @Test
    fun approvalInProgressKeepsAConcurrentDecisionRetryable() = runTest {
        val detail = validDetail()
        val resumeStarted = CompletableDeferred<Unit>()
        val releaseResume = CompletableDeferred<Unit>()
        var rejectApprovalCount = 0
        val coordinator = coordinator(
            loadDetail = { detail },
            rejectApproval = {
                rejectApprovalCount += 1
                detail
            },
        )
        val approving = async {
            coordinator.approve(approvalUiState()) { _, _, _ ->
                resumeStarted.complete(Unit)
                releaseResume.await()
                summary()
            }
        }
        resumeStarted.await()

        val concurrent = coordinator.reject(approvalUiState())
        releaseResume.complete(Unit)

        assertEquals(
            RecoveredAgentApprovalOutcome.Busy("另一项恢复审批正在处理中"),
            concurrent,
        )
        assertEquals(0, rejectApprovalCount)
        assertTrue(approving.await() is RecoveredAgentApprovalOutcome.Completed)
    }

    private fun coordinator(
        loadDetail: suspend (String) -> AgentRunDetailRecord?,
        loadAttachments: suspend (AgentRunDetailRecord) -> MessageAttachmentSelection = { MessageAttachmentSelection() },
        rejectApproval: suspend (RecoveredApprovalRejection) -> AgentRunDetailRecord? = { error("不应拒绝审批") },
    ) = RecoveredAgentApprovalCoordinator(
        loadRunDetail = loadDetail,
        loadSourceAttachments = loadAttachments,
        rejectApproval = rejectApproval,
    )

    private fun approvalUiState() = AgentApprovalUiState(
        requestId = "approval-1",
        runId = "run-1",
        conversationId = "conversation-1",
        toolCallId = "tool-call-1",
        toolName = "notes.create",
        toolDescription = "创建笔记",
        riskLabel = "需确认",
        arguments = mapOf("title" to "待确认"),
        expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
        restoredFromProcess = true,
    )

    private fun validDetail(): AgentRunDetailRecord {
        val call = ToolCall(
            id = "tool-call-1",
            name = "notes.create",
            arguments = mapOf("title" to "待确认"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val approval = ApprovalRequestRecord(
            id = "approval-1",
            runId = "run-1",
            conversationId = "conversation-1",
            toolCallId = call.id,
            toolName = call.name,
            toolDescription = "创建笔记",
            risk = call.risk,
            arguments = call.arguments,
            status = ApprovalRequestStatus.PENDING,
            decisionReason = null,
            createdAt = 1L,
            expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
            decidedAt = null,
        )
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = "run-1",
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    goal = "创建笔记",
                    status = AgentRunStatus.WAITING_APPROVAL,
                    result = null,
                    errorMessage = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                    completedAt = null,
                ),
                steps = listOf(
                    AgentStepRecord(
                        id = "step-1",
                        runId = "run-1",
                        sequence = 1,
                        type = "approval",
                        status = AgentStepStatus.RUNNING,
                        title = "等待审批",
                        detail = call.name,
                        createdAt = 1L,
                        completedAt = null,
                    ),
                ),
                events = listOf(
                    event("tool.call.proposed", call, 1L),
                    event("tool.call.validated", call, 2L),
                ),
            ),
            approvals = listOf(approval),
            toolLedger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = call.id,
                        runId = "run-1",
                        toolName = call.name,
                        risk = call.risk,
                        arguments = call.arguments,
                        proposedEventId = "event-1",
                        validatedEventId = "event-2",
                        createdAt = 1L,
                        validatedAt = 2L,
                    ),
                ),
            ),
        )
    }

    private fun event(type: String, call: ToolCall, createdAt: Long) = RunEventRecord(
        id = "event-$createdAt",
        runId = "run-1",
        type = type,
        message = type,
        createdAt = createdAt,
        metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
    )

    private fun summary() = AgentRunSummary(
        runId = "run-1",
        status = AgentRunStatus.COMPLETED,
        responseText = "笔记已创建",
        verifiedContext = VerifiedAgentContext(
            runId = "run-1",
            toolName = "notes.create",
            arguments = mapOf("title" to "待确认"),
            success = true,
            verificationStatus = AgentVerificationStatus.VERIFIED,
            rawResult = "笔记已创建",
        ),
    )
}
