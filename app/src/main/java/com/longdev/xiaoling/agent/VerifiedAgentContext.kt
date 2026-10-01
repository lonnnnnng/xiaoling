package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowCandidate
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import org.json.JSONArray
import org.json.JSONObject

enum class AgentVerificationStatus {
    VERIFIED,
    FAILED,
    READABLE_ONLY,
}

/**
 * long: 可信上下文只保留可验证的执行身份和结果状态，不把幂等键或原始凭据带入消息、提示词或跨进程投影。
 */
data class TrustedToolExecutionReceipt(
    val toolCallId: String,
    val operationId: String,
    val status: ToolExecutionReceiptStatus,
) {
    init {
        require(toolCallId.isNotBlank()) { "可信执行回执的工具调用 ID 不能为空" }
        require(operationId.isNotBlank()) { "可信执行回执的操作 ID 不能为空" }
    }
}

/**
 * long: typed 验证保留原因码和可用的 ToolCall 绑定，供恢复、审计和跨入口投影复核；缺失绑定不自动补造。
 */
data class TrustedToolVerificationEvidence(
    val status: ToolVerificationStatus,
    val toolCallId: String?,
    val reasonCode: String?,
) {
    init {
        require(toolCallId == null || toolCallId.isNotBlank()) { "可信验证证据的工具调用 ID 不能为空白" }
        require(reasonCode == null || reasonCode.isNotBlank()) { "可信验证证据的原因码不能为空白" }
    }
}

data class VerifiedToolExecution(
    val toolName: String,
    val arguments: Map<String, String>,
    val success: Boolean,
    val verificationStatus: AgentVerificationStatus,
    val rawResult: String,
    val memoryIdsUsed: List<String> = emptyList(),
    val knowledgeReferences: List<KnowledgeReference> = emptyList(),
    val executionReceipt: TrustedToolExecutionReceipt? = null,
    val verificationEvidence: TrustedToolVerificationEvidence? = null,
    val readableEvidence: ToolReadableEvidence? = null,
)

data class VerifiedAgentContext(
    val runId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val success: Boolean,
    val verificationStatus: AgentVerificationStatus,
    val rawResult: String,
    val memoryIdsUsed: List<String> = emptyList(),
    val knowledgeReferences: List<KnowledgeReference> = emptyList(),
    val toolExecutions: List<VerifiedToolExecution> = emptyList(),
    val executionReceipt: TrustedToolExecutionReceipt? = null,
    val verificationEvidence: TrustedToolVerificationEvidence? = null,
    val readableEvidence: ToolReadableEvidence? = null,
)

/**
 * long: 消息流只读取最近一条成功且带稳定引用的 knowledge.search；失败、无引用或其他工具的文本不能冒充 Judge 候选。
 */
internal fun VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(
    question: String,
): KnowledgeAnswerabilityShadowCandidate? {
    if (runId.isBlank() || question.isBlank()) return null
    val executions = toolExecutionsOrLegacyProjection()
    val execution = executions.asReversed().firstOrNull { candidate ->
        candidate.toolName == "knowledge.search" &&
            candidate.success &&
            candidate.verificationStatus != AgentVerificationStatus.FAILED &&
            candidate.rawResult.isNotBlank() &&
            candidate.knowledgeReferences.isNotEmpty()
    } ?: return null
    return KnowledgeAnswerabilityShadowCandidate(
        sourceRunId = runId,
        question = question,
        candidateText = execution.rawResult,
        references = execution.knowledgeReferences.toList(),
    )
}

/**
 * long:
 * 把知识库生命周期变化应用到“送入模型”的可信投影；Room 中的历史审计快照仍由 Repository 原样保留。
 */
internal fun VerifiedAgentContext.retainCurrentKnowledgeReferences(
    currentReferences: Set<KnowledgeReference>,
): VerifiedAgentContext? {
    val executions = toolExecutionsOrLegacyProjection()
    val retainedExecutions = executions.mapNotNull { execution ->
        val knowledgeEvidenceMissing = execution.toolName == "knowledge.search" &&
            execution.knowledgeReferences.isEmpty()
        val knowledgeEvidenceStale = execution.toolName == "knowledge.search" &&
            execution.knowledgeReferences.any { it !in currentReferences }
        if (knowledgeEvidenceMissing || knowledgeEvidenceStale) {
            // long: 知识检索结果正文与引用是同一可信单元，任一 chunk 失效时整步退出模型上下文，避免只删 ID 仍泄露旧正文。
            return@mapNotNull null
        }
        execution.copy(knowledgeReferences = execution.knowledgeReferences.filter { it in currentReferences })
    }
    if (retainedExecutions.isEmpty()) return null
    val finalExecution = retainedExecutions.last()
    return copy(
        toolName = finalExecution.toolName,
        arguments = finalExecution.arguments,
        success = retainedExecutions.all { it.success },
        verificationStatus = finalExecution.verificationStatus,
        rawResult = finalExecution.rawResult,
        memoryIdsUsed = retainedExecutions.flatMap { it.memoryIdsUsed }.distinct(),
        knowledgeReferences = retainedExecutions.flatMap { it.knowledgeReferences }.distinct(),
        toolExecutions = if (toolExecutions.isNotEmpty()) retainedExecutions else emptyList(),
        executionReceipt = finalExecution.executionReceipt,
        verificationEvidence = finalExecution.verificationEvidence,
        readableEvidence = finalExecution.readableEvidence,
    )
}

