package com.longdev.xiaoling.ui.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                    ),
                    onConfirm = { confirmCount += 1 },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("记录当前时间").assertExists()
        composeRule.onNodeWithText("1.").assertExists()
        composeRule.onNodeWithText("读取当前时间").assertExists()
        composeRule.onNodeWithText("可能触发审批：notes.create", substring = true).assertExists()
        composeRule.onNodeWithText("工具边界：app.current_time、notes.create", substring = true).assertExists()
        composeRule.onNodeWithTag("personal-task-plan-confirm").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmCount) }
    }
}
