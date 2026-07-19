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
        val ledger = detail.toolLedger
        if (ledger.calls.isEmpty() && ledger.results.isEmpty()) {
            return readEventFallback(detail)
        }

        // long: v20 Run 一旦存在独立账本，恢复判断只接受账本事实；RunEvent 仅用于核对原子双写锚点，任何漂移都必须 fail-closed，不能退回较宽松的旧事件推断。
        val runId = detail.snapshot.run.id
        val eventsById = detail.snapshot.events.associateBy { it.id }
        if (eventsById.size != detail.snapshot.events.size) {
            return invalid("RunEvent 存在重复事件 ID")
        }
        val eventPositionsById = detail.snapshot.events.withIndex().associate { (index, event) ->
            event.id to index
        }
        val callIds = ledger.calls.map { it.id }
        val resultCallIds = ledger.results.map { it.toolCallId }
        // long: 恢复只接受完整执行前缀；任何已落账调用都必须恰好有一个结果，否则无法证明最后一步就是唯一待验证的已提交副作用。
        if (callIds.distinct().size != callIds.size) {
            return invalid("工具账本存在重复 ToolCall")
        }
        if (resultCallIds.distinct().size != resultCallIds.size) {
            return invalid("同一 ToolCall 存在多个工具结果账本")
        }
        if (callIds.toSet() != resultCallIds.toSet()) {
            return invalid("工具账本中的每个调用必须恰好对应一个结果")
        }
        val callsById = ledger.calls.associateBy { it.id }
        val resultsByCallId = ledger.results.associateBy { it.toolCallId }
        if (ledger.calls.any { it.runId != runId } || ledger.results.any { it.runId != runId }) {
            return invalid("工具账本包含其他 Run 的证据")
        }

        ledger.calls.forEach { call ->
            val proposedEventId = call.proposedEventId
                ?: return invalid("工具账本缺少 proposed 事件锚点：${call.id}")
            val validatedEventId = call.validatedEventId
                ?: return invalid("工具账本缺少 validated 事件锚点：${call.id}")
            if (!call.matchesLedgerEvent(eventsById[proposedEventId], "tool.call.proposed")) {
                return invalid("工具账本 proposed 锚点与事件不一致：${call.id}")
            }
            if (!call.matchesLedgerEvent(eventsById[validatedEventId], "tool.call.validated")) {
                return invalid("工具账本 validated 锚点与事件不一致：${call.id}")
            }
            if (eventPositionsById.getValue(proposedEventId) >= eventPositionsById.getValue(validatedEventId)) {
                return invalid("工具账本 proposed/validated 事件顺序不一致：${call.id}")
            }
        }

        val orderedCalls = ledger.calls.sortedBy { call ->
            eventPositionsById.getValue(checkNotNull(call.proposedEventId))
        }
        val executions = orderedCalls.map { call ->
            val result = checkNotNull(resultsByCallId[call.id])
            if (result.toolName != call.toolName) {
                return invalid("工具调用与结果账本的工具身份不一致：${call.id}")
            }
            val event = eventsById[result.eventId]
            if (!result.matchesLedgerEvent(event)) {
                return invalid("工具结果账本与事件不一致：${result.toolCallId}")
            }
            if (eventPositionsById.getValue(checkNotNull(call.validatedEventId)) >= eventPositionsById.getValue(result.eventId)) {
                return invalid("工具调用与结果事件顺序不一致：${result.toolCallId}")
            }
            when (val verificationStatus = result.verificationStatus) {
                null -> {
                    if (result.verifiedEventId != null || result.verifiedAt != null) {
                        return invalid("工具结果账本的验证状态与锚点不一致：${result.toolCallId}")
                    }
                }

                else -> {
                    val verifiedEventId = result.verifiedEventId
                        ?: return invalid("工具结果账本缺少验证事件锚点：${result.toolCallId}")
                    if (result.verifiedAt == null) {
                        return invalid("工具结果账本缺少验证时间：${result.toolCallId}")
                    }
                    val verifiedEvent = eventsById[verifiedEventId]
                    if (!result.matchesLedgerVerificationEvent(verifiedEvent)) {
                        return invalid("工具结果账本的验证锚点与事件不一致：${result.toolCallId}")
                    }
                    if (eventPositionsById.getValue(result.eventId) >= eventPositionsById.getValue(verifiedEventId)) {
                        return invalid("工具结果与验证事件顺序不一致：${result.toolCallId}")
                    }
                }
            }
            AgentPersistedToolRecoveryEvidence(
                toolCall = ToolCall(
                    id = call.id,
                    name = call.toolName,
                    arguments = call.arguments,
                    risk = call.risk,
                ),
                result = result.toEventMetadata(),
                verificationStatus = result.verificationStatus,
            )
        }
        orderedCalls.zipWithNext().forEach { (current, next) ->
            val currentResult = checkNotNull(resultsByCallId[current.id])
            val terminalEventId = currentResult.verifiedEventId ?: currentResult.eventId
            val nextProposedEventId = checkNotNull(next.proposedEventId)
            if (eventPositionsById.getValue(terminalEventId) >= eventPositionsById.getValue(nextProposedEventId)) {
                return invalid("工具账本不符合顺序执行事件链：${current.id} -> ${next.id}")
            }
        }
        detail.snapshot.events.forEach { event ->
            when (val metadata = event.metadata) {
                is RunEventMetadata.ToolCall -> {
                    if (event.type != "tool.call.proposed" && event.type != "tool.call.validated") return@forEach
                    val call = callsById[metadata.id]
                        ?: return invalid("工具调用事件没有对应账本：${metadata.id}")
                    val anchoredEventId = if (event.type == "tool.call.proposed") {
                        call.proposedEventId
                    } else {
                        call.validatedEventId
                    }
                    if (anchoredEventId != event.id) {
                        return invalid("工具调用事件没有被账本锚定：${metadata.id}")
                    }
                }

                is RunEventMetadata.ToolResult -> {
                    if (event.type != "tool.result") return@forEach
                    val toolCallId = metadata.toolCallId
                        ?: return invalid("工具结果事件缺少 ToolCall ID")
                    val result = resultsByCallId[toolCallId]
                        ?: return invalid("工具结果事件没有对应账本：$toolCallId")
                    if (result.eventId != event.id) {
                        return invalid("工具结果事件没有被账本锚定：$toolCallId")
                    }
                }

                is RunEventMetadata.ToolVerification -> {
                    if (event.type != "tool.verify") return@forEach
                    val toolCallId = metadata.toolCallId
                        ?: return invalid("工具验证事件缺少 ToolCall ID")
                    val result = resultsByCallId[toolCallId]
                        ?: return invalid("工具验证事件没有对应结果账本：$toolCallId")
                    if (result.verifiedEventId != event.id) {
                        return invalid("工具验证事件没有被结果账本锚定：$toolCallId")
                    }
                }

                else -> Unit
            }
        }
        return AgentRunRecoveryEvidenceAssessment.Available(
            source = AgentRunRecoveryEvidenceSource.LEDGER,
            executions = executions,
        )
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
