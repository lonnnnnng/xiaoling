package com.longdev.xiaoling.ui.workflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkflowManagementPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageRoutesWorkflowActionsWithoutConcreteViewModel() {
        val actions = FakeWorkflowManagementActions()
        var permissionRequestCount = 0
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                WorkflowManagementPage(
                    state = workflowState(),
                    actions = actions,
                    onRequestNotificationPermission = { permissionRequestCount += 1 },
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("刷新工作流").performClick()
        composeRule.onNodeWithContentDescription("手动运行").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("创建计划").assertIsEnabled().performClick()
        composeRule.onNodeWithText("创建", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("workflow-item-workflow-1").performClick()
        composeRule.onNodeWithContentDescription("重试 Workflow Run").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.refreshCount)
            assertEquals(listOf("workflow-1"), actions.runWorkflowIds)
            assertEquals(listOf("workflow-1" to 1), actions.oneTimeSchedules)
            assertEquals(listOf("run-1"), actions.retryRunIds)
            assertEquals(1, permissionRequestCount)
            assertEquals(1, backCount)
        }
    }

    private fun workflowState(): WorkflowManagementUiState {
        val run = WorkflowRunUiState(
            id = "run-1",
            status = WorkflowRunStatus.FAILED,
            createdAt = 1L,
            retryOfWorkflowRunId = null,
            result = null,
            errorMessage = "provider unavailable",
            workerStopReasonCode = null,
            workerStopReasonName = null,
            steps = emptyList(),
            canRetry = true,
        )
        return WorkflowManagementUiState(
            items = listOf(
                WorkflowItemUiState(
                    id = "workflow-1",
                    name = "每日回顾",
                    enabled = true,
                    primaryGoal = "总结当天工作",
                    stepGoals = listOf("总结当天工作"),
                    latestRun = run,
                    runs = listOf(run),
                    scheduledTasks = listOf(
                        WorkflowScheduledTaskUiState(
                            id = "task-1",
                            type = ScheduledTaskType.ONE_TIME,
                            status = ScheduledTaskStatus.COMPLETED,
                            plannedAt = 1L,
                            actualStartedAt = 1L,
                            errorMessage = null,
                            workerStopReasonCode = null,
                            workerStopReasonName = null,
                            mutating = false,
                            canCancel = false,
                        ),
                    ),
                    schedule = null,
                    scheduling = false,
                    running = false,
                    canEdit = true,
                    canRun = true,
                    canSchedule = true,
                    canToggleEnabled = true,
                ),
            ),
        )
    }

    private class FakeWorkflowManagementActions : WorkflowManagementActions {
        var refreshCount = 0
        val runWorkflowIds = mutableListOf<String>()
        val retryRunIds = mutableListOf<String>()
        val oneTimeSchedules = mutableListOf<Pair<String, Int>>()

        override fun refreshWorkflows() {
            refreshCount += 1
        }

        override fun createWorkflow(name: String, stepGoals: List<String>) = Unit

        override fun updateWorkflow(workflowId: String, name: String, stepGoals: List<String>) = Unit

        override fun setWorkflowEnabled(workflowId: String, enabled: Boolean) = Unit

        override fun runWorkflow(workflowId: String) {
            runWorkflowIds += workflowId
        }

        override fun requestWorkflowRunRetry(runId: String) {
            retryRunIds += runId
        }

        override fun scheduleWorkflowOnce(workflowId: String, delayMinutes: Int) {
            oneTimeSchedules += workflowId to delayMinutes
        }

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
