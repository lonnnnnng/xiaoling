package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunRestartDisposition
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRunRecoveryNoticePolicyTest {
    @Test
    fun failedInterruptedRunCreatesStableNoticeWithoutPrivateContent() {
        val notice = projectStartupRunRecoveryNotice(
            listOf(
                run(
                    id = "run-private",
                    status = AgentRunStatus.FAILED,
                    goal = "整理私密项目",
                    errorMessage = "provider-secret-error",
                ),
            ),
        )

        assertNotNull(notice)
        assertFalse(notice!!.success)
        assertTrue(notice.title.contains("上次中断"))
        assertTrue(notice.message.contains("失败 1 个"))
        assertTrue(notice.message.contains("不会重放工具"))
        assertEquals(OperationResultAction.OPEN_INTERRUPTED_AGENT_RUN_HISTORY, notice.action)
        assertFalse(notice.message.contains("run-private"))
        assertFalse(notice.message.contains("整理私密项目"))
        assertFalse(notice.message.contains("provider-secret-error"))
    }

    @Test
    fun cancelledInterruptedRunCreatesDistinctStableNotice() {
        val notice = projectStartupRunRecoveryNotice(
            listOf(run(id = "run-cancelled", status = AgentRunStatus.CANCELLED)),
        )

        assertNotNull(notice)
        assertTrue(notice!!.message.contains("取消 1 个"))
        assertFalse(notice.message.contains("失败 1 个"))
        assertEquals(OperationResultAction.OPEN_INTERRUPTED_AGENT_RUN_HISTORY, notice.action)
    }

    @Test
    fun restartRequiredBoundaryIsVisibleWithoutExposingRunDetails() {
        val notice = projectStartupRunRecoveryNotice(
            listOf(
                run(
                    id = "run-restart-required",
                    status = AgentRunStatus.CANCELLED,
                    restartDisposition = AgentRunRestartDisposition(
                        code = AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
                        reason = "旧 Run 不可原地恢复",
                        evidenceBoundary = "仅允许建立新 Run",
                        suggestedAction = "在任务中心确认后重试",
                    ),
                ),
            ),
        )

        assertTrue(notice!!.message.contains("无法原地恢复"))
        assertTrue(notice.message.contains("确认后创建关联新 Run"))
        assertTrue(notice.message.contains("旧 Run 不会重放"))
        assertFalse(notice.message.contains("run-restart-required"))
        assertFalse(notice.message.contains("旧 Run 不可原地恢复"))
    }

    @Test
    fun settlementReadsTerminalStateAfterClosingCandidates() = runTest {
        var closed = false

        val notice = settleStartupInterruptedRuns(
            candidateRunIds = setOf("run-recovered"),
            closeInterruptedRuns = {
                closed = true
                1
            },
            loadRun = { runId ->
                if (closed) run(id = runId, status = AgentRunStatus.CANCELLED) else null
            },
        )

        assertTrue(closed)
        assertEquals("已处理上次中断", notice?.title)
        assertTrue(notice?.message.orEmpty().contains("取消 1 个"))
    }

    @Test
    fun noSettlementDoesNotReadCandidatesOrCreateNotice() = runTest {
        var loaded = false

        val notice = settleStartupInterruptedRuns(
            candidateRunIds = setOf("run-resumable"),
            closeInterruptedRuns = { 0 },
            loadRun = {
                loaded = true
                run(id = "run-resumable", status = AgentRunStatus.FAILED)
            },
        )

        assertFalse(loaded)
        assertEquals(null, notice)
    }

    @Test
    fun nonTerminalOrCompletedReadbackDoesNotCreateNotice() {
        assertEquals(
            null,
            projectStartupRunRecoveryNotice(
                listOf(
                    run(id = "run-running", status = AgentRunStatus.EXECUTING),
                    run(id = "run-completed", status = AgentRunStatus.COMPLETED),
                ),
            ),
        )
    }

    private fun run(
        id: String,
        status: AgentRunStatus,
        goal: String = "任务目标",
        errorMessage: String? = null,
        restartDisposition: AgentRunRestartDisposition? = null,
    ): AgentRunDetailRecord {
        val record = AgentRunRecord(
            id = id,
            conversationId = "conversation-$id",
            userMessageId = "message-$id",
            goal = goal,
            status = status,
            result = null,
            errorMessage = errorMessage,
            createdAt = 1L,
            updatedAt = 2L,
            completedAt = 3L,
        )
        val events = restartDisposition?.let { disposition ->
            listOf(
                RunEventRecord(
                    id = "event-recovery-$id",
                    runId = id,
                    type = "run.recovered",
                    message = "启动恢复收敛",
                    createdAt = 3L,
                    metadata = RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.EXECUTING,
                        toStatus = status,
                        reason = disposition.reason,
                        resumeKind = AgentRunResumeKind.RESTART_REQUIRED,
                        restartDisposition = disposition,
                    ),
                ),
            )
        }.orEmpty()
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(record, emptyList(), events),
            approvals = emptyList(),
        )
    }
}
