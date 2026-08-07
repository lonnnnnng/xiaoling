package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution

internal data class TaskScheduleControlCompletionPresentation(
    val role: String,
    val text: String,
)

internal data class TrustedTaskScheduleControlResult(
    val taskName: String,
    val state: TaskScheduleControlState,
)

internal enum class TaskScheduleControlState {
    PAUSED,
    ALREADY_PAUSED,
    RESUMED,
    ALREADY_RESUMED,
}

internal fun presentTaskScheduleControlCompletion(
    context: VerifiedAgentContext,
): TaskScheduleControlCompletionPresentation? {
    if (context.runId.isBlank()) return null
    val executions = context.toolExecutions.ifEmpty {
        listOf(
            VerifiedToolExecution(
                toolName = context.toolName,
                arguments = context.arguments,
                success = context.success,
                verificationStatus = context.verificationStatus,
                rawResult = context.rawResult,
            ),
        )
    }
    val controlExecutions = executions.filter { execution -> execution.toolName in TASK_SCHEDULE_CONTROL_TOOL_NAMES }
    if (controlExecutions.size != 1) return null
    val execution = controlExecutions.single()
    if (!execution.success || execution.verificationStatus != AgentVerificationStatus.VERIFIED) return null
    val trustedResult = parseTrustedTaskScheduleControlResult(
        toolName = execution.toolName,
        arguments = execution.arguments,
        rawResult = execution.rawResult,
    ) ?: return null
    val taskName = trustedResult.taskName
    val text = when (trustedResult.state) {
        TaskScheduleControlState.PAUSED ->
            "周期计划已暂停：$taskName\n后续不会生成新的执行实例。正在运行的实例和旧运行记录保持不变。"
        TaskScheduleControlState.ALREADY_PAUSED ->
            "周期计划已处于暂停状态：$taskName\n无需重复操作。正在运行的实例和旧运行记录保持不变。"
        TaskScheduleControlState.RESUMED ->
            "周期计划已恢复：$taskName\n只安排当前时间之后的一个实例，不补跑暂停期间的周期。旧运行记录保持不变。"
        TaskScheduleControlState.ALREADY_RESUMED ->
            "周期计划已处于恢复状态：$taskName\n无需重复操作，也不会补跑暂停期间的周期。旧运行记录保持不变。"
    }
    // long: 暂停/恢复终态只消费应用执行器的稳定首行，不把模型总结、内部 ID 或原始调度回执提升为任务事实。
    return TaskScheduleControlCompletionPresentation(role = "assistant", text = text)
}

internal fun parseTrustedTaskScheduleControlResult(
    toolName: String,
    arguments: Map<String, String>,
    rawResult: String,
): TrustedTaskScheduleControlResult? {
    if (arguments.keys != setOf(TASK_NAME_ARGUMENT)) return null
    val taskName = arguments[TASK_NAME_ARGUMENT]
        ?.trim()
        ?.takeIf { name ->
            name.isNotEmpty() &&
                name.length <= MAX_VISIBLE_TASK_NAME_LENGTH &&
                name.none { character -> character == '\n' || character == '\r' }
        }
        ?: return null
    val resultPrefix = "任务“$taskName”："
    val stateText = rawResult.lineSequence().firstOrNull()
        ?.takeIf { line -> line.startsWith(resultPrefix) }
        ?.removePrefix(resultPrefix)
        ?: return null
    val state = when (toolName) {
        TASK_PAUSE_TOOL_NAME -> when {
            stateText.startsWith(PAUSE_CHANGED_RESULT) -> TaskScheduleControlState.PAUSED
            stateText.startsWith(PAUSE_UNCHANGED_RESULT) -> TaskScheduleControlState.ALREADY_PAUSED
            else -> return null
        }
        TASK_RESUME_TOOL_NAME -> when {
            stateText.startsWith(RESUME_CHANGED_RESULT) -> TaskScheduleControlState.RESUMED
            stateText.startsWith(RESUME_UNCHANGED_RESULT) -> TaskScheduleControlState.ALREADY_RESUMED
            else -> return null
        }
        else -> return null
    }
    // long: 会话终态与答案级导航共用同一解析结果，避免某一入口接受了另一入口会拒绝的伪造或漂移文案。
    return TrustedTaskScheduleControlResult(taskName = taskName, state = state)
}

private const val TASK_PAUSE_TOOL_NAME = "tasks.pause"
private const val TASK_RESUME_TOOL_NAME = "tasks.resume"
private const val TASK_NAME_ARGUMENT = "name"
private const val MAX_VISIBLE_TASK_NAME_LENGTH = 100
private const val PAUSE_CHANGED_RESULT = "周期计划已暂停，后续不会生成新的执行实例"
private const val PAUSE_UNCHANGED_RESULT = "周期计划已经暂停，无需重复操作"
private const val RESUME_CHANGED_RESULT = "周期计划已恢复"
private const val RESUME_UNCHANGED_RESULT = "周期计划已经处于恢复状态，无需重复操作"
private val TASK_SCHEDULE_CONTROL_TOOL_NAMES = setOf(TASK_PAUSE_TOOL_NAME, TASK_RESUME_TOOL_NAME)
