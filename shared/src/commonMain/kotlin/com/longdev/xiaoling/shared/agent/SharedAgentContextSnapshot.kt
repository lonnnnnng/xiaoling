package com.longdev.xiaoling.shared.agent

enum class SharedAgentLlmPhase { PLAN, SUMMARIZE }
enum class SharedAgentContextProjection { EXACT_TEXT, DEVICE_INPUT_REDACTED }

data class SharedAgentContextMessage(val role: String, val content: String)

/** long: 只保存模型 adapter 的文本输入与附件计数，网络鉴权、附件原文和执行授权仍由平台持有。 */
data class SharedAgentContextInput(
    val phase: SharedAgentLlmPhase,
    val model: String,
    val providerId: String?,
    val apiMode: String,
    val messages: List<SharedAgentContextMessage>,
    val verifiedToolCallIds: List<String>,
    val projection: SharedAgentContextProjection = SharedAgentContextProjection.EXACT_TEXT,
    val imageCount: Int = 0,
    val documentCount: Int = 0,
)
