package com.longdev.xiaoling.ui.workflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.automation.WorkflowScheduleType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkflowManagementDialogsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingRetryShowsReuseBoundaryAndRoutesDecisionThroughFeatureActions() {
        val pending = WorkflowRetryConfirmationUiState(
            runId = "workflow-run-1",
            workflowName = "每日回顾",
            retryFromSequence = 3,
            reusedStepCount = 2,
        )
        var state by mutableStateOf(WorkflowManagementUiState(pendingRetryConfirmation = pending))
        val actions = FakeWorkflowManagementActions(
            onConfirmed = { state = state.copy(pendingRetryConfirmation = null) },
            onCancelled = { state = state.copy(pendingRetryConfirmation = null) },
        )
        composeRule.setContent {
            MaterialTheme {
                WorkflowManagementDialogs(
                    state = state,
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("每日回顾").assertExists()
        composeRule.onNodeWithText("将从步骤 3 重新执行，复用前 2 个已完成步骤。新 Run 会保留来源 Run ID，旧 Run 和历史快照不会修改。").assertExists()
        composeRule.onNodeWithTag("workflow-retry-confirm").performClick()
        composeRule.onNodeWithTag("workflow-retry-confirm").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(pendingRetryConfirmation = pending) }
        composeRule.onNodeWithTag("workflow-retry-cancel").performClick()
        composeRule.onNodeWithTag("workflow-retry-cancel").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, actions.confirmCount)
            assertEquals(1, actions.cancelCount)
        }
    }

    private class FakeWorkflowManagementActions(
        private val onConfirmed: () -> Unit,
        private val onCancelled: () -> Unit,
    ) : WorkflowManagementActions {
        var confirmCount = 0
        var cancelCount = 0

        override fun refreshWorkflows() = Unit

        override fun createWorkflow(name: String, stepGoals: List<String>) = Unit

        override fun updateWorkflow(workflowId: String, name: String, stepGoals: List<String>) = Unit

        override fun setWorkflowEnabled(workflowId: String, enabled: Boolean) = Unit

        override fun runWorkflow(workflowId: String) = Unit

        override fun requestWorkflowRunRetry(runId: String) = Unit

        override fun confirmWorkflowRunRetry() {
            confirmCount += 1
            onConfirmed()
        }

        override fun cancelWorkflowRunRetry() {
            cancelCount += 1
            onCancelled()
        }

        override fun scheduleWorkflowOnce(workflowId: String, delayMinutes: Int) = Unit

        override fun scheduleWorkflowRecurring(
            workflowId: String,
            type: WorkflowScheduleType,
            hour: Int,
            minute: Int,
            dayOfWeek: Int?,
        ) = Unit

        override fun cancelScheduledTask(taskId: String) = Unit

        override fun cancelWorkflowSchedule(scheduleId: String) = Unit
    }
}
