package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentToolCallRecord
import com.longdev.xiaoling.agent.AgentToolResultRecord
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolExecutionReceipt
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.matchesLedgerEvent
import com.longdev.xiaoling.agent.matchesLedgerVerificationEvent

internal enum class AgentToolDetailSource {
    LEDGER,
    EVENT_FALLBACK,
    NONE,
}

internal enum class AgentToolStageState {
    COMPLETE,
    FAILED,
    PENDING,
}

internal data class AgentToolCallPresentation(
    val id: String?,
    val toolName: String,
    val risk: ToolRisk?,
    val arguments: Map<String, String>,
    val proposed: AgentToolStageState,
    val validated: AgentToolStageState,
    val result: AgentToolStageState,
    val verified: AgentToolStageState,
    val resultContent: String?,
    val errorMessage: String?,
    val durationMs: Long?,
    val executorVerified: Boolean?,
    val memoryIdsUsed: List<String>,
    val replaySafety: ToolReplaySafety?,
    val executionReceipt: ToolExecutionReceipt?,
    val createdAt: Long,
)

internal data class AgentToolLedgerIssue(
    val code: String,
    val message: String,
)

internal data class AgentToolLedgerPresentation(
    val source: AgentToolDetailSource,
    val calls: List<AgentToolCallPresentation>,
    val issues: List<AgentToolLedgerIssue>,
)

internal fun presentAgentToolLedger(detail: AgentRunDetailRecord): AgentToolLedgerPresentation {
    val ledger = detail.toolLedger
    if (ledger.calls.isEmpty() && ledger.results.isEmpty()) {
        // long: v19 及更早 Run 没有独立账本，只有存在 typed 工具事件时才进入兼容投影；普通无工具 Run 必须保持空详情，不能被误标成历史回退。
        return presentEventFallback(detail.snapshot.events)
    }

    // long: v20 新 Run 以独立账本作为任务中心明细事实源；事件只参与一致性核对，不得在字段漂移时覆盖账本内容。
    val resultsByCallId = ledger.results.associateBy { it.toolCallId }
    val knownCallIds = ledger.calls.mapTo(mutableSetOf()) { it.id }
    return AgentToolLedgerPresentation(
        source = AgentToolDetailSource.LEDGER,
        calls = ledger.calls.map { call -> call.toPresentation(resultsByCallId[call.id]) } +
            ledger.results
                .filterNot { it.toolCallId in knownCallIds }
                // long: 正常事务不会产生孤立结果；损坏数据仍生成只读占位调用，用户可以看到原结果和缺失阶段，同时由一致性告警明确指出账本异常。
                .map { it.toOrphanPresentation() },
        issues = findLedgerIssues(detail),
    )
}

private fun AgentToolCallRecord.toPresentation(result: AgentToolResultRecord?): AgentToolCallPresentation {
    return AgentToolCallPresentation(
        id = id,
        toolName = toolName,
        risk = risk,
        arguments = arguments,
        proposed = proposedEventId.toStageState(),
        validated = validatedEventId.toStageState(),
        result = result.toResultStageState(),
        verified = result.toVerificationStageState(),
        resultContent = result?.content,
        errorMessage = result?.errorMessage,
        durationMs = result?.durationMs,
        executorVerified = result?.executorVerified,
        memoryIdsUsed = result?.memoryIdsUsed.orEmpty(),
        replaySafety = result?.replaySafety,
        executionReceipt = result?.executionReceipt,
        createdAt = createdAt,
    )
}

private fun AgentToolResultRecord.toOrphanPresentation(): AgentToolCallPresentation {
    return AgentToolCallPresentation(
        id = toolCallId,
        toolName = toolName,
        risk = null,
        arguments = emptyMap(),
        proposed = AgentToolStageState.PENDING,
        validated = AgentToolStageState.PENDING,
        result = toResultStageState(),
        verified = toVerificationStageState(),
        resultContent = content,
        errorMessage = errorMessage,
        durationMs = durationMs,
        executorVerified = executorVerified,
        memoryIdsUsed = memoryIdsUsed,
        replaySafety = replaySafety,
        executionReceipt = executionReceipt,
        createdAt = createdAt,
    )
}

