package com.longdev.xiaoling.agent

import org.json.JSONObject

enum class AgentVerificationStatus {
    VERIFIED,
    FAILED,
    READABLE_ONLY,
}

data class VerifiedAgentContext(
    val runId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val success: Boolean,
    val verificationStatus: AgentVerificationStatus,
    val rawResult: String,
)

object VerifiedAgentContextCodec {
    fun encode(context: VerifiedAgentContext): String {
        return JSONObject()
            .put("runId", context.runId)
            .put("toolName", context.toolName)
            .put("arguments", JSONObject(context.arguments))
            .put("success", context.success)
            .put("verificationStatus", context.verificationStatus.name)
            .put("rawResult", context.rawResult)
            .toString()
    }

    fun decode(raw: String?): VerifiedAgentContext? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val argumentsJson = json.optJSONObject("arguments") ?: JSONObject()
            val arguments = buildMap {
                argumentsJson.keys().forEach { key -> put(key, argumentsJson.optString(key)) }
            }
            VerifiedAgentContext(
                runId = json.getString("runId"),
                toolName = json.getString("toolName"),
                arguments = arguments,
                success = json.getBoolean("success"),
                verificationStatus = AgentVerificationStatus.valueOf(json.getString("verificationStatus")),
                rawResult = json.getString("rawResult"),
            )
        }.getOrNull()
    }
}
