package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.RunEventMetadata
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
    "skill.selected" to "Skill 已选择",
    "memory.recall.disabled" to "关闭记忆召回",
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
                "工具" to metadata.toolName,
                "结果" to metadata.content,
                "耗时" to "${metadata.durationMs}ms",
                "成功" to metadata.success.toDisplayText(),
                "验证" to metadata.verified?.toDisplayText(),
                "使用记忆" to metadata.memoryIdsUsed.takeIf { it.isNotEmpty() }?.joinToString("、"),
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