private fun AgentToolResultRecord?.toResultStageState(): AgentToolStageState {
    return when {
        this == null -> AgentToolStageState.PENDING
        success -> AgentToolStageState.COMPLETE
        else -> AgentToolStageState.FAILED
    }
}

private fun AgentToolResultRecord?.toVerificationStageState(): AgentToolStageState {
    return when {
        this?.verificationStatus != null -> AgentToolStageState.COMPLETE
        this?.success == false -> AgentToolStageState.FAILED
        else -> AgentToolStageState.PENDING
    }
}

private fun findLedgerIssues(detail: AgentRunDetailRecord): List<AgentToolLedgerIssue> {
    // long: 任务中心只做审计告警，不尝试修补任一数据源；这样异常不会静默改变旧 Run，也不会把 UI 判定反向用于恢复策略。
    val eventsById = detail.snapshot.events.associateBy { it.id }
    val callsById = detail.toolLedger.calls.associateBy { it.id }
    val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
    val issues = mutableListOf<AgentToolLedgerIssue>()

    detail.toolLedger.calls.forEach { call ->
        listOf(
            call.proposedEventId to TOOL_PROPOSED_EVENT,
            call.validatedEventId to TOOL_VALIDATED_EVENT,
        ).forEach { (eventId, expectedType) ->
            if (eventId == null) return@forEach
            val event = eventsById[eventId]
            val metadata = event?.metadata as? RunEventMetadata.ToolCall
            when {
                event == null || event.type != expectedType || metadata == null -> issues += AgentToolLedgerIssue(
                    code = "LEDGER_EVENT_MISSING",
                    message = "工具 ${call.toolName} 的 ${expectedType.toStageLabel()} 账本锚点没有对应事件。",
                )

                !call.matchesLedgerEvent(event, expectedType) -> issues += AgentToolLedgerIssue(
                    code = "TOOL_CALL_MISMATCH",
                    message = "工具 ${call.toolName} 的 ${expectedType.toStageLabel()} 事件与账本身份、参数或时间不一致。",
                )
            }
        }
    }

    detail.toolLedger.results.forEach { result ->
        if (callsById[result.toolCallId] == null) {
            issues += AgentToolLedgerIssue(
                code = "ORPHAN_LEDGER_RESULT",
                message = "工具 ${result.toolName} 的结果没有对应调用账本。",
            )
        }
        val resultEvent = eventsById[result.eventId]
        val resultMetadata = resultEvent?.metadata as? RunEventMetadata.ToolResult
        when {
            resultEvent == null || resultEvent.type != TOOL_RESULT_EVENT || resultMetadata == null -> issues +=
                AgentToolLedgerIssue(
                    code = "LEDGER_EVENT_MISSING",
                    message = "工具 ${result.toolName} 的结果账本没有对应事件。",
                )

            !result.matchesLedgerEvent(resultEvent) -> issues += AgentToolLedgerIssue(
                code = "TOOL_RESULT_MISMATCH",
                message = "工具 ${result.toolName} 的结果事件与账本字段不一致。",
            )
        }
        result.verifiedEventId?.let { verifiedEventId ->
            val verifiedEvent = eventsById[verifiedEventId]
            val verification = verifiedEvent?.metadata as? RunEventMetadata.ToolVerification
            when {
                verifiedEvent == null || verifiedEvent.type != TOOL_VERIFIED_EVENT || verification == null -> issues +=
                    AgentToolLedgerIssue(
                        code = "LEDGER_EVENT_MISSING",
                        message = "工具 ${result.toolName} 的验证账本没有对应事件。",
                    )

                !result.matchesLedgerVerificationEvent(verifiedEvent) -> issues += AgentToolLedgerIssue(
                    code = "TOOL_VERIFICATION_MISMATCH",
                    message = "工具 ${result.toolName} 的验证事件与账本字段不一致。",
                )
            }
        }
    }

    detail.snapshot.events.forEach { event ->
        when (val metadata = event.metadata) {
            is RunEventMetadata.ToolCall -> {
                if (event.type != TOOL_PROPOSED_EVENT && event.type != TOOL_VALIDATED_EVENT) return@forEach
                val call = callsById[metadata.id]
                val anchoredEventId = if (event.type == TOOL_PROPOSED_EVENT) {
                    call?.proposedEventId
                } else {
                    call?.validatedEventId
                }
                when {
                    call == null -> issues += AgentToolLedgerIssue(
                        code = "EVENT_CALL_MISSING_IN_LEDGER",
                        message = "工具 ${metadata.toolName} 的 ${event.type.toStageLabel()} 事件没有调用账本。",
                    )

                    anchoredEventId != event.id -> issues += AgentToolLedgerIssue(
                        code = "EVENT_CALL_ANCHOR_MISMATCH",
                        message = "工具 ${metadata.toolName} 的 ${event.type.toStageLabel()} 事件没有被账本锚定。",
                    )
                }
            }

            is RunEventMetadata.ToolResult -> {
                if (event.type != TOOL_RESULT_EVENT) return@forEach
                val result = metadata.toolCallId?.let(resultsByCallId::get)
                when {
                    metadata.toolCallId == null -> issues += AgentToolLedgerIssue(
                        code = "EVENT_RESULT_IDENTITY_MISSING",
                        message = "工具 ${metadata.toolName} 的结果事件缺少 ToolCall ID，无法与新账本核对。",
                    )

                    result == null -> issues += AgentToolLedgerIssue(
                        code = "EVENT_RESULT_MISSING_IN_LEDGER",
                        message = "工具 ${metadata.toolName} 的结果事件没有结果账本。",
                    )

                    result.eventId != event.id -> issues += AgentToolLedgerIssue(
                        code = "EVENT_RESULT_ANCHOR_MISMATCH",
                        message = "工具 ${metadata.toolName} 的结果事件没有被账本锚定。",
                    )
                }
            }

            is RunEventMetadata.ToolVerification -> {
                if (event.type != TOOL_VERIFIED_EVENT) return@forEach
                val result = metadata.toolCallId?.let(resultsByCallId::get)
                when {
                    metadata.toolCallId == null -> issues += AgentToolLedgerIssue(
                        code = "EVENT_VERIFICATION_IDENTITY_MISSING",
                        message = "工具 ${metadata.toolName} 的验证事件缺少 ToolCall ID，无法与新账本核对。",
                    )

                    result == null || result.verifiedEventId == null -> issues += AgentToolLedgerIssue(
                        code = "EVENT_VERIFICATION_MISSING_IN_LEDGER",
                        message = "工具 ${metadata.toolName} 的验证事件没有结果账本状态。",
                    )

                    result.verifiedEventId != event.id -> issues += AgentToolLedgerIssue(
                        code = "EVENT_VERIFICATION_ANCHOR_MISMATCH",
                        message = "工具 ${metadata.toolName} 的验证事件没有被账本锚定。",
                    )
                }
            }

            else -> Unit
        }
    }
    return issues.distinct()
}

