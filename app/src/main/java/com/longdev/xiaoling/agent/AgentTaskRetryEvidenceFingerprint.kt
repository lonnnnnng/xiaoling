package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import java.security.MessageDigest

/**
 * long: 重试确认需要绑定用户看到的那一份工具证据；只对工具账本和非恢复事件做稳定摘要，避免启动收敛追加自身事件后误报漂移。
 */
internal object AgentTaskRetryEvidenceFingerprint {
    fun calculate(detail: AgentRunDetailRecord): String {
        val canonical = buildString {
            append("ledger\n")
            detail.toolLedger.calls.sortedBy { it.id }.forEach { call ->
                field("call.id", call.id)
                field("call.runId", call.runId)
                field("call.toolName", call.toolName)
                field("call.risk", call.risk.name)
                fields("call.arguments", call.arguments)
                field("call.proposedEventId", call.proposedEventId)
                field("call.validatedEventId", call.validatedEventId)
                field("call.createdAt", call.createdAt)
                field("call.validatedAt", call.validatedAt)
            }
            detail.toolLedger.results.sortedBy { it.toolCallId }.forEach { result ->
                field("result.toolCallId", result.toolCallId)
                field("result.runId", result.runId)
                field("result.eventId", result.eventId)
                field("result.toolName", result.toolName)
                field("result.content", result.content)
                field("result.success", result.success)
                field("result.errorMessage", result.errorMessage)
                field("result.durationMs", result.durationMs)
                field("result.executorVerified", result.executorVerified)
                field("result.verificationStatus", result.verificationStatus?.name)
                field("result.verifiedEventId", result.verifiedEventId)
                fields("result.memoryIdsUsed", result.memoryIdsUsed.mapIndexed { index, value -> index.toString() to value }.toMap())
                field("result.knowledgeReferences", KnowledgeReferenceCodec.encodeToString(result.knowledgeReferences))
                field("result.replaySafety", result.replaySafety.name)
                val receipt = result.executionReceipt
                field("result.receipt.toolCallId", receipt?.toolCallId)
                field("result.receipt.operationId", receipt?.operationId)
                field("result.receipt.idempotencyKey", receipt?.idempotencyKey)
                field("result.receipt.status", receipt?.status?.name)
                field("result.createdAt", result.createdAt)
                field("result.verifiedAt", result.verifiedAt)
            }
            append("events\n")
            detail.snapshot.events
                .asSequence()
                // long: Run/Step 状态事件没有结构化业务载荷，启动收敛会按设计追加这些控制面记录；指纹只绑定 Tool Ledger 与 typed 业务事件，避免清理动作自己制造漂移。
                .filter { it.type != "run.recovered" && it.metadata != null }
                .forEach { event ->
                    field("event.id", event.id)
                    field("event.runId", event.runId)
                    field("event.type", event.type)
                    field("event.message", event.message)
                    field("event.createdAt", event.createdAt)
                    field("event.metadata", event.metadata?.let(RunEventMetadataCodec::encode))
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.field(name: String, value: Any?) {
        val text = value?.toString()
        append(name.length).append(':').append(name)
        if (text == null) append("#") else append(text.length).append(':').append(text)
        append('\n')
    }

    private fun StringBuilder.fields(name: String, values: Map<String, String>) {
        values.toSortedMap().forEach { (key, value) -> field("$name.$key", value) }
    }
}
