package com.longdev.xiaoling.ui.agenttask

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentTaskCenterDialogsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingRetryShowsEvidenceAndRoutesDecisionThroughFeatureActions() {
        val pending = AgentRetryConfirmationUiState(
            runId = "run-1",
            goal = "重新整理今日任务",
            evidenceCode = AgentTaskRetryEvidenceCode.COMMITTED_VERIFIED,
            evidenceFingerprint = "fingerprint-1",
        )
        var state by mutableStateOf(AgentTaskCenterUiState(pendingRetryConfirmation = pending))
        val actions = FakeAgentTaskCenterActions(
            onConfirmed = { state = state.copy(pendingRetryConfirmation = null) },
            onCancelled = { state = state.copy(pendingRetryConfirmation = null) },
        )
        composeRule.setContent {
            MaterialTheme {
                AgentTaskCenterDialogs(
                    state = state,
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("重新整理今日任务").assertExists()
        composeRule.onNodeWithText("已提交且已验证 · COMMITTED_VERIFIED").assertExists()
        composeRule.onNodeWithText("旧 Run 已有成功提交与验证事实，重试可能重复产生结果。 只有确认后才能创建关联新 Run。 写入工具仍需重新审批。").assertExists()
        composeRule.onNodeWithTag("agent-retry-confirm").performClick()
        composeRule.onNodeWithTag("agent-retry-confirm").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(pendingRetryConfirmation = pending) }
        composeRule.onNodeWithTag("agent-retry-cancel").performClick()
        composeRule.onNodeWithTag("agent-retry-cancel").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, actions.confirmCount)
            assertEquals(1, actions.cancelCount)
        }
    }

    @Test
    fun controlledReplayExplainsLinkedRunBoundaryAndFreshToolApproval() {
        val pending = AgentRetryConfirmationUiState(
            runId = "run-controlled-replay",
            goal = "重新创建资格笔记",
            evidenceCode = AgentTaskRetryEvidenceCode.NOT_COMMITTED,
            evidenceFingerprint = "fingerprint-controlled-replay",
            kind = AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY,
            expectedRestartDispositionCode =
                AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
        )
        val actions = FakeAgentTaskCenterActions(onConfirmed = {}, onCancelled = {})
        composeRule.setContent {
            MaterialTheme {
                AgentTaskCenterDialogs(
                    state = AgentTaskCenterUiState(pendingRetryConfirmation = pending),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("确认受控关联重试").assertExists()
        composeRule.onNodeWithText(
            "将创建关联新 Run 并使用来源 Run 冻结的工具名称、风险和参数。不会恢复旧 Run、旧模型协程或旧 Executor；新 Run 内的工具仍需重新审批。",
        ).assertExists()
    }

    private class FakeAgentTaskCenterActions(
        private val onConfirmed: () -> Unit,
        private val onCancelled: () -> Unit,
    ) : AgentTaskCenterActions {
        var confirmCount = 0
        var cancelCount = 0

        override fun refreshAgentRunHistory() = Unit

        override fun selectAgentRun(runId: String) = Unit

        override fun requestAgentRunRetry(runId: String) = Unit

        override fun confirmAgentRunRetry() {
            confirmCount += 1
            onConfirmed()
        }

        override fun cancelAgentRunRetry() {
            cancelCount += 1
            onCancelled()
        }
    }
}