private fun String.toStageLabel(): String {
    return when (this) {
        TOOL_PROPOSED_EVENT -> "proposed"
        TOOL_VALIDATED_EVENT -> "validated"
        else -> this
    }
}

private fun presentEventFallback(events: List<RunEventRecord>): AgentToolLedgerPresentation {
    val calls = linkedMapOf<String, EventToolCallBuilder>()
    events.forEach { event ->
        when (val metadata = event.metadata) {
            is RunEventMetadata.ToolCall -> {
                if (event.type != TOOL_PROPOSED_EVENT && event.type != TOOL_VALIDATED_EVENT) return@forEach
                val call = calls.getOrPut(metadata.id) {
                    EventToolCallBuilder(
                        id = metadata.id,
                        toolName = metadata.toolName,
                        risk = metadata.risk,
                        arguments = metadata.arguments,
                        createdAt = event.createdAt,
                    )
                }
                if (event.type == TOOL_PROPOSED_EVENT) call.proposed = true else call.validated = true
            }

            is RunEventMetadata.ToolResult -> {
                if (event.type != TOOL_RESULT_EVENT) return@forEach
                val call = calls.resolveEventEntry(
                    explicitId = metadata.toolCallId,
                    toolName = metadata.toolName,
                    eventId = event.id,
                    createdAt = event.createdAt,
                )
                call.result = metadata
            }

            is RunEventMetadata.ToolVerification -> {
                if (event.type != TOOL_VERIFIED_EVENT) return@forEach
                val call = calls.resolveEventEntry(
                    explicitId = metadata.toolCallId,
                    toolName = metadata.toolName,
                    eventId = event.id,
                    createdAt = event.createdAt,
                )
                call.verified = true
            }

            else -> Unit
        }
    }
    if (calls.isEmpty()) {
        return AgentToolLedgerPresentation(
            source = AgentToolDetailSource.NONE,
            calls = emptyList(),
            issues = emptyList(),
        )
    }
    return AgentToolLedgerPresentation(
        source = AgentToolDetailSource.EVENT_FALLBACK,
        calls = calls.values.map { it.toPresentation() },
        issues = emptyList(),
    )
}

