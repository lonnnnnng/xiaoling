package com.longdev.xiaoling.ui

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentRunEventPresentation(
    val summary: String,
    val fields: List<AgentRunEventField>,
    val rawFallback: String? = null,
)

internal data class AgentRunEventField(
    val label: String,
    val value: String,
)

private data class EventDescriptor(
    val title: String,
    val fields: List<Pair<String, String>> = emptyList(),
)

private val toolCallFields = listOf(
    "调用" to "id",
    "工具" to "name",
    "风险" to "risk",
    "参数" to "arguments",
)

private val toolResultFields = listOf(
    "工具" to "tool",
    "结果" to "content",
    "耗时" to "durationMs",
    "成功" to "success",
    "验证" to "verified",
)

private val approvalDecisionFields = listOf(
    "工具" to "tool",
    "决定" to "approved",
    "原因" to "reason",
)

private val approvalSkippedFields = listOf(
    "工具" to "tool",
    "原因" to "reason",
)

private val approvalRequestFields = listOf(
    "请求" to "id",
    "工具" to "tool",
    "风险" to "risk",
    "状态" to "status",
    "原因" to "decisionReason",
    "参数" to "arguments",
    "有效期" to "expiresAt",
)

private val reasonFields = listOf("原因" to "reason")

private val recoveredFields = listOf(
    "原状态" to "fromStatus",
    "新状态" to "toStatus",
    "原因" to "reason",
)

private val eventDescriptors = mapOf(
    "run.created" to EventDescriptor("Run 已创建"),
    "run.status" to EventDescriptor("Run 状态变化"),
    "step.created" to EventDescriptor("步骤已创建"),
    "step.status" to EventDescriptor("步骤状态变化"),
    "tool.verify" to EventDescriptor("工具验证"),
    "tool.call.proposed" to EventDescriptor("模型提出工具调用", toolCallFields),
    "tool.call.validated" to EventDescriptor("工具调用已校验", toolCallFields),
    "approval.requested" to EventDescriptor("审批请求", approvalRequestFields),
    "approval.request_decided" to EventDescriptor("审批请求", approvalRequestFields),
    "approval.skipped" to EventDescriptor("跳过审批", approvalSkippedFields),
    "run.failed" to EventDescriptor("Run 失败", reasonFields),
    "run.timeout" to EventDescriptor("Run 超时", reasonFields),
    "run.cancelled" to EventDescriptor("Run 已取消", reasonFields),
    "run.budget_exhausted" to EventDescriptor("Run 预算耗尽", reasonFields),
    "run.recovered" to EventDescriptor("Run 恢复收敛", recoveredFields),
)

internal fun presentAgentRunEvent(type: String, message: String): AgentRunEventPresentation {
    val payload = runCatching { JSONObject(message) }.getOrNull()
    if (payload == null) {
        return AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = emptyList(),
            rawFallback = message,
        )
    }

    // long: 当前 RunEvent.message 仍是 JSON 字符串，运行记录页先在 UI 层结构化展示；后续迁移独立 metadata 字段时可以复用这套展示模型。
    return when (type) {
        "tool.result" -> AgentRunEventPresentation(
            summary = when {
                payload.has("success") && payload.optBoolean("success", false) -> "工具执行成功"
                payload.has("success") -> "工具执行失败"
                else -> "工具执行结果"
            },
            fields = payload.fields(toolResultFields),
            rawFallback = payload.rawFallbackIfMissing(toolResultFields, message),
        )
        "approval.granted",
        "approval.denied" -> AgentRunEventPresentation(
            summary = when {
                payload.has("approved") && payload.optBoolean("approved", false) -> "审批通过"
                payload.has("approved") -> "审批拒绝"
                else -> "审批决定"
            },
            fields = payload.fields(approvalDecisionFields),
            rawFallback = payload.rawFallbackIfMissing(approvalDecisionFields, message),
        )
        else -> AgentRunEventPresentation(
            summary = type.toReadableEventTitle(),
            fields = eventDescriptors[type]?.fields?.let { payload.fields(it) } ?: payload.genericFields(),
            rawFallback = eventDescriptors[type]?.fields?.let { payload.rawFallbackIfMissing(it, message) },
        )
    }
}

private fun JSONObject.fields(mapping: List<Pair<String, String>>): List<AgentRunEventField> {
    return mapping.mapNotNull { (label, key) ->
        if (!has(key) || isNull(key)) {
            null
        } else {
            AgentRunEventField(label = label, value = opt(key).toDisplayText(key))
        }
    }
}

private fun JSONObject.genericFields(): List<AgentRunEventField> {
    return keys().asSequence()
        .toList()
        .sorted()
        .mapNotNull { key ->
            if (isNull(key)) {
                null
            } else {
                AgentRunEventField(label = key, value = opt(key).toDisplayText(key))
            }
        }
}

private fun JSONObject.rawFallbackIfMissing(mapping: List<Pair<String, String>>, raw: String): String? {
    val anyMissing = mapping.any { (_, key) -> key != "verified" && (!has(key) || isNull(key)) }
    return if (anyMissing) raw else null
}

private fun Any?.toDisplayText(key: String): String {
    return when (this) {
        null,
        JSONObject.NULL -> ""
        is Boolean -> if (this) "是" else "否"
        is Number -> if (key == "durationMs") "${this}ms" else toString()
        is JSONObject -> keys().asSequence()
            .toList()
            .sorted()
            .joinToString(" · ") { nestedKey -> "$nestedKey=${opt(nestedKey).toDisplayText(nestedKey)}" }
        is JSONArray -> (0 until length()).joinToString(" · ") { index -> opt(index).toDisplayText(key) }
        else -> toString()
    }
}

private fun String.toReadableEventTitle(): String {
    return eventDescriptors[this]?.title ?: this
}