/**
 * long: 旧消息只有顶层单工具快照；统一投影后，知识引用保留与 answerability 候选选择才能共享同一兼容语义。
 */
private fun VerifiedAgentContext.toolExecutionsOrLegacyProjection(): List<VerifiedToolExecution> =
    toolExecutions.ifEmpty {
        listOf(
            VerifiedToolExecution(
                toolName = toolName,
                arguments = arguments,
                success = success,
                verificationStatus = verificationStatus,
                rawResult = rawResult,
                memoryIdsUsed = memoryIdsUsed,
                knowledgeReferences = knowledgeReferences,
                executionReceipt = executionReceipt,
                verificationEvidence = verificationEvidence,
                readableEvidence = readableEvidence,
            ),
        )
    }

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
            .put("knowledgeReferences", KnowledgeReferenceCodec.encode(context.knowledgeReferences))
            .put("toolExecutions", context.toolExecutions.toJsonArray())
            .put("executionReceipt", context.executionReceipt?.toJson())
            .put("verificationEvidence", context.verificationEvidence?.toJson())
            .put("readableEvidence", context.readableEvidence?.let(ToolReadableEvidenceCodec::encode))
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
                knowledgeReferences = KnowledgeReferenceCodec.decode(json.optJSONArray("knowledgeReferences")),
                toolExecutions = json.optJSONArray("toolExecutions")?.let { executions ->
                    buildList {
                        for (index in 0 until executions.length()) {
                            add(executions.getJSONObject(index).toVerifiedToolExecution())
                        }
                    }
                }.orEmpty(),
                executionReceipt = json.optJSONObject("executionReceipt")?.toTrustedToolExecutionReceipt(),
                verificationEvidence = json.optJSONObject("verificationEvidence")?.toTrustedToolVerificationEvidence(),
                readableEvidence = ToolReadableEvidenceCodec.decode(json.optJSONObject("readableEvidence")),
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
                        .put("memoryIdsUsed", execution.memoryIdsUsed.toStringJsonArray())
                        .put("knowledgeReferences", KnowledgeReferenceCodec.encode(execution.knowledgeReferences))
                        .put("executionReceipt", execution.executionReceipt?.toJson())
                        .put("verificationEvidence", execution.verificationEvidence?.toJson())
                        .put("readableEvidence", execution.readableEvidence?.let(ToolReadableEvidenceCodec::encode)),
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
            knowledgeReferences = KnowledgeReferenceCodec.decode(optJSONArray("knowledgeReferences")),
            executionReceipt = optJSONObject("executionReceipt")?.toTrustedToolExecutionReceipt(),
            verificationEvidence = optJSONObject("verificationEvidence")?.toTrustedToolVerificationEvidence(),
            readableEvidence = ToolReadableEvidenceCodec.decode(optJSONObject("readableEvidence")),
        )
    }

    private fun TrustedToolExecutionReceipt.toJson(): JSONObject = JSONObject()
        .put("toolCallId", toolCallId)
        .put("operationId", operationId)
        .put("status", status.name)

    private fun TrustedToolVerificationEvidence.toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("toolCallId", toolCallId)
        .put("reasonCode", reasonCode)

    private fun JSONObject.toTrustedToolExecutionReceipt(): TrustedToolExecutionReceipt? {
        val toolCallId = optString("toolCallId").takeIf { it.isNotBlank() } ?: return null
        val operationId = optString("operationId").takeIf { it.isNotBlank() } ?: return null
        val status = optString("status").takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { ToolExecutionReceiptStatus.valueOf(raw) }.getOrNull() }
            ?: return null
        return TrustedToolExecutionReceipt(toolCallId = toolCallId, operationId = operationId, status = status)
    }

    private fun JSONObject.toTrustedToolVerificationEvidence(): TrustedToolVerificationEvidence? {
        val status = optString("status").takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { ToolVerificationStatus.valueOf(raw) }.getOrNull() }
            ?: return null
        val toolCallId = optString("toolCallId").takeIf { it.isNotBlank() }
        val reasonCode = optString("reasonCode").takeIf { it.isNotBlank() }
        return TrustedToolVerificationEvidence(status = status, toolCallId = toolCallId, reasonCode = reasonCode)
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        return JSONArray().apply { this@toStringJsonArray.forEach(::put) }
    }

    private fun JSONObject.readStringList(name: String): List<String> {
        // long: 早期 Android org.json 会把 Kotlin List 按字符串写成 "[]"；读取时兼容该历史格式，避免升级后丢失旧消息中的记忆引用审计。
        val array = optJSONArray(name) ?: optString(name)
            .takeIf { it.startsWith("[") }
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }
}
