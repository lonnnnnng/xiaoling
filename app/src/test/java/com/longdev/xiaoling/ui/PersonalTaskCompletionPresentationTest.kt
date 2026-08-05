package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowGoalVerificationDecision
import com.longdev.xiaoling.automation.WorkflowGoalVerificationReason
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalTaskCompletionPresentationTest {
    @Test
    fun immediateTaskUsesPersistedGoalDecisionInsteadOfModelSummary() {
        val completion = PersonalTaskCompletionPresentation.immediate(
            decision = decision(WorkflowGoalVerificationStatus.PARTIAL),
        )

        assertEquals("任务目标仅部分完成", completion.title)
        assertEquals("已验证步骤 1/2，可查看任务步骤和证据", completion.message)
    }

    @Test
    fun immediateTaskWithoutGoalContractStillExposesCommittedWorkflow() {
        val completion = PersonalTaskCompletionPresentation.immediate(
            decision = null,
        )

        assertEquals("个人任务已完成", completion.title)
        assertEquals("执行结果已写入当前会话，可查看任务步骤和证据", completion.message)
    }

    @Test
    fun reminderKeepsConfirmedScheduleLabelVisible() {
        val completion = PersonalTaskCompletionPresentation.reminder(
            scheduleLabel = "每天 08:30",
        )

        assertEquals("应用内提醒已创建", completion.title)
        assertEquals("每天 08:30 · 系统可能延迟执行", completion.message)
    }

    private fun decision(status: WorkflowGoalVerificationStatus) = WorkflowGoalVerificationDecision(
        sourceGoal = "打开天气并查看当前天气",
        status = status,
        reason = WorkflowGoalVerificationReason.STEP_INCOMPLETE,
        requiredToolNames = listOf("device.open_app", "device.snapshot"),
        matchedRequiredToolNames = listOf("device.open_app"),
        expectedFinalPackageName = "com.google.android.apps.weather",
        actualFinalPackageName = "com.google.android.apps.weather",
        completedStepCount = 1,
        totalStepCount = 2,
    )
}
