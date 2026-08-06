package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution

internal data class TaskCancelCompletionPresentation(
    val role: String,
    val text: String,
)

internal fun presentTaskCancelCompletion(
    context: VerifiedAgentContext,
): TaskCancelCompletionPresentation? {
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
    val cancelExecutions = executions.filter { execution -> execution.toolName == TASK_CANCEL_TOOL_NAME }
    if (cancelExecutions.size != 1) return null
    val execution = cancelExecutions.single()
    if (!execution.success || execution.verificationStatus != AgentVerificationStatus.VERIFIED) return null
    val taskName = visibleTaskName(execution.arguments["name"] ?: return null)
    val text = when {
        execution.rawResult.contains("计划已取消，不会再执行") ->
            "任务已取消：$taskName\n计划实例已取消，不会再执行。旧运行记录保持不变。"
        execution.rawResult.contains("后台任务已停止") ->
            "后台任务已停止：$taskName\n关联 Agent、工作流和调度实例已收敛为已取消。旧运行记录保持不变。"
        execution.rawResult.contains("已请求停止后台任务") ->
            "已请求停止任务：$taskName\n停止意图已持久化，稍后可在任务中心查看最终状态。旧运行记录保持不变。"
        execution.rawResult.contains("已经取消并收敛") ->
            "任务已处于取消状态：$taskName\n持久化状态显示任务已经取消，无需重复操作。旧运行记录保持不变。"
        else -> return null
    }
    // long: 终态摘要只接受应用生成的稳定结果前缀；原始任务回执仍留在 Tool part，不把模型文本当事实。
    return TaskCancelCompletionPresentation(role = "assistant", text = text)
}

private fun visibleTaskName(rawName: String): String = rawName
    .trim()
    .replace(Regex("\\s+"), " ")
    .take(MAX_VISIBLE_TASK_NAME_LENGTH)
    .ifBlank { "未命名任务" }

private const val TASK_CANCEL_TOOL_NAME = "tasks.cancel"
private const val MAX_VISIBLE_TASK_NAME_LENGTH = 100
