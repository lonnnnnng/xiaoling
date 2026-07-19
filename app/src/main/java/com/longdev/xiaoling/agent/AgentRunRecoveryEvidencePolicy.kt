package com.longdev.xiaoling.agent

enum class AgentRunRecoveryEvidenceSource {
    LEDGER,
    EVENT_FALLBACK,
}

data class AgentPersistedToolRecoveryEvidence(
    val toolCall: ToolCall,
    val result: RunEventMetadata.ToolResult,
    val verificationStatus: ToolVerificationStatus?,
)

sealed interface AgentRunRecoveryEvidenceAssessment {
    data class Available(
        val source: AgentRunRecoveryEvidenceSource,
        val executions: List<AgentPersistedToolRecoveryEvidence>,
    ) : AgentRunRecoveryEvidenceAssessment

    data class Invalid(
        val reason: String,
    ) : AgentRunRecoveryEvidenceAssessment
}

object AgentRunRecoveryEvidencePolicy {
    fun read(detail: AgentRunDetailRecord): AgentRunRecoveryEvidenceAssessment {
        return when (val consistency = AgentToolLedgerConsistencyPolicy.inspect(detail)) {
            AgentToolLedgerConsistencyAssessment.Empty -> readEventFallback(detail)
            is AgentToolLedgerConsistencyAssessment.Invalid -> invalid(consistency.reason)
            is AgentToolLedgerConsistencyAssessment.Available -> {
                // long: 共享一致性策略允许最后一个调用停在执行前；恢复路径更严格，只有每个调用均已持久化结果时才能证明“最后一步仅待验证”。
                if (consistency.executions.any { it.result == null }) {
                    return invalid("工具账本中的每个调用必须恰好对应一个结果")
                }
                AgentRunRecoveryEvidenceAssessment.Available(
                    source = AgentRunRecoveryEvidenceSource.LEDGER,
                    executions = consistency.executions.map { execution ->
                        val result = checkNotNull(execution.result)
                        AgentPersistedToolRecoveryEvidence(
                            toolCall = ToolCall(
                                id = execution.call.id,
                                name = execution.call.toolName,
                                arguments = execution.call.arguments,
                                risk = execution.call.risk,
                            ),
                            result = result.toEventMetadata(),
                            verificationStatus = result.verificationStatus,
                        )
                    },
                )
            }
        }
    }

    private fun readEventFallback(detail: AgentRunDetailRecord): AgentRunRecoveryEvidenceAssessment {
        // long: v19 及更早 Run 没有独立账本，只能使用 typed RunEvent；回退仍要求结果携带 ToolCall ID 并唯一匹配历史调用，不能从文案或时间顺序补造身份。
        val callMetadataById = linkedMapOf<String, RunEventMetadata.ToolCall>()
        detail.snapshot.events.forEach { event ->
            val call = (event.metadata as? RunEventMetadata.ToolCall)
                ?.takeIf { event.type == "tool.call.proposed" || event.type == "tool.call.validated" }
                ?: return@forEach
            val existing = callMetadataById[call.id]
            if (existing != null && existing != call) {
                return invalid("旧 Run 的 ToolCall 身份或参数发生漂移：${call.id}")
            }
            callMetadataById[call.id] = call
        }

        val executions = detail.snapshot.events.mapNotNull { event ->
            (event.metadata as? RunEventMetadata.ToolResult)
                ?.takeIf { event.type == "tool.result" }
        }.map { result ->
            val toolCallId = result.toolCallId
                ?: return invalid("旧 Run 的工具结果缺少 ToolCall ID")
            val call = callMetadataById[toolCallId]
                ?.takeIf { it.toolName == result.toolName }
                ?: return invalid("旧 Run 的工具结果无法唯一匹配原始 ToolCall：$toolCallId")
            MutableRecoveryEvidence(
                toolCall = ToolCall(
                    id = call.id,
                    name = call.toolName,
                    arguments = call.arguments,
                    risk = call.risk,
                ),
                result = result,
            )
        }.toMutableList()
        if (executions.isEmpty()) {
            return invalid("旧 Run 没有 typed 工具结果证据")
        }
        if (executions.map { it.toolCall.id }.distinct().size != executions.size) {
            return invalid("旧 Run 的同一 ToolCall 存在多个结果")
        }

        detail.snapshot.events.forEach { event ->
            val verification = (event.metadata as? RunEventMetadata.ToolVerification)
                ?.takeIf { event.type == "tool.verify" }
                ?: return@forEach
            val matched = if (verification.toolCallId != null) {
                executions.singleOrNull { evidence ->
                    evidence.verificationStatus == null &&
                        evidence.toolCall.id == verification.toolCallId &&
                        evidence.toolCall.name == verification.toolName
                }
            } else {
                // long: v19 及更早验证事件没有 ToolCall ID；这里保持旧策略按结果顺序一一配对，同名多步也不能因升级后读取新模型而改变恢复结论。
                executions.firstOrNull { it.verificationStatus == null }
                    ?.takeIf { it.toolCall.name == verification.toolName }
            }
            matched ?: return invalid("旧 Run 的工具验证无法按原顺序匹配结果：${verification.toolName}")
            matched.verificationStatus = verification.status
        }

        return AgentRunRecoveryEvidenceAssessment.Available(
            source = AgentRunRecoveryEvidenceSource.EVENT_FALLBACK,
            executions = executions.map { evidence ->
                AgentPersistedToolRecoveryEvidence(
                    toolCall = evidence.toolCall,
                    result = evidence.result,
                    verificationStatus = evidence.verificationStatus,
                )
            },
        )
    }

    private fun invalid(reason: String) = AgentRunRecoveryEvidenceAssessment.Invalid(reason)

    private fun AgentToolResultRecord.toEventMetadata() = RunEventMetadata.ToolResult(
        toolName = toolName,
        content = content,
        durationMs = durationMs,
        success = success,
        verified = executorVerified,
        memoryIdsUsed = memoryIdsUsed,
        knowledgeReferences = knowledgeReferences,
        toolCallId = toolCallId,
        replaySafety = replaySafety,
        executionReceipt = executionReceipt,
    )

    private data class MutableRecoveryEvidence(
        val toolCall: ToolCall,
        val result: RunEventMetadata.ToolResult,
        var verificationStatus: ToolVerificationStatus? = null,
    )
}
