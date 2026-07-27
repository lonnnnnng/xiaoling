package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.latestRecoveryMetadata
import com.longdev.xiaoling.agent.toApprovalExpiryPolicyLabel

internal data class AgentRunEventPresentation(
    val summary: String,
    val fields: List<AgentRunEventField>,
    val rawFallback: String? = null,
)

internal data class AgentRunEventField(
    val label: String,
    val value: String,
)

internal data class AgentRunRestartDispositionPresentation(
    val kind: String,
    val code: String,
    val reason: String,
    val evidenceBoundary: String,
    val suggestedAction: String,
)

internal fun presentAgentRunRestartDisposition(
    metadata: RunEventMetadata.Recovery,
): AgentRunRestartDispositionPresentation? {
    if (metadata.resumeKind != AgentRunResumeKind.RESTART_REQUIRED) return null
    val disposition = metadata.restartDisposition ?: return null
    val reason = disposition.reason.takeIf { it.isNotBlank() } ?: return null
    val evidenceBoundary = disposition.evidenceBoundary.takeIf { it.isNotBlank() } ?: return null
    val suggestedAction = disposition.suggestedAction.takeIf { it.isNotBlank() } ?: return null
    // long: 任务中心只展示事件中冻结的历史处置快照；旧事件缺字段时返回空，不用当前策略替旧 Run 补造证据。
    return AgentRunRestartDispositionPresentation(
        kind = metadata.resumeKind.name,
        code = disposition.code.name,
        reason = reason,
        evidenceBoundary = evidenceBoundary,
        suggestedAction = suggestedAction,
    )
}

internal fun AgentRunDetailRecord.latestRestartDispositionPresentation(): AgentRunRestartDispositionPresentation? =
    latestRecoveryMetadata()?.let(::presentAgentRunRestartDisposition)

private val eventTitles = mapOf(
    "run.created" to "Run 已创建",
    "run.status" to "Run 状态变化",
    "step.created" to "步骤已创建",
    "step.status" to "步骤状态变化",
    "tool.verify" to "工具验证",
    "tool.result" to "工具执行结果",
    "tool.call.proposed" to "模型提出工具调用",
    "tool.call.validated" to "工具调用已校验",
    "approval.requested" to "审批请求",
    "approval.request_decided" to "审批请求",
    "approval.skipped" to "跳过审批",
    "approval.granted" to "审批通过",
    "approval.denied" to "审批拒绝",
    "llm.summarize.fallback" to "模型总结兜底",
    "run.failed" to "Run 失败",
    "run.timeout" to "Run 超时",
    "run.cancelled" to "Run 已取消",
    "run.budget_exhausted" to "Run 预算耗尽",
    "run.recovered" to "Run 恢复收敛",
    "run.recovery_failed" to "恢复验证失败",
    "agent.profile.selected" to "Agent Profile 已选择",
    "run.controlled_replay.linked" to "受控关联重试",
    "skill.selected" to "Skill 已选择",
    "memory.recall.disabled" to "关闭记忆召回",
    "llm.request.completed" to "模型请求完成",
    "llm.request.failed" to "模型请求失败",
    "run.execution_budget.updated" to "执行预算更新",
)

