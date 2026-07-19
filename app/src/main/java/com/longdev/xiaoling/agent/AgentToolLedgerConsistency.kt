package com.longdev.xiaoling.agent

internal fun AgentToolCallRecord.matchesLedgerEvent(
    event: RunEventRecord?,
    expectedType: String,
): Boolean {
    val metadata = event?.metadata as? RunEventMetadata.ToolCall ?: return false
    val expectedCreatedAt = when (expectedType) {
        "tool.call.proposed" -> createdAt
        "tool.call.validated" -> validatedAt ?: return false
        else -> return false
    }
    return event.runId == runId &&
        event.type == expectedType &&
        event.createdAt == expectedCreatedAt &&
        metadata.id == id &&
        metadata.toolName == toolName &&
        metadata.risk == risk &&
        metadata.arguments == arguments
}

internal fun AgentToolResultRecord.matchesLedgerEvent(event: RunEventRecord?): Boolean {
    val metadata = event?.metadata as? RunEventMetadata.ToolResult ?: return false
    // long: errorMessage 是结果账本从成功标记和正文派生的审计字段；即使 typed event 没有独立错误列，也必须核对派生语义，避免损坏账本被恢复入口采信。
    val expectedErrorMessage = if (success) null else content
    return event.runId == runId &&
        event.type == "tool.result" &&
        event.createdAt == createdAt &&
        errorMessage == expectedErrorMessage &&
        metadata.toolCallId == toolCallId &&
        metadata.toolName == toolName &&
        metadata.content == content &&
        metadata.success == success &&
        metadata.durationMs == durationMs &&
        metadata.verified == executorVerified &&
        metadata.memoryIdsUsed == memoryIdsUsed &&
        metadata.replaySafety == replaySafety &&
        metadata.executionReceipt == executionReceipt
}

internal fun AgentToolResultRecord.matchesLedgerVerificationEvent(event: RunEventRecord?): Boolean {
    val status = verificationStatus ?: return false
    val eventId = verifiedEventId ?: return false
    val eventCreatedAt = verifiedAt ?: return false
    val metadata = event?.metadata as? RunEventMetadata.ToolVerification ?: return false
    return event.id == eventId &&
        event.runId == runId &&
        event.type == "tool.verify" &&
        event.createdAt == eventCreatedAt &&
        metadata.toolCallId == toolCallId &&
        metadata.toolName == toolName &&
        metadata.status == status
}

internal data class AgentToolLedgerExecutionRecord(
    val call: AgentToolCallRecord,
    val result: AgentToolResultRecord?,
)

internal sealed interface AgentToolLedgerConsistencyAssessment {
    data object Empty : AgentToolLedgerConsistencyAssessment

    data class Available(
        val executions: List<AgentToolLedgerExecutionRecord>,
    ) : AgentToolLedgerConsistencyAssessment

    data class Invalid(
        val reason: String,
    ) : AgentToolLedgerConsistencyAssessment
}

