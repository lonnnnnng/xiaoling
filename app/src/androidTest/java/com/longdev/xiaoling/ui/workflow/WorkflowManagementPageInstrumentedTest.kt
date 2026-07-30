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
    @Test
    fun pageDisplaysDistinctDeviceActionFailureStates() {
        val failures = listOf(
            WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.USER_DENIED,
                action = "tap_ref",
                detail = "用户拒绝了本次设备动作",
            ),
            WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.CANCELLED,
                action = "tap_ref",
                detail = "本次设备动作审批已取消",
            ),
            WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                action = "tap_ref",
                detail = "审批期间页面窗口发生变化，设备动作未执行",
            ),
            WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.OVERLAY_UNAVAILABLE,
                action = "tap_ref",
                detail = "设备动作审批浮层不可用，设备动作未执行",
            ),
            WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.SERVICE_DISCONNECTED,
                action = "tap_ref",
                detail = "无障碍服务已断开，设备动作未执行",
            ),
            WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.BUSY,
                action = "tap_ref",
                detail = "已有设备动作审批正在处理，本次动作未执行",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                WorkflowManagementPage(
                    state = workflowState(deviceActions = failures),
                    actions = FakeWorkflowManagementActions(),
                    onRequestNotificationPermission = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("workflow-item-workflow-1").performClick()

        listOf(
            "设备动作 · 用户已拒绝" to "用户拒绝了本次设备动作",
            "设备动作 · 已取消" to "本次设备动作审批已取消",
            "设备动作 · 窗口已变化" to "审批期间页面窗口发生变化，设备动作未执行",
            "设备动作 · 审批浮层不可用" to "设备动作审批浮层不可用，设备动作未执行",
            "设备动作 · 无障碍服务已断开" to "无障碍服务已断开，设备动作未执行",
            "设备动作 · 审批正忙" to "已有设备动作审批正在处理，本次动作未执行",
        ).forEach { (title, detail) ->
            composeRule.onNodeWithText(title, useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText(detail, useUnmergedTree = true).assertExists()
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageDisplaysVerifiedDeviceActionAsSafeAnswerEvidence() {
        composeRule.setContent {
            MaterialTheme {
                WorkflowManagementPage(
                    state = workflowState(
                        deviceActions = listOf(
                            WorkflowDeviceActionUiState(
                                outcome = WorkflowDeviceActionUiOutcome.VERIFIED,
                                action = "tap_ref",
                                detail = "已执行并验证",
                                beforePackageName = "com.example.before",
                                afterPackageName = "com.example.after",
                                afterNodeCount = 12,
                                afterRedactedNodeCount = 2,
                                afterTruncated = true,
                                afterObservedAt = 1_700_000_000_000L,
                                decisionRuleVersion = "workflow-device-action-decision-v1",
                            ),
                        ),
                    ),
                    actions = FakeWorkflowManagementActions(),
                    onRequestNotificationPermission = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("workflow-item-workflow-1").performClick()

        composeRule.onNodeWithTag(
            "workflow-device-action-run-1-1-0",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("设备动作 · 已执行并验证", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("动作：tap_ref", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(
            "应用：com.example.before → com.example.after",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText(
            "后置节点 12 · 脱敏 2 · 已截断",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("后置观察：", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(
            "规则 workflow-device-action-decision-v1",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText(
            "仅确认当前设备动作和后置观察已验证，不确认最终业务目标",
            useUnmergedTree = true,
        ).assertExists()
    }

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

    @Test
    fun pageDisplaysVerifiedDeviceObservationAsExpiredReadOnlyEvidence() {
        composeRule.setContent {
            MaterialTheme {
                WorkflowManagementPage(
                    state = workflowState(),
                    actions = FakeWorkflowManagementActions(),
                    onRequestNotificationPermission = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("workflow-item-workflow-1").performClick()

        composeRule.onNodeWithTag(
            "workflow-device-observation-run-1-1-0",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("设备观察 · 已验证", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("应用：com.example.notes", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("本地判断 · 有限可复核", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(
            "2 个节点已脱敏 · 规则 workflow-device-observation-v1",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText(
            "仅确认包名与快照摘要，不确认节点正文、目标完成或动作授权",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText(
            "节点引用已过期，不可用于后续动作",
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun workflowState(
        deviceActions: List<WorkflowDeviceActionUiState> = emptyList(),
    ): WorkflowManagementUiState {
        val run = WorkflowRunUiState(
            id = "run-1",
            status = WorkflowRunStatus.FAILED,
            createdAt = 1L,
            retryOfWorkflowRunId = null,
            result = null,
            errorMessage = "provider unavailable",
            workerStopReasonCode = null,
            workerStopReasonName = null,
            steps = listOf(
                WorkflowStepUiState(
                    sequence = 1,
                    title = "观察当前页面",
                    statusLabel = "已完成",
                    goal = "观察设备",
                    previousOutputs = emptyList(),
                    output = "已观察当前页面",
                    deviceObservations = listOf(
                        WorkflowDeviceObservationUiState(
                            packageName = "com.example.notes",
                            nodeCount = 15,
                            redactedNodeCount = 2,
                            truncated = false,
                            capturedAt = 1_700_000_000_000L,
                            durationMs = 193L,
                            verificationLabel = "已验证",
                            decisionLabel = "有限可复核",
                            decisionReason = "2 个节点已脱敏",
                            decisionRuleVersion = "workflow-device-observation-v1",
                            decisionScope = "仅确认包名与快照摘要，不确认节点正文、目标完成或动作授权",
                        ),
                    ),
                    deviceActions = deviceActions,
                    reusedFromStepId = null,
                ),
            ),
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

        override fun confirmWorkflowRunRetry() = Unit

        override fun cancelWorkflowRunRetry() = Unit

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
