package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject

internal object RunEventMetadataCodec {
    fun encode(metadata: RunEventMetadata): String {
        return when (metadata) {
            is RunEventMetadata.ToolCall -> JSONObject()
                .put("id", metadata.id)
                .put("toolName", metadata.toolName)
                .put("risk", metadata.risk.name)
                .putArguments(metadata.arguments)
            is RunEventMetadata.ToolResult -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("content", metadata.content)
                .put("durationMs", metadata.durationMs)
                .put("success", metadata.success)
                .put("verified", metadata.verified)
                .put("memoryIdsUsed", metadata.memoryIdsUsed.toStringJsonArray())
            is RunEventMetadata.ApprovalRequest -> JSONObject()
                .put("id", metadata.id)
                .put("toolName", metadata.toolName)
                .put("risk", metadata.risk.name)
                .putArguments(metadata.arguments)
                .put("status", metadata.status.name)
                .put("expiresAt", metadata.expiresAt)
                .put("reason", metadata.reason)
            is RunEventMetadata.ApprovalDecision -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("approved", metadata.approved)
                .put("reason", metadata.reason)
            is RunEventMetadata.ApprovalSkipped -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("reason", metadata.reason)
            is RunEventMetadata.ToolVerification -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("status", metadata.status.name)
            is RunEventMetadata.Reason -> JSONObject()
                .put("reason", metadata.reason)
            is RunEventMetadata.Recovery -> JSONObject()
                .put("fromStatus", metadata.fromStatus.name)
                .put("toStatus", metadata.toStatus.name)
                .put("reason", metadata.reason)
        }.toString()
    }

    fun decode(type: String, raw: String?): RunEventMetadata? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            when (type) {
                "tool.call.proposed",
                "tool.call.validated" -> RunEventMetadata.ToolCall(
                    id = json.requiredString("id"),
                    toolName = json.requiredToolName(),
                    risk = ToolRisk.valueOf(json.requiredString("risk")),
                    arguments = json.arguments(),
                )
                "tool.result" -> RunEventMetadata.ToolResult(
                    toolName = json.requiredToolName(),
                    content = json.requiredString("content"),
                    durationMs = json.getLong("durationMs"),
                    success = json.getBoolean("success"),
                    verified = json.booleanOrNull("verified"),
                    memoryIdsUsed = json.stringListOrEmpty("memoryIdsUsed"),
                )
                "approval.requested",
                "approval.request_decided" -> RunEventMetadata.ApprovalRequest(
                    id = json.requiredString("id"),
                    toolName = json.requiredToolName(),
                    risk = ToolRisk.valueOf(json.requiredString("risk")),
                    arguments = json.arguments(),
                    status = ApprovalRequestStatus.valueOf(json.requiredString("status")),
                    expiresAt = json.getLong("expiresAt"),
                    // long: v6 审批事件使用 decisionReason，新格式统一为 reason；别名兼容保证升级后用户决定仍可审计。
                    reason = json.stringOrNull("reason") ?: json.stringOrNull("decisionReason"),
                )
                "approval.granted",
                "approval.denied" -> RunEventMetadata.ApprovalDecision(
                    toolName = json.requiredToolName(),
                    approved = json.getBoolean("approved"),
                    reason = json.requiredString("reason"),
                )
                "approval.skipped" -> RunEventMetadata.ApprovalSkipped(
                    toolName = json.requiredToolName(),
                    reason = json.requiredString("reason"),
                )
                "tool.verify" -> RunEventMetadata.ToolVerification(
                    toolName = json.requiredToolName(),
                    status = ToolVerificationStatus.valueOf(json.requiredString("status").uppercase()),
                )
                "llm.summarize.fallback",
                "skill.selected",
                "run.failed",
                "run.timeout",
                "run.cancelled",
                "run.budget_exhausted" -> RunEventMetadata.Reason(json.requiredString("reason"))
                "run.recovered" -> RunEventMetadata.Recovery(
                    fromStatus = AgentRunStatus.valueOf(json.requiredString("fromStatus")),
                    toStatus = AgentRunStatus.valueOf(json.requiredString("toStatus")),
                    reason = json.requiredString("reason"),
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun JSONObject.putArguments(arguments: Map<String, String>): JSONObject {
        return put(
            "arguments",
            JSONObject().apply {
                // long: 审计参数按 key 排序落库，避免同一工具调用因 Map 遍历顺序不同产生无意义差异。
                arguments.toSortedMap().forEach { (key, value) -> put(key, value) }
            },
        )
    }

    private fun JSONObject.requiredToolName(): String =
        stringOrNull("toolName")
            ?: stringOrNull("name")
            ?: stringOrNull("tool")
            ?: error("RunEvent metadata 缺少工具名称")

    private fun JSONObject.requiredString(key: String): String =
        stringOrNull(key) ?: error("RunEvent metadata 缺少 $key")

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.stringListOrEmpty(key: String): List<String> {
        // long: Android org.json 不会稳定地把 Kotlin List 包装成 JSONArray；显式数组是新格式，同时兼容早期已落库的字符串化 JSON 数组。
        val values = optJSONArray(key) ?: optString(key)
            .takeIf { it.startsWith("[") }
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        return JSONArray().apply { this@toStringJsonArray.forEach(::put) }
    }

    private fun JSONObject.arguments(): Map<String, String> =
        optJSONObject("arguments")?.toStringMap().orEmpty()

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        keys().forEach { key -> put(key, optString(key)) }
    }
}
