package com.longdev.xiaoling.agent

import java.security.MessageDigest

/** long: 整理只从已完成轮次的用户原话提出建议，不把模型输出或工具结果升级为用户事实。 */
object MemoryMaintenancePolicy {
    const val MAX_RUNS = 20
    const val MAX_SOURCE_BYTES = 8 * 1024
    const val MAX_OUTBOX_ROWS = 1024
    const val WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val EXECUTION_TIMEOUT_MILLIS = 15_000L

    fun suggest(text: String, conversationId: String, runId: String): AgentMemoryCandidateDraft? {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES)
        val userText = text.trim().removePrefix("/agent ")
        // long: 不为后台建议扫描全库；重复与旧事实冲突留给用户确认，接受时仍走原有去重且不覆盖旧记忆。
        return AgentMemoryCandidatePolicy.evaluateTurn(userText,
            AgentMemorySource(conversationId, runId, "已完成任务的原始用户陈述，整理建议需确认"), emptyList())
    }

    fun hash(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("xiaoling-memory-maintenance-v1".toByteArray(Charsets.UTF_8))
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
