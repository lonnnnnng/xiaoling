package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import org.json.JSONArray
import org.json.JSONObject

internal object RunEventMetadataCodec {
    fun encode(metadata: RunEventMetadata): String {
        return when (metadata) {
            is RunEventMetadata.AgentProfileSelection -> JSONObject()
                .put("id", metadata.profile.id)
                .put("name", metadata.profile.name)
                .put("avatar", metadata.profile.avatar)
                .put("providerId", metadata.profile.providerId)
                .put("model", metadata.profile.model)
                .put("apiMode", metadata.profile.apiMode.name)
                .put("systemPrompt", metadata.profile.systemPrompt)
                .put("contextPolicy", metadata.profile.contextPolicy.name)
                .put("allowedToolNames", metadata.profile.allowedToolNames.toStringJsonArray())
                .put("allowedSkillIds", metadata.profile.allowedSkillIds.toStringJsonArray())
                .put("memoryEnabled", metadata.profile.memoryEnabled)
            is RunEventMetadata.LlmRequest -> JSONObject()
                .put("phase", metadata.phase.name)
                .put("model", metadata.model)
                .put("latencyMs", metadata.latencyMs)
                .put("firstByteLatencyMs", metadata.firstByteLatencyMs)
                .put("promptBytes", metadata.promptBytes)
                .put("inputTokens", metadata.inputTokens)
                .put("outputTokens", metadata.outputTokens)
                .put("totalTokens", metadata.totalTokens)
            is RunEventMetadata.LlmFailure -> JSONObject()
                .put("phase", metadata.phase.name)
                .put("kind", metadata.kind.name)
                .put("reason", metadata.reason)
            is RunEventMetadata.ExecutionBudget -> JSONObject()
                .put("totalTimeoutMs", metadata.totalTimeoutMs)
                .put("consumedMs", metadata.consumedMs)
            is RunEventMetadata.ToolCall -> JSONObject()
                .put("id", metadata.id)
                .put("toolName", metadata.toolName)
                .put("risk", metadata.risk.name)
                .putArguments(metadata.arguments)
            is RunEventMetadata.ToolResult -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("content", metadata.content)
                .put("durationMs", metadata.durationMs)
                .put("success", metadata.success)
                .put("verified", metadata.verified)
                .put("memoryIdsUsed", metadata.memoryIdsUsed.toStringJsonArray())
                .put("knowledgeReferences", KnowledgeReferenceCodec.encode(metadata.knowledgeReferences))
                .put("toolCallId", metadata.toolCallId)
                .put("replaySafety", metadata.replaySafety.name)
                .put("executionReceipt", metadata.executionReceipt?.toJson())
            is RunEventMetadata.ApprovalRequest -> JSONObject()
                .put("id", metadata.id)
                .put("toolName", metadata.toolName)
                .put("risk", metadata.risk.name)
                .putArguments(metadata.arguments)
                .put("status", metadata.status.name)
                .put("expiresAt", metadata.expiresAt)
                .put("reason", metadata.reason)
            is RunEventMetadata.ApprovalDecision -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("approved", metadata.approved)
                .put("reason", metadata.reason)
            is RunEventMetadata.ApprovalSkipped -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("reason", metadata.reason)
            is RunEventMetadata.ToolVerification -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("status", metadata.status.name)
                .put("toolCallId", metadata.toolCallId)
            is RunEventMetadata.Reason -> JSONObject()
                .put("reason", metadata.reason)
            is RunEventMetadata.Recovery -> JSONObject()
                .put("fromStatus", metadata.fromStatus.name)
                .put("toStatus", metadata.toStatus.name)
                .put("reason", metadata.reason)
                .apply {
                    metadata.retryEvidenceCode?.let { put("retryEvidenceCode", it.name) }
                    metadata.retryEvidenceFingerprint?.let { put("retryEvidenceFingerprint", it) }
                    metadata.resumeKind?.let { put("resumeKind", it.name) }
                    metadata.restartDisposition?.let { disposition ->
                        put("restartDispositionCode", disposition.code.name)
                        put("policyReason", disposition.reason)
                        put("evidenceBoundary", disposition.evidenceBoundary)
                        put("suggestedAction", disposition.suggestedAction)
                    }
                }
            is RunEventMetadata.RecoveryFailure -> JSONObject()
                .put("toolName", metadata.toolName)
                .put("code", metadata.code)
                .put("reason", metadata.reason)
                .put("suggestedAction", metadata.suggestedAction)
        }.toString()
    }

