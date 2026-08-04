package com.longdev.xiaoling.ui.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.automation.WorkflowGoalVerificationSpec
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
                        knowledgeContextCount = 3,
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
        composeRule.onNodeWithText("计划上下文：长期记忆 2 条 · 本地知识 3 个片段").assertExists()
        composeRule.onNodeWithText("工具边界：app.current_time、notes.create", substring = true).assertExists()
        composeRule.onNodeWithTag("personal-task-plan-confirm").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmCount) }
    }

    @Test
    fun showsNonExactReminderRuleBeforeCreatingSchedule() {
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
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("应用内提醒：每日 09:00 · Asia/Shanghai", substring = true).assertExists()
        composeRule.onNodeWithText("非精确定时", substring = true).assertExists()
        composeRule.onNodeWithText("确认并创建提醒").assertExists()
    }
}
