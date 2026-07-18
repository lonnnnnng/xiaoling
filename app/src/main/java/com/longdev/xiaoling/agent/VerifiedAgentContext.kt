package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject

enum class AgentVerificationStatus {
    VERIFIED,
    FAILED,
    READABLE_ONLY,
}

data class VerifiedToolExecution(
    val toolName: String,
    val arguments: Map<String, String>,
    val success: Boolean,
    val verificationStatus: AgentVerificationStatus,
    val rawResult: String,
    val memoryIdsUsed: List<String> = emptyList(),
)

data class VerifiedAgentContext(
    val runId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val success: Boolean,
    val verificationStatus: AgentVerificationStatus,
    val rawResult: String,
    val memoryIdsUsed: List<String> = emptyList(),
    val toolExecutions: List<VerifiedToolExecution> = emptyList(),
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
            .put("memoryIdsUsed", context.memoryIdsUsed.toStringJsonArray())
            .put("toolExecutions", context.toolExecutions.toJsonArray())
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
                memoryIdsUsed = json.readStringList("memoryIdsUsed"),
                toolExecutions = json.optJSONArray("toolExecutions")?.let { executions ->
                    buildList {
                        for (index in 0 until executions.length()) {
                            add(executions.getJSONObject(index).toVerifiedToolExecution())
                        }
                    }
                }.orEmpty(),
            )
        }.getOrNull()
    }

    private fun List<VerifiedToolExecution>.toJsonArray(): JSONArray {
        return JSONArray().apply {
            this@toJsonArray.forEach { execution ->
                put(
                    JSONObject()
                        .put("toolName", execution.toolName)
                        .put("arguments", JSONObject(execution.arguments))
                        .put("success", execution.success)
                        .put("verificationStatus", execution.verificationStatus.name)
                        .put("rawResult", execution.rawResult)
                        .put("memoryIdsUsed", execution.memoryIdsUsed.toStringJsonArray()),
                )
            }
        }
    }

    private fun JSONObject.toVerifiedToolExecution(): VerifiedToolExecution {
        val argumentsJson = optJSONObject("arguments") ?: JSONObject()
        val arguments = buildMap {
            argumentsJson.keys().forEach { key -> put(key, argumentsJson.optString(key)) }
        }
        return VerifiedToolExecution(
            toolName = getString("toolName"),
            arguments = arguments,
            success = getBoolean("success"),
            verificationStatus = AgentVerificationStatus.valueOf(getString("verificationStatus")),
            rawResult = getString("rawResult"),
            memoryIdsUsed = readStringList("memoryIdsUsed"),
        )
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        return JSONArray().apply { this@toStringJsonArray.forEach(::put) }
    }

    private fun JSONObject.readStringList(name: String): List<String> {
        val array = optJSONArray(name) ?: optString(name)
            .takeIf { it.startsWith("[") }
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }
}