internal object AgentToolLedgerConsistencyPolicy {
    fun inspect(detail: AgentRunDetailRecord): AgentToolLedgerConsistencyAssessment {
        val ledger = detail.toolLedger
        if (ledger.calls.isEmpty() && ledger.results.isEmpty()) {
            return AgentToolLedgerConsistencyAssessment.Empty
        }

        val runId = detail.snapshot.run.id
        val events = detail.snapshot.events
        val eventsById = events.associateBy { it.id }
        if (eventsById.size != events.size) return invalid("RunEvent 存在重复事件 ID")
        val eventPositionsById = events.withIndex().associate { (index, event) -> event.id to index }
        val callIds = ledger.calls.map { it.id }
        val resultCallIds = ledger.results.map { it.toolCallId }
        if (callIds.distinct().size != callIds.size) return invalid("工具账本存在重复 ToolCall")
        if (resultCallIds.distinct().size != resultCallIds.size) return invalid("同一 ToolCall 存在多个工具结果账本")
        if (ledger.calls.any { it.runId != runId } || ledger.results.any { it.runId != runId }) {
            return invalid("工具账本包含其他 Run 的证据")
        }
        val callsById = ledger.calls.associateBy { it.id }
        val resultsByCallId = ledger.results.associateBy { it.toolCallId }
        val orphanResult = ledger.results.firstOrNull { it.toolCallId !in callsById }
        if (orphanResult != null) return invalid("工具结果账本没有对应调用：${orphanResult.toolCallId}")

        // long: 进程可能在最后一次调用的 proposed、validated、result 或 verified 后中断，因此只允许链尾不完整；每个调用仍必须从 proposed 锚点开始，防止遗漏无法归属的副作用证据。
        val orderedExecutions = mutableListOf<AgentToolLedgerExecutionRecord>()
        for (call in ledger.calls) {
            val proposedEventId = call.proposedEventId
                ?: return invalid("工具账本缺少 proposed 事件锚点：${call.id}")
            if (!call.matchesLedgerEvent(eventsById[proposedEventId], "tool.call.proposed")) {
                return invalid("工具账本 proposed 锚点与事件不一致：${call.id}")
            }
            val result = resultsByCallId[call.id]
            val validatedEventId = call.validatedEventId
            if (validatedEventId == null) {
                if (result != null) return invalid("工具结果账本缺少 validated 事件锚点：${call.id}")
            } else {
                if (!call.matchesLedgerEvent(eventsById[validatedEventId], "tool.call.validated")) {
                    return invalid("工具账本 validated 锚点与事件不一致：${call.id}")
                }
                if (eventPositionsById.getValue(proposedEventId) >= eventPositionsById.getValue(validatedEventId)) {
                    return invalid("工具账本 proposed/validated 事件顺序不一致：${call.id}")
                }
            }

            if (result != null) {
                if (result.toolName != call.toolName) {
                    return invalid("工具调用与结果账本的工具身份不一致：${call.id}")
                }
                if (result.executionReceipt?.toolCallId?.let { it != result.toolCallId } == true) {
                    return invalid("工具结果账本的执行回执与 ToolCall 不一致：${call.id}")
                }
                if (!result.matchesLedgerEvent(eventsById[result.eventId])) {
                    return invalid("工具结果账本与事件不一致：${result.toolCallId}")
                }
                if (eventPositionsById.getValue(checkNotNull(validatedEventId)) >= eventPositionsById.getValue(result.eventId)) {
                    return invalid("工具调用与结果事件顺序不一致：${result.toolCallId}")
                }
                val verificationStatus = result.verificationStatus
                if (verificationStatus == null) {
                    if (result.verifiedEventId != null || result.verifiedAt != null) {
                        return invalid("工具结果账本的验证状态与锚点不一致：${result.toolCallId}")
                    }
                } else {
                    val verifiedEventId = result.verifiedEventId
                        ?: return invalid("工具结果账本缺少验证事件锚点：${result.toolCallId}")
                    if (!result.matchesLedgerVerificationEvent(eventsById[verifiedEventId])) {
                        return invalid("工具结果账本的验证锚点与事件不一致：${result.toolCallId}")
                    }
                    if (eventPositionsById.getValue(result.eventId) >= eventPositionsById.getValue(verifiedEventId)) {
                        return invalid("工具结果与验证事件顺序不一致：${result.toolCallId}")
                    }
                }
            }
            orderedExecutions += AgentToolLedgerExecutionRecord(call = call, result = result)
        }
        orderedExecutions.sortBy { execution ->
            eventPositionsById.getValue(checkNotNull(execution.call.proposedEventId))
        }
        // long: 前序调用必须完成验证后才能进入下一次调用，否则无法证明工具严格串行，也不能安全判断失败 Run 已经产生过哪些副作用。
        for (index in 0 until orderedExecutions.lastIndex) {
            val current = orderedExecutions[index]
            val next = orderedExecutions[index + 1]
            val currentResult = current.result
                ?: return invalid("前序工具结果缺失，不能建立顺序执行链：${current.call.id}")
            val terminalEventId = currentResult.verifiedEventId
                ?: return invalid("前序工具结果缺少完成验证：${current.call.id}")
            val nextProposedEventId = checkNotNull(next.call.proposedEventId)
            if (eventPositionsById.getValue(terminalEventId) >= eventPositionsById.getValue(nextProposedEventId)) {
                return invalid("工具账本不符合顺序执行事件链：${current.call.id} -> ${next.call.id}")
            }
        }

        for (event in events) {
            when (val metadata = event.metadata) {
                is RunEventMetadata.ToolCall -> {
                    if (event.type != "tool.call.proposed" && event.type != "tool.call.validated") continue
                    val call = callsById[metadata.id]
                        ?: return invalid("工具调用事件没有对应账本：${metadata.id}")
                    val anchoredEventId = if (event.type == "tool.call.proposed") {
                        call.proposedEventId
                    } else {
                        call.validatedEventId
                    }
                    if (anchoredEventId != event.id || !call.matchesLedgerEvent(event, event.type)) {
                        return invalid("工具调用事件没有被账本唯一锚定：${metadata.id}")
                    }
                }

                is RunEventMetadata.ToolResult -> {
                    if (event.type != "tool.result") continue
                    val toolCallId = metadata.toolCallId
                        ?: return invalid("工具结果事件缺少 ToolCall ID")
                    val result = resultsByCallId[toolCallId]
                        ?: return invalid("工具结果事件没有对应账本：$toolCallId")
                    if (result.eventId != event.id || !result.matchesLedgerEvent(event)) {
                        return invalid("工具结果事件没有被账本唯一锚定：$toolCallId")
                    }
                }

                is RunEventMetadata.ToolVerification -> {
                    if (event.type != "tool.verify") continue
                    val toolCallId = metadata.toolCallId
                        ?: return invalid("工具验证事件缺少 ToolCall ID")
                    val result = resultsByCallId[toolCallId]
                        ?: return invalid("工具验证事件没有对应结果账本：$toolCallId")
                    if (result.verifiedEventId != event.id || !result.matchesLedgerVerificationEvent(event)) {
                        return invalid("工具验证事件没有被结果账本唯一锚定：$toolCallId")
                    }
                }

                else -> Unit
            }
        }
        return AgentToolLedgerConsistencyAssessment.Available(orderedExecutions)
    }

    private fun invalid(reason: String) = AgentToolLedgerConsistencyAssessment.Invalid(reason)
}
