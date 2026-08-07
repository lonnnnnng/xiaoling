package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRetryConfirmationPresentationTest {
    @Test
    fun restartRequiredConfirmationExplainsNewRunBoundary() {
        val presentation = presentAgentRetryConfirmation(
            pending(AgentRetryConfirmationKind.RESTART_REQUIRED_RELAUNCH),
        )

        assertEquals("确认创建关联新 Run", presentation.title)
        assertTrue(presentation.detail.contains("保留旧 Run 的终态和审计记录"))
        assertTrue(presentation.detail.contains("不会恢复旧模型协程"))
        assertTrue(presentation.detail.contains("重放旧工具"))
        assertTrue(presentation.detail.contains("仍需重新审批"))
    }

    @Test
    fun controlledReplayAndEvidenceRetryKeepDistinctTitles() {
        assertEquals(
            "确认受控关联重试",
            presentAgentRetryConfirmation(
                pending(AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY),
            ).title,
        )
        assertEquals(
            "确认重新运行",
            presentAgentRetryConfirmation(
                pending(AgentRetryConfirmationKind.EVIDENCE_RETRY),
            ).title,
        )
    }

    private fun pending(kind: AgentRetryConfirmationKind) = AgentRetryConfirmationUiState(
        runId = "run-source",
        goal = "继续原任务",
        evidenceCode = AgentTaskRetryEvidenceCode.NOT_COMMITTED,
        evidenceFingerprint = "fingerprint",
        kind = kind,
        expectedRestartDispositionCode = AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
    )
}
