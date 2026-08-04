package com.longdev.xiaoling.ui.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.automation.WorkflowGoalVerificationSpec
import com.longdev.xiaoling.ui.PersonalTaskPlanGenerationMetricsUiState
import com.longdev.xiaoling.ui.PendingPersonalTaskPlanUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PersonalTaskPlanDialogInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsFrozenPlanRiskAndToolBoundaryBeforeConfirmation() {
        var confirmCount = 0
        composeRule.setContent {
            MaterialTheme {
                PersonalTaskPlanDialog(
                    state = PendingPersonalTaskPlanUiState(
                        id = "plan-1",
                        conversationId = "conversation-1",
                        sourceGoal = "查看当前时间并记到笔记",
                        name = "记录当前时间",
                        steps = listOf("读取当前时间", "把时间写入笔记"),
                        agentName = "默认 Agent",
                        model = "model-a",
                        allowedToolNames = listOf("app.current_time", "notes.create"),
                        approvalToolNames = listOf("notes.create"),
                        createdAt = 1L,
                        memoryContextCount = 2,
                        memoryContextOmittedCount = 1,
                        knowledgeContextCount = 3,
                        knowledgeContextOmittedCount = 2,
                        contextBytes = 6_144,
                        generationMetrics = PersonalTaskPlanGenerationMetricsUiState(
                            modelCallCount = 1,
                            latencyMs = 4_000L,
                            firstByteLatencyMs = 320L,
                            promptBytes = 6_144,
                            inputTokens = 120L,
                            outputTokens = 30L,
                            totalTokens = 150L,
                        ),
                        targetAppPackage = "com.android.settings",
                        goalVerificationSpec = WorkflowGoalVerificationSpec(
                            requiredToolNames = listOf("app.current_time", "notes.create"),
                            expectedFinalPackageName = "com.android.settings",
                        ),
                    ),
                    onConfirm = { confirmCount += 1 },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("记录当前时间").assertExists()
        composeRule.onNodeWithText("1.").assertExists()
        composeRule.onNodeWithText("读取当前时间").assertExists()
        composeRule.onNodeWithText("限定应用：com.android.settings").assertExists()
        composeRule.onNodeWithText("完成标准：app.current_time -> notes.create", substring = true).assertExists()
        composeRule.onNodeWithText("完成时应用：com.android.settings", substring = true).assertExists()
        composeRule.onNodeWithText("可能触发审批：notes.create", substring = true).assertExists()
        composeRule.onNodeWithTag("personal-task-plan-context-usage").assertExists()
        composeRule.onNodeWithText(
            "计划上下文：长期记忆 2 条 · 本地知识 3 个片段 · 占用 6.0KB",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText(
            "上下文精简：省略长期记忆 1 条 · 本地知识 2 个片段",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("计划生成：模型 1 次 · 耗时 4.00s · TTFB 320ms · Prompt 6.0KB", substring = true).assertExists()
        composeRule.onNodeWithText("工具边界：app.current_time、notes.create", substring = true).assertExists()
        composeRule.onNodeWithTag("personal-task-plan-confirm").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmCount) }
    }

    @Test
    fun showsNonExactReminderRuleBeforeCreatingSchedule() {
        var confirmCount = 0
        composeRule.setContent {
            MaterialTheme {
                PersonalTaskPlanDialog(
                    state = PendingPersonalTaskPlanUiState(
                        id = "plan-reminder",
                        conversationId = "conversation-1",
                        sourceGoal = "每天九点提醒我喝水",
                        name = "喝水提醒",
                        steps = listOf("提醒用户喝水"),
                        agentName = "默认 Agent",
                        model = "model-a",
                        allowedToolNames = listOf("app.current_time"),
                        approvalToolNames = emptyList(),
                        createdAt = 1L,
                        reminderScheduleLabel = "每日 09:00 · Asia/Shanghai",
                    ),
                    onConfirm = { confirmCount += 1 },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("应用内提醒：每日 09:00 · Asia/Shanghai", substring = true).assertExists()
        composeRule.onNodeWithText("非精确定时", substring = true).assertExists()
        composeRule.onNodeWithText("确认并创建提醒").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmCount) }
    }

    @Test
    fun showsUnknownPlanGenerationUsageWithoutInventingCost() {
        composeRule.setContent {
            MaterialTheme {
                PersonalTaskPlanDialog(
                    state = PendingPersonalTaskPlanUiState(
                        id = "plan-unknown-telemetry",
                        conversationId = "conversation-1",
                        sourceGoal = "打开时钟",
                        name = "打开时钟",
                        steps = listOf("打开时钟应用"),
                        agentName = "默认 Agent",
                        model = "model-a",
                        allowedToolNames = listOf("device.open_app"),
                        approvalToolNames = listOf("device.open_app"),
                        createdAt = 1L,
                        generationMetrics = PersonalTaskPlanGenerationMetricsUiState(
                            modelCallCount = 1,
                            latencyMs = 850L,
                            firstByteLatencyMs = null,
                            promptBytes = 512,
                            inputTokens = null,
                            outputTokens = null,
                            totalTokens = null,
                        ),
                    ),
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("personal-task-plan-metrics").assertExists()
        composeRule.onNodeWithText("Tokens 未返回", substring = true).assertExists()
    }

    @Test
    fun cancelReturnsPlanForEditing() {
        var dismissCount = 0
        composeRule.setContent {
            MaterialTheme {
                PersonalTaskPlanDialog(
                    state = PendingPersonalTaskPlanUiState(
                        id = "plan-cancel",
                        conversationId = "conversation-1",
                        sourceGoal = "整理今天的任务",
                        name = "整理任务",
                        steps = listOf("读取任务", "整理顺序"),
                        agentName = "默认 Agent",
                        model = "model-a",
                        allowedToolNames = listOf("notes.list"),
                        approvalToolNames = emptyList(),
                        createdAt = 1L,
                    ),
                    onConfirm = {},
                    onDismiss = { dismissCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("personal-task-plan-cancel").performClick()

        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun disablesPlanActionsWhileWaitingForNotificationPermission() {
        composeRule.setContent {
            MaterialTheme {
                PersonalTaskPlanDialog(
                    state = PendingPersonalTaskPlanUiState(
                        id = "plan-permission",
                        conversationId = "conversation-1",
                        sourceGoal = "每天九点提醒我喝水",
                        name = "喝水提醒",
                        steps = listOf("提醒用户喝水"),
                        agentName = "默认 Agent",
                        model = "model-a",
                        allowedToolNames = listOf("app.current_time"),
                        approvalToolNames = emptyList(),
                        createdAt = 1L,
                        reminderScheduleLabel = "每日 09:00 · Asia/Shanghai",
                    ),
                    confirmationInProgress = true,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("等待通知权限").assertExists()
        composeRule.onNodeWithTag("personal-task-plan-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("personal-task-plan-cancel").assertIsNotEnabled()
    }
}
