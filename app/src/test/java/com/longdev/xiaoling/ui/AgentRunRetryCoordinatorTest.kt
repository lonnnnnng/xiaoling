package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.MessageAttachmentSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunRetryCoordinatorTest {
    @Test
    fun retryableRunWithoutSideEffectMovesToPreparation() = runTest {
        val detail = detail(status = AgentRunStatus.FAILED)
        val events = mutableListOf<AgentRunRetryEvent>()
        val coordinator = coordinator(this)

        coordinator.request(
            runId = detail.snapshot.run.id,
            runHistory = listOf(detail),
            busy = false,
            onEvent = events::add,
        )

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

        coordinator(this).request(
            runId = detail.snapshot.run.id,
            runHistory = listOf(detail),
            busy = false,
            onEvent = events::add,
        )

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

        coordinator(this).confirm(
            pending = pending,
            runHistory = listOf(changed),
            onEvent = events::add,
        )

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
        val coordinator = AgentRunRetryCoordinator(
            scope = this,
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
        val coordinator = AgentRunRetryCoordinator(
            scope = this,
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
        val coordinator = coordinator(this)

        val cases = listOf(
            Triple(true, listOf(failed), "当前已有任务正在执行，请等待结束后再重试"),
            Triple(false, emptyList(), "找不到要重试的 Agent Run，请刷新任务中心"),
            Triple(false, listOf(completed), "当前状态不支持重试"),
        )

        cases.forEach { (busy, history, expectedMessage) ->
            val events = mutableListOf<AgentRunRetryEvent>()
            coordinator.request(
                runId = "run-source",
                runHistory = history,
                busy = busy,
                onEvent = events::add,
            )

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
        val coordinator = coordinator(this)

        val missingEvents = mutableListOf<AgentRunRetryEvent>()
        coordinator.confirm(pending, emptyList(), missingEvents::add)
        assertEquals(
            listOf(AgentRunRetryEvent.Failed("run-source", "来源 Agent Run 已不存在，请刷新任务中心")),
            missingEvents,
        )

        val changedEvents = mutableListOf<AgentRunRetryEvent>()
        coordinator.confirm(
            pending = pending,
            runHistory = listOf(detail(status = AgentRunStatus.COMPLETED)),
            onEvent = changedEvents::add,
        )
        assertEquals(
            listOf(AgentRunRetryEvent.Failed("run-source", "当前状态已变化，请刷新任务中心")),
            changedEvents,
        )

        val cancelledEvents = mutableListOf<AgentRunRetryEvent>()
        coordinator.cancel(pending.runId, cancelledEvents::add)
        assertEquals(
            listOf(AgentRunRetryEvent.Cancelled("run-source")),
            cancelledEvents,
        )
    }

    private fun coordinator(scope: CoroutineScope) = AgentRunRetryCoordinator(
        scope = scope,
        loadSourceAttachments = { MessageAttachmentSelection() },
    )

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