private fun MutableMap<String, EventToolCallBuilder>.resolveEventEntry(
    explicitId: String?,
    toolName: String,
    eventId: String,
    createdAt: Long,
): EventToolCallBuilder {
    explicitId?.let { id ->
        return getOrPut(id) {
            EventToolCallBuilder(
                id = id,
                toolName = toolName,
                risk = null,
                arguments = emptyMap(),
                createdAt = createdAt,
            )
        }
    }
    // long: v20 以前的 typed ToolResult/ToolVerification 可能没有调用 ID；任务中心必须将其保留为“关联未知”的独立事件条目，不能按工具名或时间顺序猜测属于哪次调用。
    val entryKey = "legacy-$eventId"
    return getOrPut(entryKey) {
        EventToolCallBuilder(
            id = null,
            toolName = toolName,
            risk = null,
            arguments = emptyMap(),
            createdAt = createdAt,
        )
    }
}

private data class EventToolCallBuilder(
    val id: String?,
    val toolName: String,
    val risk: ToolRisk?,
    val arguments: Map<String, String>,
    val createdAt: Long,
    var proposed: Boolean = false,
    var validated: Boolean = false,
    var result: RunEventMetadata.ToolResult? = null,
    var verified: Boolean = false,
) {
    fun toPresentation(): AgentToolCallPresentation {
        return AgentToolCallPresentation(
            id = id,
            toolName = toolName,
            risk = risk,
            arguments = arguments,
            proposed = proposed.toStageState(),
            validated = validated.toStageState(),
            result = when {
                result == null -> AgentToolStageState.PENDING
                result?.success == true -> AgentToolStageState.COMPLETE
                else -> AgentToolStageState.FAILED
            },
            verified = when {
                verified -> AgentToolStageState.COMPLETE
                result?.success == false -> AgentToolStageState.FAILED
                else -> AgentToolStageState.PENDING
            },
            resultContent = result?.content,
            errorMessage = result?.content?.takeIf { result?.success == false },
            durationMs = result?.durationMs,
            executorVerified = result?.verified,
            memoryIdsUsed = result?.memoryIdsUsed.orEmpty(),
            replaySafety = result?.replaySafety,
            executionReceipt = result?.executionReceipt,
            createdAt = createdAt,
        )
    }
}

private fun String?.toStageState(): AgentToolStageState {
    return if (this == null) AgentToolStageState.PENDING else AgentToolStageState.COMPLETE
}

private fun Boolean.toStageState(): AgentToolStageState {
    return if (this) AgentToolStageState.COMPLETE else AgentToolStageState.PENDING
}

private const val TOOL_PROPOSED_EVENT = "tool.call.proposed"
private const val TOOL_VALIDATED_EVENT = "tool.call.validated"
private const val TOOL_RESULT_EVENT = "tool.result"
private const val TOOL_VERIFIED_EVENT = "tool.verify"
