package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowGoalVerificationDecision
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus

internal object PersonalTaskCompletionPresentation {
    fun immediate(
        workflowId: String,
        decision: WorkflowGoalVerificationDecision?,
    ): PersonalTaskCompletionUiState {
        // long: 完成标题只读取 Repository 持久化的目标级判定；模型单步总结没有资格把任务升级为“已验证完成”。
        val title = when (decision?.status) {
            WorkflowGoalVerificationStatus.VERIFIED -> "任务目标已验证完成"
            WorkflowGoalVerificationStatus.PARTIAL -> "任务目标仅部分完成"
            WorkflowGoalVerificationStatus.INCOMPLETE -> "任务目标尚未完成"
            null -> "个人任务已完成"
        }
        val message = decision?.let {
            "已验证步骤 ${it.completedStepCount}/${it.totalStepCount}，可查看任务步骤和证据"
        } ?: "执行结果已写入当前会话，可查看任务步骤和证据"
        return PersonalTaskCompletionUiState(
            workflowId = workflowId,
            title = title,
            message = message,
        )
    }

    fun reminder(
        workflowId: String,
        scheduleLabel: String,
    ): PersonalTaskCompletionUiState = PersonalTaskCompletionUiState(
        workflowId = workflowId,
        title = "应用内提醒已创建",
        message = "$scheduleLabel · 系统可能延迟执行",
    )
}