internal fun presentAgentRunEvent(
    type: String,
    message: String,
    metadata: RunEventMetadata?,
): AgentRunEventPresentation {
    if (metadata == null) {
        return AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = emptyList(),
            rawFallback = message,
        )
    }

    // long: sealed metadata 让每类事件只暴露合法字段组合；UI 不再按 type 猜测 JSON shape，未知历史载荷统一回退到可读 message。
    return when (metadata) {
        is RunEventMetadata.AgentProfileSelection -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "Agent" to metadata.profile.name,
                "标识" to metadata.profile.avatar,
                "Provider" to metadata.profile.providerId,
                "模型" to metadata.profile.model,
                "协议" to metadata.profile.apiMode.name,
                "上下文" to metadata.profile.contextPolicy.name,
                "记忆" to metadata.profile.memoryEnabled.toDisplayText(),
                "工具" to metadata.profile.allowedToolNames.joinToString("、"),
                "Skill" to metadata.profile.allowedSkillIds.takeIf { it.isNotEmpty() }?.joinToString("、"),
            ),
        )
        is RunEventMetadata.ControlledReplay -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "来源 Run" to metadata.sourceRunId,
                "来源 ToolCall" to metadata.sourceToolCallId,
                "新 ToolCall" to metadata.newToolCallId,
                "定义指纹" to metadata.definitionFingerprint,
            ),
        )
        is RunEventMetadata.LlmRequest -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "阶段" to metadata.phase.name,
                "模型" to metadata.model,
                "总耗时" to "${metadata.latencyMs}ms",
                "首字节" to metadata.firstByteLatencyMs?.let { "${it}ms" },
                "Prompt" to "${metadata.promptBytes} B",
                "输入 Token" to metadata.inputTokens?.toString(),
                "输出 Token" to metadata.outputTokens?.toString(),
                "总 Token" to metadata.totalTokens?.toString(),
            ),
        )
        is RunEventMetadata.LlmFailure -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "阶段" to metadata.phase.name,
                "错误码" to metadata.kind.name,
                "原因" to metadata.reason,
            ),
        )
        is RunEventMetadata.ExecutionBudget -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "已消耗" to "${metadata.consumedMs}ms",
                "总预算" to "${metadata.totalTimeoutMs}ms",
                "剩余" to "${metadata.totalTimeoutMs - metadata.consumedMs}ms",
            ),
        )
        is RunEventMetadata.ToolCall -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "调用" to metadata.id,
                "工具" to metadata.toolName,
                "风险" to metadata.risk.name,
                "参数" to metadata.arguments.toDisplayText(),
            ),
        )
        is RunEventMetadata.ToolResult -> AgentRunEventPresentation(
            summary = if (metadata.success) "工具执行成功" else "工具执行失败",
            fields = fields(
                "调用" to metadata.toolCallId,
                "工具" to metadata.toolName,
                "结果" to metadata.content,
                "耗时" to "${metadata.durationMs}ms",
                "成功" to metadata.success.toDisplayText(),
                "验证" to metadata.verified?.toDisplayText(),
                "使用记忆" to metadata.memoryIdsUsed.takeIf { it.isNotEmpty() }?.joinToString("、"),
                "知识引用" to metadata.knowledgeReferences.toKnowledgeAuditText(),
                "操作" to metadata.executionReceipt?.operationId,
                "回执状态" to metadata.executionReceipt?.status?.name,
                "重放声明" to metadata.replaySafety.name,
                "幂等证明" to metadata.executionReceipt?.let { receipt ->
                    if (receipt.idempotencyKey.isNullOrBlank()) "未记录" else "已记录"
                },
            ),
        )
        is RunEventMetadata.ApprovalRequest -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "请求" to metadata.id,
                "工具" to metadata.toolName,
                "风险" to metadata.risk.name,
                "状态" to metadata.status.name,
                "原因" to metadata.reason,
                "参数" to metadata.arguments.toDisplayText(),
                "过期策略" to metadata.expiresAt.toApprovalExpiryPolicyLabel(),
            ),
        )
        is RunEventMetadata.ApprovalDecision -> AgentRunEventPresentation(
            summary = if (metadata.approved) "审批通过" else "审批拒绝",
            fields = fields(
                "工具" to metadata.toolName,
                "决定" to metadata.approved.toDisplayText(),
                "原因" to metadata.reason,
            ),
        )
        is RunEventMetadata.ApprovalSkipped -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "工具" to metadata.toolName,
                "原因" to metadata.reason,
            ),
        )
        is RunEventMetadata.ToolVerification -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "工具" to metadata.toolName,
                "状态" to metadata.status.name,
            ),
        )
        is RunEventMetadata.Reason -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields("原因" to metadata.reason),
        )
        is RunEventMetadata.Recovery -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "原状态" to metadata.fromStatus.name,
                "新状态" to metadata.toStatus.name,
                "原因" to metadata.reason,
                "重试证据" to metadata.retryEvidenceCode?.name,
                "恢复处置" to metadata.resumeKind?.name,
                "处置码" to metadata.restartDisposition?.code?.name,
                "策略原因" to metadata.restartDisposition?.reason,
                "证据边界" to metadata.restartDisposition?.evidenceBoundary,
                "建议" to metadata.restartDisposition?.suggestedAction,
            ),
        )
        is RunEventMetadata.RecoveryFailure -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = fields(
                "工具" to metadata.toolName,
                "错误码" to metadata.code,
                "原因" to metadata.reason,
                "建议" to metadata.suggestedAction,
            ),
        )
    }
}

private fun fields(vararg values: Pair<String, String?>): List<AgentRunEventField> =
    values.mapNotNull { (label, value) -> value?.let { AgentRunEventField(label, it) } }

private fun Map<String, String>.toDisplayText(): String =
    if (isEmpty()) "无" else toSortedMap().entries.joinToString(" · ") { (key, value) -> "$key=$value" }

private fun Boolean.toDisplayText(): String = if (this) "是" else "否"

private fun String.toReadableEventTitle(): String = eventTitles[this] ?: this