    fun decode(type: String, raw: String?): RunEventMetadata? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            when (type) {
                AgentEventTypes.PROFILE_SELECTED -> RunEventMetadata.AgentProfileSelection(
                    profile = AgentProfileSnapshot(
                        id = json.requiredString("id"),
                        name = json.requiredString("name"),
                        avatar = json.stringOrNull("avatar").orEmpty(),
                        providerId = json.requiredString("providerId"),
                        model = json.requiredString("model"),
                        apiMode = com.longdev.xiaoling.model.ApiMode.valueOf(json.requiredString("apiMode")),
                        systemPrompt = json.stringOrNull("systemPrompt").orEmpty(),
                        contextPolicy = AgentContextPolicy.valueOf(json.requiredString("contextPolicy")),
                        allowedToolNames = json.stringListOrEmpty("allowedToolNames"),
                        allowedSkillIds = json.stringListOrEmpty("allowedSkillIds"),
                        memoryEnabled = json.getBoolean("memoryEnabled"),
                    ),
                )
                AgentEventTypes.LLM_REQUEST_COMPLETED -> RunEventMetadata.LlmRequest(
                    phase = AgentLlmPhase.valueOf(json.requiredString("phase")),
                    model = json.requiredString("model"),
                    latencyMs = json.getLong("latencyMs"),
                    firstByteLatencyMs = json.longOrNull("firstByteLatencyMs"),
                    promptBytes = json.getInt("promptBytes"),
                    inputTokens = json.longOrNull("inputTokens"),
                    outputTokens = json.longOrNull("outputTokens"),
                    totalTokens = json.longOrNull("totalTokens"),
                )
                AgentEventTypes.LLM_REQUEST_FAILED -> RunEventMetadata.LlmFailure(
                    phase = AgentLlmPhase.valueOf(json.requiredString("phase")),
                    kind = json.stringOrNull("kind")?.let { value ->
                        runCatching { AgentLlmFailureKind.valueOf(value) }
                            .getOrElse { AgentLlmFailureKind.UNKNOWN }
                    } ?: AgentLlmFailureKind.UNKNOWN,
                    reason = json.requiredString("reason"),
                )
                AgentEventTypes.EXECUTION_BUDGET_UPDATED -> RunEventMetadata.ExecutionBudget(
                    totalTimeoutMs = json.getLong("totalTimeoutMs"),
                    consumedMs = json.getLong("consumedMs"),
                )
                "tool.call.proposed",
                "tool.call.validated" -> RunEventMetadata.ToolCall(
                    id = json.requiredString("id"),
                    toolName = json.requiredToolName(),
                    risk = ToolRisk.valueOf(json.requiredString("risk")),
                    arguments = json.arguments(),
                )
                "tool.result" -> RunEventMetadata.ToolResult(
                    toolName = json.requiredToolName(),
                    content = json.requiredString("content"),
                    durationMs = json.getLong("durationMs"),
                    success = json.getBoolean("success"),
                    verified = json.booleanOrNull("verified"),
                    memoryIdsUsed = json.stringListOrEmpty("memoryIdsUsed"),
                    knowledgeReferences = KnowledgeReferenceCodec.decode(json.optJSONArray("knowledgeReferences")),
                    toolCallId = json.stringOrNull("toolCallId"),
                    // long: 旧工具结果没有重放声明快照时必须按不可重放处理，不能使用升级后的当前定义反推历史保证。
                    replaySafety = json.stringOrNull("replaySafety")
                        ?.let(ToolReplaySafety::valueOf)
                        ?: ToolReplaySafety.RESTART_REQUIRED,
                    executionReceipt = json.executionReceiptOrNull(),
                )
                "approval.requested",
                "approval.request_decided" -> RunEventMetadata.ApprovalRequest(
                    id = json.requiredString("id"),
                    toolName = json.requiredToolName(),
                    risk = ToolRisk.valueOf(json.requiredString("risk")),
                    arguments = json.arguments(),
                    status = ApprovalRequestStatus.valueOf(json.requiredString("status")),
                    expiresAt = json.getLong("expiresAt"),
                    // long: v6 审批事件使用 decisionReason，新格式统一为 reason；别名兼容保证升级后用户决定仍可审计。
                    reason = json.stringOrNull("reason") ?: json.stringOrNull("decisionReason"),
                )
                "approval.granted",
                "approval.denied" -> RunEventMetadata.ApprovalDecision(
                    toolName = json.requiredToolName(),
                    approved = json.getBoolean("approved"),
                    reason = json.requiredString("reason"),
                )
                "approval.skipped" -> RunEventMetadata.ApprovalSkipped(
                    toolName = json.requiredToolName(),
                    reason = json.requiredString("reason"),
                )
                "tool.verify" -> RunEventMetadata.ToolVerification(
                    toolName = json.requiredToolName(),
                    status = ToolVerificationStatus.valueOf(json.requiredString("status").uppercase()),
                    toolCallId = json.stringOrNull("toolCallId"),
                )
                "llm.summarize.fallback",
                AgentEventTypes.RECOVERY_SUMMARY,
                "skill.selected",
                "run.failed",
                "run.timeout",
                "run.cancelled",
                "run.budget_exhausted" -> RunEventMetadata.Reason(json.requiredString("reason"))
                "run.recovered" -> RunEventMetadata.Recovery(
                    fromStatus = AgentRunStatus.valueOf(json.requiredString("fromStatus")),
                    toStatus = AgentRunStatus.valueOf(json.requiredString("toStatus")),
                    reason = json.requiredString("reason"),
                    retryEvidenceCode = json.stringOrNull("retryEvidenceCode")?.let { value ->
                        // long: 字段存在但枚举来自未来版本时必须保守归类，不能把未知证据伪装成旧事件缺字段而绕过确认。
                        runCatching { AgentTaskRetryEvidenceCode.valueOf(value) }
                            .getOrElse { AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE }
                    },
                    retryEvidenceFingerprint = json.stringOrNull("retryEvidenceFingerprint"),
                    resumeKind = json.stringOrNull("resumeKind")?.let { value ->
                        // long: 新版本的恢复类型不能在旧客户端被当作可原地续跑；未知类型固定降级为需要新 Run。
                        runCatching { AgentRunResumeKind.valueOf(value) }
                            .getOrElse { AgentRunResumeKind.RESTART_REQUIRED }
                    },
                    restartDisposition = json.stringOrNull("restartDispositionCode")?.let { value ->
                        val policyReason = json.stringOrNull("policyReason") ?: return@let null
                        val evidenceBoundary = json.stringOrNull("evidenceBoundary") ?: return@let null
                        val suggestedAction = json.stringOrNull("suggestedAction") ?: return@let null
                        // long: 未识别的处置码代表本地无法解释其证据边界，按恢复证据不完整保守展示；四个字段必须整体出现，避免展示半份历史结论。
                        AgentRunRestartDisposition(
                            code = runCatching { AgentRunRestartDispositionCode.valueOf(value) }
                                .getOrElse { AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID },
                            reason = policyReason,
                            evidenceBoundary = evidenceBoundary,
                            suggestedAction = suggestedAction,
                        )
                    },
                )
                AgentEventTypes.RECOVERY_FAILED -> RunEventMetadata.RecoveryFailure(
                    toolName = json.requiredToolName(),
                    code = json.requiredString("code"),
                    reason = json.requiredString("reason"),
                    suggestedAction = json.requiredString("suggestedAction"),
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun JSONObject.putArguments(arguments: Map<String, String>): JSONObject {
        return put(
            "arguments",
            JSONObject().apply {
                // long: 审计参数按 key 排序落库，避免同一工具调用因 Map 遍历顺序不同产生无意义差异。
                arguments.toSortedMap().forEach { (key, value) -> put(key, value) }
            },
        )
    }

    private fun JSONObject.requiredToolName(): String =
        stringOrNull("toolName")
            ?: stringOrNull("name")
            ?: stringOrNull("tool")
            ?: error("RunEvent metadata 缺少工具名称")

    private fun JSONObject.requiredString(key: String): String =
        stringOrNull(key) ?: error("RunEvent metadata 缺少 $key")

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.stringListOrEmpty(key: String): List<String> {
        // long: Android org.json 不会稳定地把 Kotlin List 包装成 JSONArray；显式数组是新格式，同时兼容早期已落库的字符串化 JSON 数组。
        val values = optJSONArray(key) ?: optString(key)
            .takeIf { it.startsWith("[") }
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        return JSONArray().apply { this@toStringJsonArray.forEach(::put) }
    }

    private fun ToolExecutionReceipt.toJson(): JSONObject = JSONObject()
        .put("toolCallId", toolCallId)
        .put("operationId", operationId)
        .put("idempotencyKey", idempotencyKey)
        .put("status", status.name)

    private fun JSONObject.executionReceiptOrNull(): ToolExecutionReceipt? {
        // long: 旧 Run 没有执行回执时保持 null，不能从成功文本或工具名反推已经提交的副作用证据。
        val receipt = optJSONObject("executionReceipt") ?: return null
        return ToolExecutionReceipt(
            toolCallId = receipt.requiredString("toolCallId"),
            operationId = receipt.requiredString("operationId"),
            idempotencyKey = receipt.stringOrNull("idempotencyKey"),
            status = ToolExecutionReceiptStatus.valueOf(receipt.requiredString("status")),
        )
    }

    private fun JSONObject.arguments(): Map<String, String> =
        optJSONObject("arguments")?.toStringMap().orEmpty()

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        keys().forEach { key -> put(key, optString(key)) }
    }
}
