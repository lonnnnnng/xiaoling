package com.longdev.xiaoling.shared.agent

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import kotlinx.serialization.json.*

/** long: 完整私有结果帧包含幂等键，只用于平台交换；模型和可信上下文必须经各自脱敏投影。 */
object SharedToolExecutionResultCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_FRAME_BYTES = 1_048_576
    const val MAX_REFERENCES = 256
    const val MAX_TOOL_CALL_ID_CHARS = 256
    private val frameKeys = setOf("schemaVersion", "toolCallId", "result")
    private val resultKeys = setOf("success", "content", "verified", "memoryIdsUsed", "knowledgeReferences",
        "executionReceipt", "verificationEvidence", "recoveryFailure", "readableEvidence")
    private val receiptKeys = setOf("toolCallId", "operationId", "idempotencyKey", "status")
    private val verificationKeys = setOf("status", "toolCallId", "reasonCode")
    private val knowledgeKeys = setOf("retrievalId", "documentId", "documentName", "documentRevision",
        "chunkId", "chunkSequence", "startOffset", "endOffset")
    private val recoveryKeys = setOf("code", "reason", "suggestedAction")
    private val readableKeys = setOf("kind", "toolCallId", "snapshotId", "contentHash", "sourceRef")

    fun encode(frame: SharedToolResultFrame): String {
        val result = frame.result
        // long: 直接构造的巨大列表不能等到生成 JSON 后才拒绝；这些限额只约束新增交换帧，不限制原执行路径。
        require(frame.toolCallId.length <= MAX_TOOL_CALL_ID_CHARS)
        require(result.memoryIdsUsed.size <= MAX_REFERENCES && result.knowledgeReferences.size <= MAX_REFERENCES)
        require(result.content.length <= MAX_FRAME_BYTES)
        val wire = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("toolCallId", frame.toolCallId)
            put("result", buildJsonObject {
                put("success", result.success)
                put("content", result.content)
                put("verified", result.verified?.let(::JsonPrimitive) ?: JsonNull)
                put("memoryIdsUsed", JsonArray(result.memoryIdsUsed.map(::JsonPrimitive)))
                put("knowledgeReferences", JsonArray(result.knowledgeReferences.map { reference -> buildJsonObject {
                    put("retrievalId", reference.retrievalId); put("documentId", reference.documentId)
                    put("documentName", reference.documentName); put("documentRevision", reference.documentRevision)
                    put("chunkId", reference.chunkId); put("chunkSequence", reference.chunkSequence)
                    put("startOffset", reference.startOffset); put("endOffset", reference.endOffset)
                } }))
                put("executionReceipt", result.executionReceipt?.let { receipt -> buildJsonObject {
                    put("toolCallId", receipt.toolCallId); put("operationId", receipt.operationId)
                    put("idempotencyKey", receipt.idempotencyKey?.let(::JsonPrimitive) ?: JsonNull)
                    put("status", receipt.status.name)
                } } ?: JsonNull)
                put("verificationEvidence", result.verification?.let { verification -> buildJsonObject {
                    put("status", verification.status.name)
                    put("toolCallId", verification.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                    put("reasonCode", verification.reasonCode?.let(::JsonPrimitive) ?: JsonNull)
                } } ?: JsonNull)
                put("recoveryFailure", result.recoveryFailure?.let { failure -> buildJsonObject {
                    put("code", failure.code); put("reason", failure.reason); put("suggestedAction", failure.suggestedAction)
                } } ?: JsonNull)
                put("readableEvidence", result.readableEvidence?.let {
                    Json.parseToJsonElement(SharedToolReadableEvidenceCodec.encode(it))
                } ?: JsonNull)
            })
        }.toString()
        // long: 新帧必须能无损回读，超限、无效 Unicode 或坏身份不能写成两端解释不同的结果。
        require(decode(wire, frame.toolCallId) == frame) { "工具结果帧无法完整回读" }
        return wire
    }

    fun decode(raw: String, expectedToolCallId: String): SharedToolResultFrame = try {
        require(expectedToolCallId.isNotBlank() && expectedToolCallId.length <= MAX_TOOL_CALL_ID_CHARS)
        require(raw.length <= MAX_FRAME_BYTES && raw.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_FRAME_BYTES)
        // long: 建树前拒绝重复键、孤立 surrogate 和非标准 JSON，避免另一端读取不同的调用/回执身份。
        StrictJsonObjectSyntax.validate(raw, MAX_FRAME_BYTES)
        val frame = (Json.parseToJsonElement(raw) as JsonObject).exact(frameKeys)
        val version = frame["schemaVersion"] as JsonPrimitive
        require(!version.isString && version.content == SCHEMA_VERSION.toString())
        require(frame.text("toolCallId") == expectedToolCallId)
        val result = (frame["result"] as JsonObject).exact(resultKeys)
        val memories = result["memoryIdsUsed"] as JsonArray
        val references = result["knowledgeReferences"] as JsonArray
        require(memories.size <= MAX_REFERENCES && references.size <= MAX_REFERENCES)
        SharedToolResultFrame(expectedToolCallId, SharedToolExecutionResult(
            success = result.boolean("success"), content = result.text("content"),
            verified = if (result["verified"] == JsonNull) null else result.boolean("verified"),
            memoryIdsUsed = memories.map { element ->
                (element as JsonPrimitive).also { require(it.isString && it.content.isNotBlank()) }.content
            },
            knowledgeReferences = references.map { element ->
                val reference = (element as JsonObject).exact(knowledgeKeys)
                SharedKnowledgeReference(reference.text("retrievalId"), reference.text("documentId"),
                    reference.text("documentName"), reference.count("documentRevision"), reference.text("chunkId"),
                    reference.count("chunkSequence"), reference.count("startOffset"), reference.count("endOffset"))
            },
            executionReceipt = result.objectOrNull("executionReceipt")?.exact(receiptKeys)?.let {
                SharedToolExecutionReceipt(it.text("toolCallId"), it.text("operationId"),
                    SharedToolReceiptStatus.valueOf(it.text("status")), it.nullableText("idempotencyKey"))
            },
            verification = result.objectOrNull("verificationEvidence")?.exact(verificationKeys)?.let {
                SharedToolVerificationEvidence(SharedToolVerificationStatus.valueOf(it.text("status")),
                    it.nullableText("toolCallId"), it.nullableText("reasonCode"))
            },
            recoveryFailure = result.objectOrNull("recoveryFailure")?.exact(recoveryKeys)?.let {
                SharedToolRecoveryFailure(it.text("code"), it.text("reason"), it.text("suggestedAction"))
            },
            readableEvidence = result.objectOrNull("readableEvidence")?.let {
                // long: 历史页面子协议允许扩展，新完整帧只接收已知字段，不能在跨平台回读时静默丢掉未知事实。
                require(it.keys == readableKeys || it.keys == readableKeys + "browserAction")
                requireNotNull(SharedToolReadableEvidenceCodec.decode(it.toString()))
            },
        ))
    } catch (_: Exception) {
        // long: 格式失败只返回固定错误，不把正文、幂等键或解析器 cause 暴露给日志和模型。
        throw IllegalArgumentException("工具结果帧格式无效")
    }

    private fun JsonObject.exact(keys: Set<String>): JsonObject = also { require(it.keys == keys) }
    private fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).also { require(it.isString) }.content
    private fun JsonObject.nullableText(key: String): String? = if (this[key] == JsonNull) null else text(key)
    private fun JsonObject.objectOrNull(key: String): JsonObject? = if (this[key] == JsonNull) null else this[key] as JsonObject
    private fun JsonObject.boolean(key: String): Boolean {
        val value = this[key] as JsonPrimitive
        require(!value.isString && value.content in setOf("true", "false"))
        return value.content == "true"
    }
    private fun JsonObject.count(key: String): Int {
        val value = this[key] as JsonPrimitive
        require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*")))
        return value.content.toInt()
    }
}
