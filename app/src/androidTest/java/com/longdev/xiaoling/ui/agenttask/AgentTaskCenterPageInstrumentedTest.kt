package com.longdev.xiaoling.ui.agenttask

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunRestartDisposition
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentTaskCenterPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageRoutesRunActionsWithoutConcreteViewModel() {
        val actions = FakeAgentTaskCenterActions()
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                AgentTaskCenterPage(
                    state = taskCenterState(),
                    actions = actions,
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("刷新 Agent 任务").performClick()
        composeRule.onNodeWithTag("agent-task-run-run-1").performClick()
        composeRule.onNodeWithContentDescription("重试 Agent Run").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.refreshCount)
            assertEquals(listOf("run-1"), actions.selectedRunIds)
            assertEquals(listOf("run-1"), actions.retryRunIds)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun restartRequiredCardRoutesExplicitNewRunAction() {
        val actions = FakeAgentTaskCenterActions()
        composeRule.setContent {
            MaterialTheme {
                AgentTaskCenterPage(
                    state = taskCenterState(restartRequired = true),
                    actions = actions,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("创建关联新 Run").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("run-1"), actions.retryRunIds)
        }
    }

    private fun taskCenterState(restartRequired: Boolean = false): AgentTaskCenterUiState {
        val events = if (restartRequired) {
            listOf(
                RunEventRecord(
                    id = "event-recovery-run-1",
                    runId = "run-1",
                    type = "run.recovered",
                    message = "启动恢复收敛",
                    createdAt = 3L,
                    metadata = RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.EXECUTING,
                        toStatus = AgentRunStatus.CANCELLED,
                        reason = "旧 Run 不可原地恢复",
                        resumeKind = AgentRunResumeKind.RESTART_REQUIRED,
                        restartDisposition = AgentRunRestartDisposition(
                            code = AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
                            reason = "旧 Run 不可原地恢复",
                            evidenceBoundary = "保留旧 Run 审计事实",
                            suggestedAction = "确认后创建关联新 Run",
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }
        val detail = AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = "run-1",
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    goal = "重新整理今日任务",
                    status = if (restartRequired) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED,
                    result = null,
                    errorMessage = "provider unavailable",
                    createdAt = 1L,
                    updatedAt = 2L,
                    completedAt = 3L,
                ),
                steps = emptyList(),
                events = events,
            ),
            approvals = emptyList(),
        )
        return AgentTaskCenterUiState(
            runs = listOf(
                AgentTaskCenterRunUiState(
                    detail = detail,
                    selected = false,
                    retrying = false,
                ),
            ),
        )
    }

    private class FakeAgentTaskCenterActions : AgentTaskCenterActions {
        var refreshCount = 0
        val selectedRunIds = mutableListOf<String>()
        val retryRunIds = mutableListOf<String>()

        override fun refreshAgentRunHistory() {
            refreshCount += 1
        }

        override fun selectAgentRun(runId: String) {
            selectedRunIds += runId
        }

        override fun requestAgentRunRetry(runId: String) {
            retryRunIds += runId
        }

        override fun confirmAgentRunRetry() = Unit

        override fun cancelAgentRunRetry() = Unit
    }
}
