package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import com.longdev.xiaoling.shared.agent.SharedAgentContextSnapshotCodec
import java.security.MessageDigest

// long: Android 保留原调用类型名，输入校验在 shared 只维护一份；Room 记录、异常和授权仍属于平台。
typealias AgentContextProjection = com.longdev.xiaoling.shared.agent.SharedAgentContextProjection
typealias AgentContextMessage = com.longdev.xiaoling.shared.agent.SharedAgentContextMessage
typealias AgentContextInput = com.longdev.xiaoling.shared.agent.SharedAgentContextInput

data class AgentContextSnapshotRecord(
    val runId: String,
    val stepId: String,
    val sequence: Int,
    val input: AgentContextInput,
    val recordSha256: String,
    val payloadBytes: Int,
    val createdAt: Long,
    val compressionVersion: Int? = null,
)

class AgentContextSnapshotException(cause: Throwable? = null) :
    IllegalStateException("上下文快照未能完整保存，本次模型请求已停止；请检查上下文大小或存储状态", cause)

internal object AgentContextSnapshotCodec {
    const val SCHEMA_VERSION = SharedAgentContextSnapshotCodec.SCHEMA_VERSION
    const val MAX_REQUEST_BYTES = SharedAgentContextSnapshotCodec.MAX_REQUEST_BYTES
    const val MAX_RUN_BYTES = SharedAgentContextSnapshotCodec.MAX_RUN_BYTES
    const val MAX_FRAMES = SharedAgentContextSnapshotCodec.MAX_FRAMES
    const val MAX_TOOL_CALLS = SharedAgentContextSnapshotCodec.MAX_TOOL_CALLS

    fun encode(input: AgentContextInput): String {
        SharedAgentContextSnapshotCodec.validateInput(input)
        // long: 同 Step 幂等及历史摘要绑定原 JSONObject 正文，Android 写入不能改用 shared 的新字段顺序或转义样式。
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
        val input = SharedAgentContextSnapshotCodec.decode(json)
        // long: 保留原 Android 重编码容量检查；只用于校验，不替换存储原文或据此重算旧摘要。
        encode(input)
        return input
    }

    fun utf8Size(text: String): Int = SharedAgentContextSnapshotCodec.utf8Size(text)

    fun recordHash(runId: String, stepId: String, sequence: Int, payload: String, compressionVersion: Int? = null): String =
        MessageDigest.getInstance("SHA-256")
            .digest(SharedAgentContextSnapshotCodec.recordHashBytes(runId, stepId, sequence, payload, compressionVersion))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
