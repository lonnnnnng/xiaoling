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
            ToolReadableEvidence(kind, toolCallId, snapshotId, contentHash, sourceRef)
        }.getOrNull()
    }

    fun decode(raw: String?): ToolReadableEvidence? {
        if (raw.isNullOrBlank()) return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }
}
