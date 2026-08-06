package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunStatus
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
    ) = AgentRunRecord(
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
}
