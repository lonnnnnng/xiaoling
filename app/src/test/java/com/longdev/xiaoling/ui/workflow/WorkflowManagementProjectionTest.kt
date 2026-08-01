package com.longdev.xiaoling.ui.workflow

import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowDeviceActionDecision
import com.longdev.xiaoling.automation.WorkflowDeviceActionDecisionStatus
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionRecord
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.AgentToolResultRecord
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.ToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowManagementProjectionTest {
    @Test
    fun openAppUiStateUsesActionLabelAndFollowUpBoundary() {
        val action = WorkflowDeviceActionUiState(
            outcome = WorkflowDeviceActionUiOutcome.VERIFIED,
            action = "open_app",
            detail = "已执行并验证",
        )

        assertEquals("打开应用", action.actionLabel)
        assertEquals(
            "本次打开应用不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行",
            action.followUpGuidance,
        )
    }

    @Test
    fun projectShowsDeniedOpenAppWithoutApprovalArguments() {
        val workflow = workflow(id = "workflow-open-app-denied", enabled = true)
        val agentRunId = "agent-run-open-app-denied"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-open-app-denied",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-open-app-denied",
                workflowRunId = "workflow-run-open-app-denied",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "打开系统计算器",
                detail = "打开允许列表中的系统计算器",
                agentRunId = agentRunId,
                result = null,
                errorMessage = "用户未批准工具执行",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceActionApprovalsByAgentRunId = mapOf(
                agentRunId to listOf(
                    approval(
                        runId = agentRunId,
                        status = ApprovalRequestStatus.DENIED,
                        decisionReason = "用户已在设备动作审批浮层拒绝",
                        toolName = "device.open_app",
                    ),
                ),
            ),
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.USER_DENIED, action.outcome)
        assertEquals("open_app", action.action)
        assertEquals("打开应用", action.actionLabel)
        assertFalse(action.toString().contains("com.android.calculator2"))
        assertFalse(action.toString().contains("raw-package-secret"))
    }

    @Test
    fun homeActionUiStateUsesSafeNavigationLabelAndFollowUpBoundary() {
        val action = WorkflowDeviceActionUiState(
            outcome = WorkflowDeviceActionUiOutcome.VERIFIED,
            action = "home",
            detail = "已执行并验证",
        )

        assertEquals("返回桌面", action.actionLabel)
        assertEquals(
            "本次返回桌面不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行",
            action.followUpGuidance,
        )
    }

    @Test
    fun backActionUiStateUsesSafeNavigationLabelAndFollowUpBoundary() {
        val action = WorkflowDeviceActionUiState(
            outcome = WorkflowDeviceActionUiOutcome.VERIFIED,
            action = "back",
            detail = "已执行并验证",
        )

        assertEquals("返回", action.actionLabel)
        assertEquals(
            "本次返回不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行",
            action.followUpGuidance,
        )
    }

    @Test
    fun swipeActionUiStateUsesRedactedLabelAndRiskAwareFollowUpBoundary() {
        val action = WorkflowDeviceActionUiState(
            outcome = WorkflowDeviceActionUiOutcome.VERIFIED,
            action = "swipe",
            detail = "已执行并验证",
        )

        assertEquals("滚动", action.actionLabel)
        assertEquals(
            "本次滚动不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行",
            action.followUpGuidance,
        )
        assertFalse(action.followUpGuidance.contains("审批"))
    }

    @Test
    fun projectShowsDeniedTypeTextWithoutInputTextOrApprovalArguments() {
        val workflow = workflow(id = "workflow-type-text-denied", enabled = true)
        val agentRunId = "agent-run-type-text-denied"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-type-text-denied",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-type-text-denied",
                workflowRunId = "workflow-run-type-text-denied",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "输入安全文本",
                detail = "在当前输入框输入安全文本",
                agentRunId = agentRunId,
                result = null,
                errorMessage = "用户未批准工具执行",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceActionApprovalsByAgentRunId = mapOf(
                agentRunId to listOf(
                    approval(
                        runId = agentRunId,
                        status = ApprovalRequestStatus.DENIED,
                        decisionReason = "用户已在设备动作审批浮层拒绝",
                        toolName = "device.type_text",
                    ),
                ),
            ),
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.USER_DENIED, action.outcome)
        assertEquals("type_text", action.action)
        assertFalse(action.toString().contains("Workflow safe text"))
        assertFalse(action.toString().contains("snapshot-secret"))
        assertFalse(action.toString().contains("ref-secret"))
    }

    @Test
    fun projectDoesNotBindDeviceActionApprovalFromAnotherAgentRun() {
        val workflow = workflow(id = "workflow-device-action-isolated", enabled = true)
        val expectedAgentRunId = "agent-run-device-action-expected"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-device-action-isolated",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-device-action-isolated",
                workflowRunId = "workflow-run-device-action-isolated",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "点击安全按钮",
                detail = "点击当前页面中的安全按钮",
                agentRunId = expectedAgentRunId,
                result = null,
                errorMessage = "用户未批准工具执行",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )

        val projectedStep = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceActionApprovalsByAgentRunId = mapOf(
                expectedAgentRunId to listOf(
                    approval(
                        runId = "agent-run-device-action-other",
                        status = ApprovalRequestStatus.DENIED,
                        decisionReason = "用户已在设备动作审批浮层拒绝",
                    ),
                ),
            ),
        ).items.single().runs.single().steps.single()

        assertTrue(projectedStep.deviceActions.isEmpty())
    }

    @Test
    fun projectUsesExecutionFailureAfterApprovedDeviceActionWhenNoDecisionWasPersisted() {
        val workflow = workflow(id = "workflow-device-action-window-changed", enabled = true)
        val agentRunId = "agent-run-device-action-window-changed"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-device-action-window-changed",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-device-action-window-changed",
                workflowRunId = "workflow-run-device-action-window-changed",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "点击安全按钮",
                detail = "点击当前页面中的安全按钮",
                agentRunId = agentRunId,
                result = null,
                errorMessage = "页面 window generation 已变化，必须重新观察",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceActionApprovalsByAgentRunId = mapOf(
                agentRunId to listOf(
                    approval(
                        runId = agentRunId,
                        status = ApprovalRequestStatus.APPROVED,
                        decisionReason = "用户已在设备动作审批浮层批准",
                    ),
                ),
            ),
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.WINDOW_CHANGED, action.outcome)
        assertEquals("审批后页面窗口发生变化，设备动作未通过执行验证", action.detail)
        assertFalse(action.toString().contains("window generation"))
    }

    @Test
    fun projectKeepsApprovedExecutionFailureVisibleAlongsideEarlierCancelledAttempt() {
        val workflow = workflow(id = "workflow-device-action-multiple-attempts", enabled = true)
        val agentRunId = "agent-run-device-action-multiple-attempts"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-device-action-multiple-attempts",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-device-action-multiple-attempts",
                workflowRunId = "workflow-run-device-action-multiple-attempts",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "点击安全按钮",
                detail = "点击当前页面中的安全按钮",
                agentRunId = agentRunId,
                result = null,
                errorMessage = "页面 window generation 已变化，必须重新观察",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )

        val actions = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceActionApprovalsByAgentRunId = mapOf(
                agentRunId to listOf(
                    approval(
                        runId = agentRunId,
                        status = ApprovalRequestStatus.CANCELLED,
                        decisionReason = "设备动作审批等待已取消",
                    ),
                    approval(
                        runId = agentRunId,
                        status = ApprovalRequestStatus.APPROVED,
                        decisionReason = "用户已在设备动作审批浮层批准",
                    ),
                ),
            ),
        ).items.single().runs.single().steps.single().deviceActions

        assertEquals(
            listOf(WorkflowDeviceActionUiOutcome.CANCELLED, WorkflowDeviceActionUiOutcome.WINDOW_CHANGED),
            actions.map(WorkflowDeviceActionUiState::outcome),
        )
        assertFalse(actions.toString().contains("window generation"))
    }

    @Test
    fun projectClassifiesCancelledDeviceActionApprovalsIntoStableUiOutcomes() {
        val cases = listOf(
            Triple(
                "设备动作审批等待已取消",
                WorkflowDeviceActionUiOutcome.CANCELLED,
                "本次设备动作审批已取消",
            ),
            Triple(
                "审批期间活动页面已经切换",
                WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                "审批期间页面窗口发生变化，设备动作未执行",
            ),
            Triple(
                "审批期间目标页面内容已经变化",
                WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                "审批期间页面窗口发生变化，设备动作未执行",
            ),
            Triple(
                "审批期间出现了多个无法区分的 Accessibility overlay",
                WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                "审批期间页面窗口发生变化，设备动作未执行",
            ),
            Triple(
                "审批期间 Accessibility overlay 身份发生变化",
                WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                "审批期间页面窗口发生变化，设备动作未执行",
            ),
            Triple(
                "审批期间出现了额外窗口或原窗口集合发生变化",
                WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                "审批期间页面窗口发生变化，设备动作未执行",
            ),
            Triple(
                "当前窗口状态不允许显示设备动作审批",
                WorkflowDeviceActionUiOutcome.OVERLAY_UNAVAILABLE,
                "设备动作审批浮层不可用，设备动作未执行",
            ),
            Triple(
                "系统拒绝显示设备动作审批浮层",
                WorkflowDeviceActionUiOutcome.OVERLAY_UNAVAILABLE,
                "设备动作审批浮层不可用，设备动作未执行",
            ),
            Triple(
                "无障碍服务已断开，设备动作审批已取消",
                WorkflowDeviceActionUiOutcome.SERVICE_DISCONNECTED,
                "无障碍服务已断开，设备动作未执行",
            ),
            Triple(
                "已有设备动作审批正在显示，本次请求已取消",
                WorkflowDeviceActionUiOutcome.BUSY,
                "已有设备动作审批正在处理，本次动作未执行",
            ),
        )

        cases.forEachIndexed { index, (reason, expectedOutcome, expectedDetail) ->
            val workflow = workflow(id = "workflow-device-action-cancelled-$index", enabled = true)
            val agentRunId = "agent-run-device-action-cancelled-$index"
            val workflowRun = run(
                workflowId = workflow.id,
                runId = "workflow-run-device-action-cancelled-$index",
                status = WorkflowRunStatus.FAILED,
                step = WorkflowStepRecord(
                    id = "step-device-action-cancelled-$index",
                    workflowRunId = "workflow-run-device-action-cancelled-$index",
                    sequence = 1,
                    type = "AGENT",
                    status = WorkflowStepStatus.FAILED,
                    title = "点击安全按钮",
                    detail = "点击当前页面中的安全按钮",
                    agentRunId = agentRunId,
                    result = null,
                    errorMessage = reason,
                    createdAt = 1L,
                    startedAt = 2L,
                    completedAt = 3L,
                ),
            )

            val action = WorkflowManagementProjection.project(
                loading = false,
                error = null,
                workflows = listOf(workflow),
                runs = listOf(workflowRun),
                scheduledTasks = emptyList(),
                schedules = emptyList(),
                mutatingWorkflowIds = emptySet(),
                mutatingScheduledTaskIds = emptySet(),
                mutatingWorkflowScheduleIds = emptySet(),
                schedulingWorkflowId = null,
                runningWorkflowId = null,
                sendingMessage = false,
                deviceActionApprovalsByAgentRunId = mapOf(
                    agentRunId to listOf(
                        approval(
                            runId = agentRunId,
                            status = ApprovalRequestStatus.CANCELLED,
                            decisionReason = reason,
                        ),
                    ),
                ),
            ).items.single().runs.single().steps.single().deviceActions.single()

            assertEquals(expectedOutcome, action.outcome)
            assertEquals(expectedDetail, action.detail)
            assertFalse(action.toString().contains(reason))
        }
    }

    @Test
    fun projectShowsDeniedDeviceActionAsStableSafeUiOutcome() {
        val workflow = workflow(id = "workflow-device-action-denied", enabled = true)
        val agentRunId = "agent-run-device-action-denied"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-device-action-denied",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-device-action-denied",
                workflowRunId = "workflow-run-device-action-denied",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "点击安全按钮",
                detail = "点击当前页面中的安全按钮",
                agentRunId = agentRunId,
                result = null,
                errorMessage = "用户未批准工具执行",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceActionApprovalsByAgentRunId = mapOf(
                agentRunId to listOf(
                    approval(
                        runId = agentRunId,
                        status = ApprovalRequestStatus.DENIED,
                        decisionReason = "用户已在设备动作审批浮层拒绝",
                    ),
                ),
            ),
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.USER_DENIED, action.outcome)
        assertEquals("tap_ref", action.action)
        assertEquals("用户拒绝了本次设备动作", action.detail)
        assertNull(action.beforePackageName)
        assertNull(action.afterPackageName)
        assertFalse(action.toString().contains("snapshot-secret"))
        assertFalse(action.toString().contains("ref-secret"))
        assertFalse(action.toString().contains("银行卡密码"))
    }

    @Test
    fun projectRedactsRawDeviceActionResultFromStepPreviousOutputsAndRunResult() {
        val workflow = workflow(id = "workflow-redact-device-action", enabled = true)
        val rawActionResult = """
            {"ruleVersion":"workflow-device-action-result-v1","safetyRuleVersion":"workflow-device-action-safety-v1","action":"tap_ref","beforePackageName":"com.example.before","afterPackageName":"com.example.after","afterNodeCount":12,"afterRedactedNodeCount":2,"afterTruncated":false,"afterObservedAt":1700000000000,"verified":true}
        """.trimIndent()
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-redact-device-action",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-redact-device-action",
                workflowRunId = "workflow-run-redact-device-action",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "点击安全按钮",
                detail = "点击当前页面中的安全按钮",
                agentRunId = "agent-run-redact-device-action",
                result = rawActionResult,
                errorMessage = "动作结果未形成可信判定",
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(
                    goal = "点击安全按钮",
                    previousOutputs = listOf(rawActionResult),
                ),
            ),
        ).let { detail ->
            detail.copy(
                run = detail.run.copy(
                    result = rawActionResult,
                    errorMessage = rawActionResult,
                ),
            )
        }

        val projectedRun = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single()

        assertEquals("设备动作原始结果已隐藏，请查看下方本地判定", projectedRun.steps.single().output)
        assertEquals(
            listOf("设备动作原始输出已隐藏，请查看对应步骤证据"),
            projectedRun.steps.single().previousOutputs,
        )
        assertEquals("设备动作原始结果已隐藏，请查看步骤中的本地判定", projectedRun.result)
        assertEquals("设备动作错误详情已隐藏，请查看步骤中的本地判定", projectedRun.errorMessage)
        assertFalse(projectedRun.toString().contains("workflow-device-action-result-v1"))
        assertFalse(projectedRun.toString().contains("afterObservedAt"))
    }

    @Test
    fun projectIncludesVerifiedDeviceActionDecisionWithoutRawActionIdentity() {
        val workflow = workflow(id = "workflow-device-action", enabled = true)
        val agentRunId = "agent-run-device-action"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-device-action",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-device-action",
                workflowRunId = "workflow-run-device-action",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "点击安全按钮",
                detail = "点击当前页面中的安全按钮",
                agentRunId = agentRunId,
                result = "已完成设备动作",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                outputSnapshot = WorkflowStepSnapshotCodec.encodeOutput(
                    text = "已完成设备动作",
                    deviceActionDecisions = listOf(
                        WorkflowDeviceActionDecision(
                            status = WorkflowDeviceActionDecisionStatus.VERIFIED,
                            action = "tap_ref",
                            beforePackageName = "com.example.before",
                            afterPackageName = "com.example.after",
                            afterNodeCount = 12,
                            afterRedactedNodeCount = 2,
                            afterTruncated = true,
                            afterObservedAt = 1_700_000_000_000L,
                        ),
                    ),
                ),
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, action.outcome)
        assertEquals("tap_ref", action.action)
        assertEquals("com.example.before", action.beforePackageName)
        assertEquals("com.example.after", action.afterPackageName)
        assertEquals(12, action.afterNodeCount)
        assertEquals(2, action.afterRedactedNodeCount)
        assertEquals(true, action.afterTruncated)
        assertEquals(1_700_000_000_000L, action.afterObservedAt)
        assertEquals("workflow-device-action-decision-v1", action.decisionRuleVersion)
        assertFalse(action.toString().contains("snapshot-secret"))
        assertFalse(action.toString().contains("ref-secret"))
        assertFalse(action.toString().contains("fingerprint-secret"))
        assertFalse(action.toString().contains("[0,0,100,100]"))
        assertFalse(action.toString().contains("raw-arguments-secret"))
    }

    @Test
    fun projectIncludesVerifiedSwipeWithoutViewportOrReferenceIdentity() {
        val fingerprint = "a".repeat(64)
        val workflow = workflow(id = "workflow-swipe-verified", enabled = true)
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-swipe-verified",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-swipe-verified",
                workflowRunId = "workflow-run-swipe-verified",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "向上滚动当前设置页",
                detail = "向上滚动当前可滚动区域",
                agentRunId = "agent-run-swipe-verified",
                result = "已完成滚动",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                outputSnapshot = WorkflowStepSnapshotCodec.encodeOutput(
                    text = "已完成滚动",
                    deviceActionDecisions = listOf(
                        WorkflowDeviceActionDecision(
                            status = WorkflowDeviceActionDecisionStatus.VERIFIED,
                            action = "swipe",
                            beforePackageName = "com.android.settings",
                            afterPackageName = "com.android.settings",
                            afterNodeCount = 24,
                            afterRedactedNodeCount = 0,
                            afterTruncated = false,
                            afterObservedAt = 1_700_000_000_000L,
                        ),
                    ),
                ),
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, action.outcome)
        assertEquals("滚动", action.actionLabel)
        assertEquals("com.android.settings", action.beforePackageName)
        assertEquals("com.android.settings", action.afterPackageName)
        assertFalse(action.followUpGuidance.contains("审批"))
        assertFalse(action.toString().contains("snapshot-secret"))
        assertFalse(action.toString().contains("ref-secret"))
        assertFalse(action.toString().contains(fingerprint))
    }

    @Test
    fun projectIncludesVerifiedOpenAppTargetPackageWithoutRawApprovalIdentity() {
        val workflow = workflow(id = "workflow-open-app-verified", enabled = true)
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-open-app-verified",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-open-app-verified",
                workflowRunId = "workflow-run-open-app-verified",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "打开系统计算器",
                detail = "打开允许列表中的系统计算器",
                agentRunId = "agent-run-open-app-verified",
                result = "已打开系统计算器",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                outputSnapshot = WorkflowStepSnapshotCodec.encodeOutput(
                    text = "已打开系统计算器",
                    deviceActionDecisions = listOf(
                        WorkflowDeviceActionDecision(
                            status = WorkflowDeviceActionDecisionStatus.VERIFIED,
                            action = "open_app",
                            beforePackageName = "com.longdev.xiaoling",
                            afterPackageName = "com.android.calculator2",
                            afterNodeCount = 15,
                            afterRedactedNodeCount = 0,
                            afterTruncated = false,
                            afterObservedAt = 1_700_000_000_000L,
                        ),
                    ),
                ),
            ),
        )

        val action = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.single().deviceActions.single()

        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, action.outcome)
        assertEquals("打开应用", action.actionLabel)
        assertEquals("com.longdev.xiaoling", action.beforePackageName)
        assertEquals("com.android.calculator2", action.afterPackageName)
        assertFalse(action.toString().contains("snapshot_id"))
        assertFalse(action.toString().contains("package_name"))
    }

    @Test
    fun projectRedactsRawDeviceSnapshotFromStepPreviousOutputsAndRunResult() {
        val workflow = workflow(id = "workflow-redact-output", enabled = true)
        val agentRunId = "agent-run-redact-output"
        val rawSnapshot = """
            {"snapshot_id":"snapshot-secret","package":"com.example.notes","captured_at":1700000000000,"redacted_node_count":0,"truncated":false,"nodes":[{"text":"银行卡密码 123456","ref":"ref-secret"}]}
        """.trimIndent()
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-redact-output",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-redact-output",
                workflowRunId = "workflow-run-redact-output",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = agentRunId,
                result = "Agent 任务已完成\n- 工具：device.snapshot\n- 结果：$rawSnapshot",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(
                    goal = "观察设备",
                    previousOutputs = listOf(rawSnapshot),
                ),
            ),
        ).let { detail ->
            detail.copy(run = detail.run.copy(result = "执行结果：$rawSnapshot"))
        }

        val projectedRun = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = agentRunId,
                results = listOf(toolResult(content = rawSnapshot, runId = agentRunId)),
            ),
        ).items.single().runs.single()

        assertEquals("设备观察已记录，请查看下方已验证证据", projectedRun.steps.single().output)
        assertEquals(
            listOf("设备观察输出已脱敏，请查看对应步骤证据"),
            projectedRun.steps.single().previousOutputs,
        )
        assertEquals("设备观察已记录，请查看步骤中的已验证证据", projectedRun.result)
        assertFalse(projectedRun.toString().contains("银行卡密码"))
        assertFalse(projectedRun.toString().contains("ref-secret"))
        assertFalse(projectedRun.toString().contains("snapshot-secret"))
    }

    @Test
    fun projectRedactsEscapedAndLegacyDeviceSnapshotShapes() {
        val workflow = workflow(id = "workflow-redact-legacy", enabled = true)
        val escapedLegacySnapshot = """{\"packageName\":\"com.example.notes\",\"capturedAt\":1700000000000,\"redactedNodeCount\":0,\"nodes\":[{\"text\":\"银行卡密码 123456\",\"ref\":\"ref-secret\"}]}"""
        val partialCurrentSnapshot = """{"package":"com.example.notes","captured_at":1700000000000,"truncated":false,"nodes":[{"text":"银行卡密码 123456"}]}"""
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-redact-legacy",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-redact-legacy",
                workflowRunId = "workflow-run-redact-legacy",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = null,
                result = partialCurrentSnapshot,
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(
                    goal = "观察设备",
                    previousOutputs = listOf(escapedLegacySnapshot),
                ),
            ),
        ).let { detail ->
            detail.copy(run = detail.run.copy(result = escapedLegacySnapshot))
        }

        val projectedRun = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single()

        assertEquals("设备观察已记录，请查看下方已验证证据", projectedRun.steps.single().output)
        assertEquals(listOf("设备观察输出已脱敏，请查看对应步骤证据"), projectedRun.steps.single().previousOutputs)
        assertEquals("设备观察已记录，请查看步骤中的已验证证据", projectedRun.result)
        assertFalse(projectedRun.toString().contains("银行卡密码"))
        assertFalse(projectedRun.toString().contains("ref-secret"))
    }

    @Test
    fun projectRejectsFailedUnverifiedAndMalformedDeviceObservationEvidence() {
        val workflow = workflow(id = "workflow-reject-evidence", enabled = true)
        val agentRunId = "agent-run-reject-evidence"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-reject-evidence",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-reject-evidence",
                workflowRunId = "workflow-run-reject-evidence",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = agentRunId,
                result = "观察未形成可信证据",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )
        val validSnapshot = """
            {"snapshot_id":"snapshot-valid","package":"com.example.notes","window_id":7,"window_generation":8,"captured_at":1700000000000,"expires_at":1700000030000,"redacted_node_count":0,"truncated":false,"nodes":[]}
        """.trimIndent()

        val projectedStep = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = agentRunId,
                results = listOf(
                    toolResult(
                        content = validSnapshot,
                        runId = agentRunId,
                        success = false,
                        verificationStatus = ToolVerificationStatus.PASSED,
                    ),
                    toolResult(
                        content = validSnapshot,
                        runId = agentRunId,
                        verificationStatus = ToolVerificationStatus.FAILED,
                    ),
                    toolResult(content = "not-json", runId = agentRunId),
                    toolResult(content = validSnapshot, runId = agentRunId, toolName = "notes.list"),
                ),
            ),
        ).items.single().runs.single().steps.single()

        assertTrue(projectedStep.deviceObservations.isEmpty())
    }

    @Test
    fun projectDoesNotBindDeviceObservationFromAnotherAgentRun() {
        val workflow = workflow(id = "workflow-isolated", enabled = true)
        val expectedAgentRunId = "agent-run-expected"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-isolated",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-isolated",
                workflowRunId = "workflow-run-isolated",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = expectedAgentRunId,
                result = "已观察当前页面",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )
        val validSnapshot = """
            {"snapshot_id":"snapshot-other","package":"com.example.other","window_id":7,"window_generation":8,"captured_at":1700000000000,"expires_at":1700000030000,"redacted_node_count":0,"truncated":false,"nodes":[]}
        """.trimIndent()

        val projectedStep = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = expectedAgentRunId,
                results = listOf(toolResult(content = validSnapshot, runId = "agent-run-other")),
            ),
        ).items.single().runs.single().steps.single()

        assertTrue(projectedStep.deviceObservations.isEmpty())
    }

    @Test
    fun projectIncludesVerifiedDeviceObservationWithoutExposingRawSnapshotData() {
        val workflow = workflow(id = "workflow-observe", enabled = true)
        val agentRunId = "agent-run-observe"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-observe",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-observe",
                workflowRunId = "workflow-run-observe",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = agentRunId,
                result = "已观察当前页面",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )
        val rawSnapshot = """
            {
              "snapshot_id":"snapshot-secret",
              "package":"com.example.notes",
              "window_title":"私人笔记",
              "window_id":7,
              "window_generation":8,
              "captured_at":1700000000000,
              "expires_at":1700000005000,
              "redacted_node_count":1,
              "truncated":false,
              "nodes":[
                {"index":0,"depth":0,"role":"button","text":"银行卡密码 123456","bounds":[0,0,100,100],"enabled":true,"selected":false,"redacted":false,"ref":"ref-secret","actions":["tap"]},
                {"index":1,"parent_index":0,"depth":1,"role":"text","bounds":[0,0,100,100],"enabled":true,"selected":false,"redacted":true,"actions":[]}
              ]
            }
        """.trimIndent()

        val result = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = agentRunId,
                results = listOf(toolResult(content = rawSnapshot)),
            ),
        )

        val observation = result.items.single().runs.single().steps.single().deviceObservations.single()
        assertEquals("com.example.notes", observation.packageName)
        assertEquals(2, observation.nodeCount)
        assertEquals(1, observation.redactedNodeCount)
        assertFalse(observation.truncated)
        assertEquals(1_700_000_000_000L, observation.capturedAt)
        assertEquals(193L, observation.durationMs)
        assertEquals("已验证", observation.verificationLabel)
        assertEquals("有限可复核", observation.decisionLabel)
        assertEquals("1 个节点已脱敏", observation.decisionReason)
        assertEquals("workflow-device-observation-v1", observation.decisionRuleVersion)
        assertEquals("仅确认包名与快照摘要，不确认节点正文、目标完成或动作授权", observation.decisionScope)
        assertFalse(observation.toString().contains("私人笔记"))
        assertFalse(observation.toString().contains("银行卡密码"))
        assertFalse(observation.toString().contains("ref-secret"))
        assertFalse(observation.toString().contains("snapshot-secret"))
    }

    @Test
    fun projectAggregatesWorkflowStateAndDerivesAvailableActions() {
        val activeWorkflow = workflow(id = "workflow-active", enabled = true)
        val disabledWorkflow = workflow(id = "workflow-disabled", enabled = false)
        val activeRun = run(
            workflowId = activeWorkflow.id,
            runId = "run-active",
            status = WorkflowRunStatus.RUNNING,
        )
        val failedRun = run(
            workflowId = activeWorkflow.id,
            runId = "run-failed",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-failed",
                workflowRunId = "run-failed",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "步骤 1",
                detail = "fallback goal",
                agentRunId = null,
                result = null,
                errorMessage = "provider unavailable",
                createdAt = 2L,
                startedAt = 3L,
                completedAt = 4L,
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(
                    goal = "真实目标",
                    previousOutputs = listOf("前序输出"),
                ),
                outputSnapshot = WorkflowStepSnapshotCodec.encodeOutput("失败前输出"),
            ),
        )
        val scheduledTask = task(
            id = "task-scheduled",
            workflowId = activeWorkflow.id,
            type = ScheduledTaskType.ONE_TIME,
            status = ScheduledTaskStatus.SCHEDULED,
        )
        val completedTask = task(
            id = "task-completed",
            workflowId = activeWorkflow.id,
            type = ScheduledTaskType.RECURRING,
            status = ScheduledTaskStatus.COMPLETED,
        )
        val schedule = schedule(workflowId = activeWorkflow.id)
        val pendingRetry = WorkflowRetryConfirmationUiState(
            runId = failedRun.run.id,
            workflowName = activeWorkflow.name,
            retryFromSequence = 1,
            reusedStepCount = 0,
        )

        val result = WorkflowManagementProjection.project(
            loading = false,
            error = "last refresh failed",
            workflows = listOf(activeWorkflow, disabledWorkflow),
            runs = listOf(activeRun, failedRun),
            scheduledTasks = listOf(scheduledTask, completedTask),
            schedules = listOf(schedule),
            mutatingWorkflowIds = setOf(disabledWorkflow.id),
            mutatingScheduledTaskIds = setOf(scheduledTask.id),
            mutatingWorkflowScheduleIds = setOf(schedule.id),
            schedulingWorkflowId = activeWorkflow.id,
            runningWorkflowId = null,
            sendingMessage = false,
            pendingRetryConfirmation = pendingRetry,
        )

        assertEquals("last refresh failed", result.error)
        assertEquals(listOf(activeWorkflow.id, disabledWorkflow.id), result.items.map { it.id })
        assertEquals(pendingRetry, result.pendingRetryConfirmation)

        val active = result.items.first()
        assertTrue(active.running)
        assertTrue(active.scheduling)
        assertFalse(active.canEdit)
        assertFalse(active.canRun)
        assertFalse(active.canSchedule)
        assertFalse(active.canToggleEnabled)
        assertEquals(schedule.id, active.schedule?.id)
        assertFalse(active.schedule?.canCancel ?: true)
        assertEquals(listOf(scheduledTask.id, completedTask.id), active.scheduledTasks.map { it.id })
        assertTrue(active.scheduledTasks.first().canCancel)
        assertTrue(active.scheduledTasks.first().mutating)
        assertFalse(active.scheduledTasks.last().canCancel)
        assertEquals(listOf(activeRun.run.id, failedRun.run.id), active.runs.map { it.id })
        assertFalse(active.runs.first().canRetry)
        assertFalse(active.runs.last().canRetry)
        assertEquals("真实目标", active.runs.last().steps.single().goal)
        assertEquals(listOf("前序输出"), active.runs.last().steps.single().previousOutputs)
        assertEquals("失败前输出", active.runs.last().steps.single().output)

        val disabled = result.items.last()
        assertFalse(disabled.running)
        assertFalse(disabled.canEdit)
        assertFalse(disabled.canRun)
        assertFalse(disabled.canSchedule)
        assertFalse(disabled.canToggleEnabled)
        assertNull(disabled.schedule)
    }

    @Test
    fun projectDisablesGlobalActionsWhileAnotherWorkflowIsBusyAndRestoresThemWhenIdle() {
        val first = workflow(id = "workflow-first", enabled = true)
        val second = workflow(id = "workflow-second", enabled = true)
        val failedRun = run(
            workflowId = second.id,
            runId = "run-failed",
            status = WorkflowRunStatus.FAILED,
        )

        val busy = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(first, second),
            runs = listOf(failedRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = first.id,
            runningWorkflowId = first.id,
            sendingMessage = false,
        ).items.last()

        assertFalse(busy.canRun)
        assertFalse(busy.canSchedule)
        assertFalse(busy.runs.single().canRetry)

        val idle = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(first, second),
            runs = listOf(failedRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.last()

        assertTrue(idle.canRun)
        assertTrue(idle.canSchedule)
        assertTrue(idle.runs.single().canRetry)
    }

    private fun workflow(id: String, enabled: Boolean): WorkflowRecord {
        return WorkflowRecord(
            id = id,
            name = id,
            goal = "goal-$id",
            enabled = enabled,
            createdAt = 1L,
            updatedAt = 2L,
            steps = listOf(
                WorkflowStepDefinitionRecord(
                    id = "step-$id",
                    workflowId = id,
                    sequence = 1,
                    goal = "goal-$id",
                    idempotencyKey = "key-$id",
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            ),
        )
    }

    private fun run(
        workflowId: String,
        runId: String,
        status: WorkflowRunStatus,
        step: WorkflowStepRecord? = null,
    ): WorkflowRunDetail {
        return WorkflowRunDetail(
            run = WorkflowRunRecord(
                id = runId,
                workflowId = workflowId,
                trigger = WorkflowTrigger.MANUAL,
                scheduledTaskId = null,
                plannedAt = null,
                conversationId = "conversation-1",
                agentRunId = null,
                status = status,
                result = null,
                errorMessage = null,
                createdAt = 1L,
                startedAt = null,
                completedAt = null,
            ),
            steps = listOfNotNull(step),
        )
    }

    private fun task(
        id: String,
        workflowId: String,
        type: ScheduledTaskType,
        status: ScheduledTaskStatus,
    ): ScheduledTaskRecord {
        return ScheduledTaskRecord(
            id = id,
            workflowId = workflowId,
            type = type,
            scheduleId = null,
            status = status,
            plannedAt = 10L,
            workRequestId = null,
            workflowRunId = null,
            actualStartedAt = null,
            completedAt = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun schedule(workflowId: String): WorkflowScheduleRecord {
        return WorkflowScheduleRecord(
            id = "schedule-1",
            workflowId = workflowId,
            type = WorkflowScheduleType.DAILY,
            timeOfDayMinutes = 9 * 60,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
            enabled = true,
            nextTaskId = "task-scheduled",
            nextPlannedAt = 10L,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun toolResult(
        content: String,
        runId: String = "agent-run-observe",
        toolName: String = "device.snapshot",
        success: Boolean = true,
        verificationStatus: ToolVerificationStatus = ToolVerificationStatus.PASSED,
    ): AgentToolResultRecord {
        return AgentToolResultRecord(
            toolCallId = "tool-call-snapshot",
            runId = runId,
            eventId = "event-result",
            toolName = toolName,
            content = content,
            success = success,
            errorMessage = null,
            durationMs = 193L,
            executorVerified = true,
            verificationStatus = verificationStatus,
            verifiedEventId = "event-verified",
            memoryIdsUsed = emptyList(),
            replaySafety = ToolReplaySafety.RESTART_REQUIRED,
            executionReceipt = null,
            createdAt = 4L,
            verifiedAt = 5L,
        )
    }

    private fun approval(
        runId: String,
        status: ApprovalRequestStatus,
        decisionReason: String,
        toolName: String = "device.tap_ref",
    ): WorkflowDeviceActionApprovalEvidence {
        val rawApproval = ApprovalRequestRecord(
            id = "approval-$runId-${status.name}",
            runId = runId,
            conversationId = "conversation-1",
            toolCallId = "tool-call-${toolName.substringAfterLast('.')}",
            toolName = toolName,
            toolDescription = if (toolName == "device.type_text") "向节点输入普通文本" else "点击节点引用",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf(
                "snapshot_id" to "snapshot-secret",
                "ref" to "ref-secret",
                "content" to "银行卡密码",
                "text_sha256" to "fingerprint-secret",
                "text_length" to "18",
            ),
            status = status,
            decisionReason = "$decisionReason；不得展示 snapshot-secret/ref-secret/银行卡密码",
            createdAt = 4L,
            expiresAt = Long.MAX_VALUE,
            decidedAt = 5L,
        )
        return checkNotNull(WorkflowDeviceActionApprovalEvidencePolicy.project(rawApproval))
    }

    private fun observationsFor(
        agentRunId: String,
        results: List<AgentToolResultRecord>,
    ): Map<String, List<WorkflowDeviceObservationUiState>> {
        return mapOf(
            agentRunId to WorkflowDeviceObservationProjection.project(
                expectedAgentRunId = agentRunId,
                ledger = AgentToolLedgerRecord(results = results),
            ),
        )
    }
}
