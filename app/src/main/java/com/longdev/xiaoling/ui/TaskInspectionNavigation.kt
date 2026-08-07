package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

internal fun MessagePart.Tool.inspectedTaskNameForNavigation(): String? {
    if (!success) return null
    if (verificationStatus == MessageToolVerificationStatus.FAILED) return null
    if (arguments.keys != setOf(TASK_NAME_ARGUMENT)) return null
    val taskName = arguments[TASK_NAME_ARGUMENT]
        ?.trim()
        ?.takeIf { name -> name.isNotEmpty() && name.length <= 100 }
        ?: return null
    return when (toolName) {
        TASK_INSPECTION_TOOL_NAME -> taskName.takeIf {
            result.lineSequence().firstOrNull() == TASK_INSPECTION_RESULT_HEADING
        }
        TASK_CANCEL_TOOL_NAME -> taskName.takeIf { name ->
            // long: 取消卡只能由应用生成的稳定首行进入任务中心，不能因模型在结果正文中写“已取消”就伪造导航入口。
            verificationStatus == MessageToolVerificationStatus.VERIFIED &&
                name.none { character -> character == '\n' || character == '\r' } &&
                result.lineSequence().firstOrNull().orEmpty().startsWith("任务“$name”") &&
                CANCEL_RESULT_MARKERS.any { marker -> result.contains(marker) }
        }
        TASK_PAUSE_TOOL_NAME,
        TASK_RESUME_TOOL_NAME,
        -> parseTrustedTaskScheduleControlResult(
            toolName = toolName,
            arguments = arguments,
            rawResult = result,
        )
            ?.takeIf { verificationStatus == MessageToolVerificationStatus.VERIFIED }
            ?.taskName
        else -> null
    }
}

internal fun resolveInspectedWorkflowId(
    workflows: List<WorkflowRecord>,
    taskName: String,
): String? {
    // long: 历史工具消息只保存任务名称；点击时必须重新核对当前唯一任务，重命名、删除或同名都不能猜测旧内部 ID。
    return workflows.filter { workflow -> workflow.name == taskName }.singleOrNull()?.id
}

private const val TASK_INSPECTION_TOOL_NAME = "tasks.inspect"
private const val TASK_CANCEL_TOOL_NAME = "tasks.cancel"
private const val TASK_PAUSE_TOOL_NAME = "tasks.pause"
private const val TASK_RESUME_TOOL_NAME = "tasks.resume"
private const val TASK_NAME_ARGUMENT = "name"
private const val TASK_INSPECTION_RESULT_HEADING = "任务最近运行"
private val CANCEL_RESULT_MARKERS = listOf(
    "计划已取消，不会再执行",
    "后台任务已停止",
    "已请求停止后台任务",
    "已经取消并收敛",
)
