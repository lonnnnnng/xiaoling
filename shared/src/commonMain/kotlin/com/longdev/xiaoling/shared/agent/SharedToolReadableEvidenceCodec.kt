package com.longdev.xiaoling.shared.agent

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** long: Android 持久证据与 iOS 使用相同字段和校验；只共享脱敏事实，平台仍独立核验当前调用、审批和页面来源。 */
object SharedToolReadableEvidenceCodec {
    const val BROWSER_ACTION_SCHEMA_VERSION = 1
    const val MAX_JSON_CHARS = 1_000_000
    private val actionFields = setOf("schemaVersion", "actionId", "toolCallId", "action", "sessionId",
        "inputSnapshotId", "outputSnapshotId", "ref", "inputContentHash")

    fun encode(evidence: SharedToolReadableEvidence): String = buildJsonObject {
        put("kind", evidence.kind.name)
        put("toolCallId", evidence.toolCallId)
        put("snapshotId", evidence.snapshotId)
        put("contentHash", evidence.contentHash)
        put("sourceRef", evidence.sourceRef)
        evidence.browserAction?.let { action ->
            put("browserAction", buildJsonObject {
                put("schemaVersion", BROWSER_ACTION_SCHEMA_VERSION)
                put("actionId", action.actionId)
                put("toolCallId", action.toolCallId)
                put("action", action.action.name)
                put("sessionId", action.sessionId)
                put("inputSnapshotId", action.inputSnapshotId)
                put("outputSnapshotId", action.outputSnapshotId)
                put("ref", action.ref)
                put("inputContentHash", action.inputContentHash)
            })
        }
    }.toString().also { wire ->
        // long: 直接构造的模型也必须通过回读门禁，避免把超限或会被 Unicode 替换的身份写入无法恢复的账本。
        require(decode(wire) == evidence) { "共享可读证据无法按同一 wire 完整回读" }
    }

    fun decode(raw: String?): SharedToolReadableEvidence? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            // long: JSON 树会覆盖重复键，必须在构建树之前验证原文，否则两端可能信任不同的调用身份。
            StrictJsonObjectSyntax.validate(raw, MAX_JSON_CHARS)
            val json = Json.parseToJsonElement(raw) as JsonObject
            // long: 顶层保留旧证据的扩展字段兼容；动作存在时必须完整有效，禁止损坏后静默降为只读页面。
            val action = if ("browserAction" in json) {
                val value = json["browserAction"] as JsonObject
                require(value.keys == actionFields)
                val version = value["schemaVersion"] as JsonPrimitive
                require(!version.isString && version.content == BROWSER_ACTION_SCHEMA_VERSION.toString())
                SharedBrowserActionReceipt(
                    value.text("actionId"), value.text("toolCallId"), SharedBrowserDomAction.valueOf(value.text("action")),
                    value.text("inputSnapshotId"), value.text("outputSnapshotId"), value.text("ref"),
                    value.text("sessionId"), value.text("inputContentHash"),
                )
            } else null
            SharedToolReadableEvidence(
                SharedToolReadableEvidenceKind.valueOf(json.text("kind")), json.text("toolCallId"),
                json.text("snapshotId"), json.text("contentHash"), json.text("sourceRef"), action,
            )
        }.getOrNull()
    }

    private fun JsonObject.text(key: String): String {
        val value = this[key] as JsonPrimitive
        require(value.isString && value.content.isNotBlank())
        return value.content
    }
}
