package com.longdev.xiaoling.ui

import com.longdev.xiaoling.ui.agenttask.AgentRetryConfirmationKind
import com.longdev.xiaoling.ui.agenttask.AgentRetryConfirmationUiState
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentNotCommittedReplayQualification
import com.longdev.xiaoling.agent.AgentNotCommittedReplayQualificationAssessment
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunRestartDisposition
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolDefinitionRecoveryContract
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.MessageAttachmentSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunRetryCoordinatorTest {
    @Test
    fun controlledReplayLoadsLatestRoomDetailAndRequiresExplicitConfirmation() = runTest {
        val detail = notCommittedReplayDetail()
        val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
        var loadCount = 0
        var qualificationCount = 0
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = AgentRunRetryCoordinator(
            scope = this,
            loadRunDetail = { runId ->
                assertEquals(detail.snapshot.run.id, runId)
                loadCount += 1
                detail
            },
            requalifyNotCommittedReplay = {
                qualificationCount += 1
                AgentNotCommittedReplayQualificationAssessment.Eligible(controlledReplayQualification())
            },
            loadSourceAttachments = { MessageAttachmentSelection() },
        )

        coordinator.request(
            runId = detail.snapshot.run.id,
            busy = false,
            onEvent = events::add,
        ).join()

        assertEquals(1, loadCount)
        assertEquals(1, qualificationCount)
        assertEquals(
            listOf(
                AgentRunRetryEvent.ConfirmationRequired(
                    AgentRetryConfirmationUiState(
                        runId = detail.snapshot.run.id,
                        goal = detail.snapshot.run.goal,
                        evidenceCode = evidence.code,
                        evidenceFingerprint = evidence.fingerprint,
                        kind = AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY,
                        expectedRestartDispositionCode =
                            AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
                    ),
                ),
            ),
            events,
        )
    }

    @Test
    fun controlledReplayConfirmationReloadsRoomAndPassesFreshQualificationToPreparation() = runTest {
        val detail = notCommittedReplayDetail()
        val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
        val qualification = controlledReplayQualification()
        var loadCount = 0
        var qualificationCount = 0
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = AgentRunRetryCoordinator(
            scope = this,
            loadRunDetail = {
                loadCount += 1
                detail
            },
            requalifyNotCommittedReplay = {
                qualificationCount += 1
                AgentNotCommittedReplayQualificationAssessment.Eligible(qualification)
            },
            loadSourceAttachments = { MessageAttachmentSelection() },
        )
        val pending = AgentRetryConfirmationUiState(
            runId = detail.snapshot.run.id,
            goal = detail.snapshot.run.goal,
            evidenceCode = evidence.code,
            evidenceFingerprint = evidence.fingerprint,
            kind = AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY,
            expectedRestartDispositionCode =
                AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
        )

        coordinator.confirm(pending, events::add).join()

        assertEquals(1, loadCount)
        assertEquals(1, qualificationCount)
        assertEquals(
            listOf(
                AgentRunRetryEvent.PreparationRequired(
                    detail = detail,
                    controlledReplayQualification = qualification,
                ),
            ),
            events,
        )
    }

    @Test
    fun controlledReplayConfirmationRejectsRestartDispositionDrift() = runTest {
        val original = notCommittedReplayDetail()
        val evidence = AgentTaskRetryPolicy.assessEvidence(original)
        val changed = notCommittedReplayDetail(
            dispositionCode = AgentRunRestartDispositionCode.COMMIT_UNKNOWN,
        )
        var qualificationCount = 0
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = AgentRunRetryCoordinator(
            scope = this,
            loadRunDetail = { changed },
            requalifyNotCommittedReplay = {
                qualificationCount += 1
                AgentNotCommittedReplayQualificationAssessment.Eligible(controlledReplayQualification())
            },
            loadSourceAttachments = { MessageAttachmentSelection() },
        )
        val pending = AgentRetryConfirmationUiState(
            runId = original.snapshot.run.id,
            goal = original.snapshot.run.goal,
            evidenceCode = evidence.code,
            evidenceFingerprint = evidence.fingerprint,
            kind = AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY,
            expectedRestartDispositionCode =
                AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
        )

        coordinator.confirm(pending, events::add).join()

        assertEquals(0, qualificationCount)
        assertEquals(
            listOf(AgentRunRetryEvent.Failed("run-source", "受控重放资格已变化，请重新发起重试")),
            events,
        )
    }

    @Test
    fun retryableRunWithoutSideEffectMovesToPreparation() = runTest {
        val detail = detail(status = AgentRunStatus.FAILED)
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = coordinator(this, detail)

        coordinator.request(
            runId = detail.snapshot.run.id,
            busy = false,
            onEvent = events::add,
        ).join()

        assertEquals(
            listOf(AgentRunRetryEvent.PreparationRequired(detail)),
            events,
        )
    }

    @Test
    fun successfulWriteRequiresEvidenceConfirmationBeforePreparation() = runTest {
        val detail = writeSideEffectDetail()
        val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
        val events = mutableListOf<AgentRunRetryEvent>()

        coordinator(this, detail).request(
            runId = detail.snapshot.run.id,
            busy = false,
            onEvent = events::add,
        ).join()

        assertEquals(
            listOf(
                AgentRunRetryEvent.ConfirmationRequired(
                    AgentRetryConfirmationUiState(
                        runId = detail.snapshot.run.id,
                        goal = detail.snapshot.run.goal,
                        evidenceCode = evidence.code,
                        evidenceFingerprint = evidence.fingerprint,
                    ),
                ),
            ),
            events,
        )
    }

    @Test
    fun evidenceDriftRefreshesConfirmationInsteadOfPreparingRetry() = runTest {
        val base = writeSideEffectDetail()
        val originalEvidence = AgentTaskRetryPolicy.assessEvidence(base)
        val pending = AgentRetryConfirmationUiState(
            runId = base.snapshot.run.id,
            goal = base.snapshot.run.goal,
            evidenceCode = originalEvidence.code,
            evidenceFingerprint = originalEvidence.fingerprint,
        )
        val changed = base.copy(
            snapshot = base.snapshot.copy(
                events = base.snapshot.events + listOf(
                    event(
                        id = "event-tool-call-2",
                        type = "tool.call.validated",
                        metadata = RunEventMetadata.ToolCall(
                            id = "tool-call-2",
                            toolName = "memory.remember",
                            risk = ToolRisk.REQUIRES_APPROVAL,
                            arguments = mapOf("content" to "用户也喜欢深色界面"),
                        ),
                    ),
                    event(
                        id = "event-tool-result-2",
                        type = "tool.result",
                        metadata = RunEventMetadata.ToolResult(
                            toolName = "memory.remember",
                            content = "已保存第二条",
                            durationMs = 20L,
                            success = true,
                            verified = true,
                            toolCallId = "tool-call-2",
                        ),
                    ),
                ),
            ),
        )
        val currentEvidence = AgentTaskRetryPolicy.assessEvidence(changed)
        val events = mutableListOf<AgentRunRetryEvent>()

        coordinator(this, changed).confirm(
            pending = pending,
            onEvent = events::add,
        ).join()

        assertEquals(originalEvidence.code, currentEvidence.code)
        assertEquals(
            listOf(
                AgentRunRetryEvent.ConfirmationRefreshed(
                    pending.copy(
                        evidenceCode = currentEvidence.code,
                        evidenceFingerprint = currentEvidence.fingerprint,
                    ),
                ),
            ),
            events,
        )
    }

    @Test
    fun preparationRestoresAttachmentAndBuildsLinkedRetryWithoutMutatingSourceRun() = runTest {
        val detail = detail(status = AgentRunStatus.FAILED)
        val sourceBefore = detail.copy()
        val attachments = MessageAttachmentSelection(
            document = DocumentAttachmentPolicy.create(
                fileName = "source.txt",
                mimeType = "text/plain",
                data = "原始附件".toByteArray(),
            ),
        )
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = coordinator(
            scope = this,
            detail = detail,
            loadSourceAttachments = { source ->
                assertEquals(detail.snapshot.run.id, source.snapshot.run.id)
                attachments
            },
        )

        coordinator.prepare(detail, events::add).join()

        assertEquals(sourceBefore, detail)
        assertEquals(null, detail.snapshot.run.retryOfRunId)
        assertEquals(
            listOf(
                AgentRunRetryEvent.RetryStarting(
                    runId = "run-source",
                    conversationId = "conversation-1",
                ),
                AgentRunRetryEvent.RetryReady(
                    AgentRunRetryLaunchRequest(
                        userMessage = "/agent 重试任务",
                        conversationId = "conversation-1",
                        retryOfRunId = "run-source",
                        attachments = attachments,
                    ),
                ),
            ),
            events,
        )
    }

    @Test
    fun attachmentLoadFailurePublishesStableFailureEvent() = runTest {
        val detail = detail(status = AgentRunStatus.FAILED)
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = coordinator(
            scope = this,
            detail = detail,
            loadSourceAttachments = { error("Room 读取失败") },
        )

        coordinator.prepare(detail, events::add).join()

        assertEquals(
            listOf(
                AgentRunRetryEvent.RetryStarting(
                    runId = "run-source",
                    conversationId = "conversation-1",
                ),
                AgentRunRetryEvent.Failed(
                    runId = "run-source",
                    message = "Room 读取失败",
                ),
            ),
            events,
        )
    }

    @Test
    fun requestRejectionsPublishTheExistingUserFacingReasons() = runTest {
        val failed = detail(status = AgentRunStatus.FAILED)
        val completed = detail(status = AgentRunStatus.COMPLETED)
        val cases = listOf(
            Triple(true, listOf(failed), "当前已有任务正在执行，请等待结束后再重试"),
            Triple(false, emptyList(), "找不到要重试的 Agent Run，请刷新任务中心"),
            Triple(false, listOf(completed), "当前状态不支持重试"),
        )

        cases.forEach { (busy, history, expectedMessage) ->
            val events = mutableListOf<AgentRunRetryEvent>()
            coordinator(this, history.firstOrNull()).request(
                runId = "run-source",
                busy = busy,
                onEvent = events::add,
            ).join()

            assertEquals(
                listOf(AgentRunRetryEvent.Failed(runId = "run-source", message = expectedMessage)),
                events,
            )
        }
    }

    @Test
    fun confirmationFailureAndCancellationPublishExplicitEvents() = runTest {
        val detail = writeSideEffectDetail()
        val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
        val pending = AgentRetryConfirmationUiState(
            runId = detail.snapshot.run.id,
            goal = detail.snapshot.run.goal,
            evidenceCode = evidence.code,
            evidenceFingerprint = evidence.fingerprint,
        )
        val missingEvents = mutableListOf<AgentRunRetryEvent>()
        coordinator(this, null).confirm(pending, missingEvents::add).join()
        assertEquals(
            listOf(AgentRunRetryEvent.Failed("run-source", "来源 Agent Run 已不存在，请刷新任务中心")),
            missingEvents,
        )

        val changedEvents = mutableListOf<AgentRunRetryEvent>()
        coordinator(this, detail(status = AgentRunStatus.COMPLETED)).confirm(
            pending = pending,
            onEvent = changedEvents::add,
        ).join()
        assertEquals(
            listOf(AgentRunRetryEvent.Failed("run-source", "当前状态已变化，请刷新任务中心")),
            changedEvents,
        )

        val cancelledEvents = mutableListOf<AgentRunRetryEvent>()
        coordinator(this, detail).cancel(pending.runId, cancelledEvents::add)
        assertEquals(
            listOf(AgentRunRetryEvent.Cancelled("run-source")),
            cancelledEvents,
        )
    }

    private fun coordinator(
        scope: CoroutineScope,
        detail: AgentRunDetailRecord? = null,
        loadSourceAttachments: suspend (AgentRunDetailRecord) -> MessageAttachmentSelection = {
            MessageAttachmentSelection()
        },
    ) = AgentRunRetryCoordinator(
        scope = scope,
        loadRunDetail = { detail },
        requalifyNotCommittedReplay = {
            AgentNotCommittedReplayQualificationAssessment.Ineligible("测试默认不签发资格")
        },
        loadSourceAttachments = loadSourceAttachments,
    )

    private fun controlledReplayQualification(): AgentNotCommittedReplayQualification {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        return AgentNotCommittedReplayQualification(
            toolCall = ToolCall(
                id = "tool-call-replay",
                name = definition.name,
                arguments = mapOf("title" to "资格", "content" to "尚未执行"),
                risk = definition.risk,
            ),
            recoveryContract = ToolDefinitionRecoveryContract.snapshot(definition),
        )
    }

    private fun notCommittedReplayDetail(
        dispositionCode: AgentRunRestartDispositionCode =
            AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
    ): AgentRunDetailRecord {
        val base = detail(
            status = AgentRunStatus.CANCELLED,
            events = listOf(
                event(
                    type = "run.recovered",
                    metadata = RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.EXECUTING,
                        toStatus = AgentRunStatus.CANCELLED,
                        reason = "应用重启后终止上次未完成 Agent 任务",
                        retryEvidenceCode = AgentTaskRetryEvidenceCode.NOT_COMMITTED,
                        restartDisposition = AgentRunRestartDisposition(
                            code = dispositionCode,
                            reason = "尚未进入工具执行边界",
                            evidenceBoundary = "原工具调用已通过受控同调用资格核验",
                            suggestedAction = "确认后创建关联新 Run",
                        ),
                    ),
                ),
                RunEventRecord(
                    id = "event-run-status-cancelled",
                    runId = "run-source",
                    type = "run.status",
                    message = AgentRunStatus.CANCELLED.name,
                    createdAt = 2L,
                    metadata = null,
                ),
            ),
        )
        return base.copy(
            snapshot = base.snapshot.copy(
                events = base.snapshot.events.map { event ->
                    val recovery = event.metadata as? RunEventMetadata.Recovery
                    if (recovery == null) {
                        event
                    } else {
                        event.copy(
                            metadata = recovery.copy(
                                retryEvidenceFingerprint = AgentTaskRetryPolicy.assessEvidence(base).fingerprint,
                            ),
                        )
                    }
                },
            ),
        )
    }

    private fun detail(
        status: AgentRunStatus,
        events: List<RunEventRecord> = emptyList(),
    ): AgentRunDetailRecord = AgentRunDetailRecord(
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
            steps = emptyList(),
            events = events,
        ),
        approvals = emptyList(),
    )

    private fun writeSideEffectDetail() = detail(
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
                    durationMs = 20L,
                    success = true,
                    verified = true,
                ),
            ),
        ),
    )

    private fun event(type: String, metadata: RunEventMetadata) =
        event(id = "event-$type", type = type, metadata = metadata)

    private fun event(
        id: String,
        type: String,
        metadata: RunEventMetadata,
    ) = RunEventRecord(
        id = id,
        runId = "run-source",
        type = type,
        message = type,
        createdAt = 1L,
        metadata = metadata,
    )
}
