package com.longdev.xiaoling.agent

import org.json.JSONObject

/**
 * long: 只读证据在可信上下文、RunEvent 和 Room Tool Ledger 之间共用同一脱敏格式，避免不同入口各自解析后放宽字段边界。
 */
internal object ToolReadableEvidenceCodec {
    fun encode(evidence: ToolReadableEvidence): JSONObject = JSONObject()
        .put("kind", evidence.kind.name)
        .put("toolCallId", evidence.toolCallId)
        .put("snapshotId", evidence.snapshotId)
        .put("contentHash", evidence.contentHash)
        .put("sourceRef", evidence.sourceRef)
        .apply {
            evidence.browserAction?.let { action ->
                put("browserAction", JSONObject().put("schemaVersion", 1).put("actionId", action.actionId)
                    .put("toolCallId", action.toolCallId).put("action", action.action.name).put("sessionId", action.sessionId)
                    .put("inputSnapshotId", action.inputSnapshotId).put("outputSnapshotId", action.outputSnapshotId)
                    .put("ref", action.ref).put("inputContentHash", action.inputContentHash))
            }
        }

    fun encodeToString(evidence: ToolReadableEvidence): String = encode(evidence).toString()

    fun decode(json: JSONObject?): ToolReadableEvidence? {
        json ?: return null
        val kind = json.optString("kind").takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { ToolReadableEvidenceKind.valueOf(raw) }.getOrNull() }
            ?: return null
        val toolCallId = json.optString("toolCallId").takeIf(String::isNotBlank) ?: return null
        val snapshotId = json.optString("snapshotId").takeIf(String::isNotBlank) ?: return null
        val contentHash = json.optString("contentHash").takeIf(String::isNotBlank) ?: return null
        val sourceRef = json.optString("sourceRef").takeIf(String::isNotBlank) ?: return null
        return runCatching {
            // long: 旧页面证据没有动作字段仍可读取；动作字段损坏必须拒绝整份证据，不能悄悄降级后失去调用绑定。
            val action = if (json.has("browserAction")) json.getJSONObject("browserAction").let { value ->
                val keys = buildSet { value.keys().forEach(::add) }
                require(keys == setOf("schemaVersion", "actionId", "toolCallId", "action", "sessionId", "inputSnapshotId", "outputSnapshotId", "ref", "inputContentHash"))
                require(value.get("schemaVersion") == 1)
                BrowserActionReceipt(value.getString("actionId"), value.getString("toolCallId"), BrowserDomAction.valueOf(value.getString("action")),
                    value.getString("inputSnapshotId"), value.getString("outputSnapshotId"), value.getString("ref"),
                    value.getString("sessionId"), value.getString("inputContentHash"))
            } else null
            ToolReadableEvidence(kind, toolCallId, snapshotId, contentHash, sourceRef, action)
        }.getOrNull()
    }

    fun decode(raw: String?): ToolReadableEvidence? {
        if (raw.isNullOrBlank()) return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }
}
