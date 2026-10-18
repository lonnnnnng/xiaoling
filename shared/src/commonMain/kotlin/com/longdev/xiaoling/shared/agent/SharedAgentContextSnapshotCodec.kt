package com.longdev.xiaoling.shared.agent

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import kotlinx.serialization.json.*

/** long: 共享输入字段、严格解码与摘要规范字节；平台原持久 encoder 和 SHA-256 实现不由这个 codec 替换。 */
object SharedAgentContextSnapshotCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_REQUEST_BYTES = 1_048_576
    const val MAX_RUN_BYTES = 16_777_216L
    const val MAX_FRAMES = 64
    const val MAX_TOOL_CALLS = 32
    private val keys = setOf("schemaVersion", "phase", "model", "providerId", "apiMode", "projection",
        "imageCount", "documentCount", "verifiedToolCallIds", "messages")

    fun validateInput(input: SharedAgentContextInput) {
        require(input.model.isNotBlank() && input.model.length <= 200)
        require(input.providerId == null || input.providerId.isNotBlank())
        require(input.apiMode in setOf("CHAT_COMPLETIONS", "RESPONSES"))
        require(input.messages.map { it.role } == listOf("system", "user"))
        require(input.verifiedToolCallIds.size <= MAX_TOOL_CALLS)
        require(input.verifiedToolCallIds.all { it.isNotBlank() && it.length <= 200 })
        require(input.verifiedToolCallIds.distinct().size == input.verifiedToolCallIds.size)
        require(input.imageCount in 0..1 && input.documentCount in 0..1 && input.imageCount + input.documentCount <= 1)
        require(input.imageCount + input.documentCount == 0 || input.apiMode == "RESPONSES")
        // long: 身份与正文都不能在 UTF-8 编码时静默替换，否则共享输入和原持久证据会变成不同文本。
        (listOf(input.model) + listOfNotNull(input.providerId) + input.verifiedToolCallIds + input.messages.map { it.content })
            .forEach(::utf8Size)
    }

    fun encode(input: SharedAgentContextInput): String {
        validateInput(input)
        val wire = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("phase", input.phase.name)
            put("model", input.model)
            put("providerId", input.providerId?.let(::JsonPrimitive) ?: JsonNull)
            put("apiMode", input.apiMode)
            put("projection", input.projection.name)
            put("imageCount", input.imageCount)
            put("documentCount", input.documentCount)
            put("verifiedToolCallIds", JsonArray(input.verifiedToolCallIds.map(::JsonPrimitive)))
            put("messages", JsonArray(input.messages.map { message -> buildJsonObject {
                put("role", message.role); put("content", message.content)
            } }))
        }.toString()
        require(utf8Size(wire) <= MAX_REQUEST_BYTES) { "上下文快照超过单请求限额" }
        return wire
    }

    fun decode(raw: String): SharedAgentContextInput = try {
        require(utf8Size(raw) <= MAX_REQUEST_BYTES)
        // long: 合法 ASCII 帧可以超过一百万字符，扫描字符预算必须随原 1 MiB 字节边界显式传入。
        StrictJsonObjectSyntax.validate(raw, MAX_REQUEST_BYTES)
        val value = Json.parseToJsonElement(raw) as JsonObject
        require(value.keys == keys)
        val version = value["schemaVersion"] as JsonPrimitive
        require(!version.isString && version.content == SCHEMA_VERSION.toString())
        val messages = value["messages"] as JsonArray
        val ids = value["verifiedToolCallIds"] as JsonArray
        require(messages.size == 2 && ids.size <= MAX_TOOL_CALLS)
        SharedAgentContextInput(
            phase = SharedAgentLlmPhase.valueOf(value.text("phase")), model = value.text("model"),
            providerId = if (value["providerId"] == JsonNull) null else value.text("providerId"),
            apiMode = value.text("apiMode"), projection = SharedAgentContextProjection.valueOf(value.text("projection")),
            imageCount = value.count("imageCount"), documentCount = value.count("documentCount"),
            verifiedToolCallIds = ids.map { element -> (element as JsonPrimitive).also { require(it.isString) }.content },
            messages = messages.map { element ->
                val message = element as JsonObject
                require(message.keys == setOf("role", "content"))
                SharedAgentContextMessage(message.text("role"), message.text("content"))
            },
        ).also(::validateInput)
    } catch (_: Exception) {
        // long: Room 将确定的格式损坏分类为 INVALID；统一异常类型，避免新解析器异常逃出该门禁或展示正文。
        throw IllegalArgumentException("上下文快照格式无效")
    }

    fun utf8Bytes(text: String): ByteArray = try {
        text.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("上下文文本包含无效 Unicode")
    }

    fun utf8Size(text: String): Int = utf8Bytes(text).size

    /** long: 对原 payload 字节逐字段加大端长度前缀；禁止解析后重编码，否则旧记录的归属与摘要会改变。 */
    fun recordHashBytes(runId: String, stepId: String, sequence: Int, payload: String, compressionVersion: Int? = null): ByteArray {
        require(runId.isNotBlank() && stepId.isNotBlank() && sequence > 0)
        require(compressionVersion == null || compressionVersion > 0)
        val fields = if (compressionVersion == null) listOf("xiaoling-agent-context-v1", runId, stepId, sequence.toString(), payload)
            else listOf("xiaoling-agent-context-compressed-v1", runId, stepId, sequence.toString(), compressionVersion.toString(), payload)
        val encoded = fields.map(::utf8Bytes)
        val size = encoded.sumOf { it.size.toLong() + 4 }
        require(size <= Int.MAX_VALUE)
        val bytes = ByteArray(size.toInt())
        var offset = 0
        encoded.forEach { field ->
            for (shift in listOf(24, 16, 8, 0)) bytes[offset++] = (field.size ushr shift).toByte()
            field.copyInto(bytes, offset); offset += field.size
        }
        return bytes
    }

    private fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).also { require(it.isString) }.content

    private fun JsonObject.count(key: String): Int {
        val value = this[key] as JsonPrimitive
        // long: 附件数量只允许整数 0/1，不把浮点、指数、负零或字符串当成同一个历史字段值。
        require(!value.isString && value.content in setOf("0", "1"))
        return value.content.toInt()
    }
}
