package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowRunStatus

internal data class TaskRetryCompletionPresentation(
    val role: String,
    val text: String,
)

internal fun presentTaskRetryCompletion(
    taskName: String,
    status: WorkflowRunStatus,
    reusedStepCount: Int,
): TaskRetryCompletionPresentation? {
    require(reusedStepCount >= 0) { "复用步骤数不能为负数" }
    // long: 会话只展示用户任务名和稳定终态；内部 Run ID、原始错误与步骤正文留在任务中心，避免错误恢复信息扩大隐私面。
    val visibleTaskName = taskName
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_VISIBLE_TASK_NAME_LENGTH)
        .ifBlank { "未命名任务" }
    val reusedSummary = "已复用 $reusedStepCount 个已完成步骤，旧运行记录保持不变。"
    return when (status) {
        WorkflowRunStatus.COMPLETED -> TaskRetryCompletionPresentation(
            role = "assistant",
            text = "任务关联重试已完成：$visibleTaskName\n$reusedSummary 可在任务中心查看关联运行。",
        )
        WorkflowRunStatus.BLOCKED -> TaskRetryCompletionPresentation(
            role = "error",
            text = "任务关联重试等待处理：$visibleTaskName\n$reusedSummary 不会恢复或重放旧执行栈，请前往任务中心处理阻塞原因。",
        )
        WorkflowRunStatus.FAILED -> TaskRetryCompletionPresentation(
            role = "error",
            text = "任务关联重试未完成：$visibleTaskName\n关联新运行已失败；$reusedSummary 不会恢复或重放旧执行栈，请前往任务中心查看受限诊断后再决定是否重试。",
        )
        WorkflowRunStatus.CANCELLED -> TaskRetryCompletionPresentation(
            role = "error",
            text = "任务关联重试已停止：$visibleTaskName\n关联新运行已取消；$reusedSummary 不会恢复或重放旧执行栈，可前往任务中心核对最终状态。",
        )
        WorkflowRunStatus.QUEUED,
        WorkflowRunStatus.RUNNING,
        -> null
    }
}

private const val MAX_VISIBLE_TASK_NAME_LENGTH = 100
