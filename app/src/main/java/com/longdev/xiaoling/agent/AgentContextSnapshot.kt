package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class AgentContextProjection { EXACT_TEXT, DEVICE_INPUT_REDACTED }

data class AgentContextMessage(val role: String, val content: String)

/** long: 保存发送给模型 adapter 的文本输入；附件原文与鉴权配置不进入上下文副本。 */
data class AgentContextInput(
    val phase: AgentLlmPhase,
    val model: String,
    val providerId: String?,
    val apiMode: String,
    val messages: List<AgentContextMessage>,
    val verifiedToolCallIds: List<String>,
    val projection: AgentContextProjection = AgentContextProjection.EXACT_TEXT,
    val imageCount: Int = 0,
    val documentCount: Int = 0,
)

data class AgentContextSnapshotRecord(
    val runId: String,
    val stepId: String,
    val sequence: Int,
    val input: AgentContextInput,
    val recordSha256: String,
    val payloadBytes: Int,
    val createdAt: Long,
)

class AgentContextSnapshotException(cause: Throwable? = null) :
    IllegalStateException("上下文快照未能完整保存，本次模型请求已停止；请检查上下文大小或存储状态", cause)

internal object AgentContextSnapshotCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_REQUEST_BYTES = 1_048_576
    const val MAX_RUN_BYTES = 16_777_216L
    const val MAX_FRAMES = 64
    const val MAX_TOOL_CALLS = 32

    fun encode(input: AgentContextInput): String {
        require(input.model.isNotBlank() && input.model.length <= 200)
        require(input.providerId == null || input.providerId.isNotBlank())
        require(input.apiMode in setOf("CHAT_COMPLETIONS", "RESPONSES"))
        require(input.messages.map { it.role } == listOf("system", "user"))
        require(input.verifiedToolCallIds.size <= MAX_TOOL_CALLS)
        require(input.verifiedToolCallIds.all { it.isNotBlank() && it.length <= 200 })
        require(input.verifiedToolCallIds.distinct().size == input.verifiedToolCallIds.size)
        require(input.imageCount in 0..1 && input.documentCount in 0..1 && input.imageCount + input.documentCount <= 1)
        require(input.imageCount + input.documentCount == 0 || input.apiMode == "RESPONSES")
        // long: UTF-8 不能静默替换非法代理字符，否则数据库快照与实际输入会出现不可解释的差异。
        input.messages.forEach { utf8Size(it.content) }
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("phase", input.phase.name)
            .put("model", input.model)
            .put("providerId", input.providerId ?: JSONObject.NULL)
            .put("apiMode", input.apiMode)
            .put("projection", input.projection.name)
            .put("imageCount", input.imageCount)
            .put("documentCount", input.documentCount)
            .put("verifiedToolCallIds", JSONArray(input.verifiedToolCallIds))
            .put("messages", JSONArray().apply {
                input.messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            }).toString()
        require(utf8Size(json) <= MAX_REQUEST_BYTES) { "上下文快照超过单请求限额" }
        return json
    }

    fun decode(json: String): AgentContextInput {
        require(utf8Size(json) <= MAX_REQUEST_BYTES)
        val value = JSONObject(json)
        require(value.keys().asSequence().toSet() == KEYS)
        require(value.get("schemaVersion") == SCHEMA_VERSION)
        val messages = value.getJSONArray("messages")
        require(messages.length() == 2)
        val ids = value.getJSONArray("verifiedToolCallIds")
        require(ids.length() <= MAX_TOOL_CALLS)
        val input = AgentContextInput(
            phase = AgentLlmPhase.valueOf(value.strictString("phase")),
            model = value.strictString("model"),
            providerId = if (value.isNull("providerId")) null else value.strictString("providerId"),
            apiMode = value.strictString("apiMode"),
            projection = AgentContextProjection.valueOf(value.strictString("projection")),
            imageCount = value.get("imageCount") as Int,
            documentCount = value.get("documentCount") as Int,
            verifiedToolCallIds = List(ids.length()) { ids.get(it) as String },
            messages = List(messages.length()) { index ->
                val message = messages.getJSONObject(index)
                require(message.keys().asSequence().toSet() == setOf("role", "content"))
                AgentContextMessage(message.strictString("role"), message.strictString("content"))
            },
        )
        encode(input)
        return input
    }

    fun utf8Size(text: String): Int = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(text)).remaining()

    fun recordHash(runId: String, stepId: String, sequence: Int, payload: String): String {
        require(runId.isNotBlank() && stepId.isNotBlank() && sequence > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        // long: 长度前缀把身份、顺序与输入绑定在同一摘要里，避免正文相同但归属字段损坏时仍被读作有效记录。
        listOf("xiaoling-agent-context-v1", runId, stepId, sequence.toString(), payload).forEach { field ->
            utf8Size(field)
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun JSONObject.strictString(key: String): String = get(key) as String
    private val KEYS = setOf("schemaVersion", "phase", "model", "providerId", "apiMode", "projection",
        "imageCount", "documentCount", "verifiedToolCallIds", "messages")
}
