package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

internal fun MessagePart.Tool.inspectedTaskNameForNavigation(): String? {
    if (toolName != TASK_INSPECTION_TOOL_NAME || !success) return null
    if (verificationStatus == MessageToolVerificationStatus.FAILED) return null
    if (arguments.keys != setOf(TASK_NAME_ARGUMENT)) return null
    if (result.lineSequence().firstOrNull() != TASK_INSPECTION_RESULT_HEADING) return null
    return arguments[TASK_NAME_ARGUMENT]
        ?.trim()
        ?.takeIf { name -> name.isNotEmpty() && name.length <= 100 }
}

internal fun resolveInspectedWorkflowId(
    workflows: List<WorkflowRecord>,
    taskName: String,
): String? {
    // long: 历史工具消息只保存任务名称；点击时必须重新核对当前唯一任务，重命名、删除或同名都不能猜测旧内部 ID。
    return workflows.filter { workflow -> workflow.name == taskName }.singleOrNull()?.id
}

private const val TASK_INSPECTION_TOOL_NAME = "tasks.inspect"
private const val TASK_NAME_ARGUMENT = "name"
private const val TASK_INSPECTION_RESULT_HEADING = "任务最近运行"
