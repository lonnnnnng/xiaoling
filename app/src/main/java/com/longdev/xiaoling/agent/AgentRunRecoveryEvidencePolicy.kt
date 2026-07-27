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

data class AgentPendingApprovalRecoveryEvidence(
    val pendingToolCall: ToolCall,
    val verifiedPrefix: List<AgentToolExecution>,
    val source: AgentRunRecoveryEvidenceSource,
)

sealed interface AgentPendingApprovalRecoveryEvidenceAssessment {
    data class Available(
        val evidence: AgentPendingApprovalRecoveryEvidence,
    ) : AgentPendingApprovalRecoveryEvidenceAssessment

    data class Invalid(
        val reason: String,
    ) : AgentPendingApprovalRecoveryEvidenceAssessment
}

sealed interface AgentRunRecoveryEvidenceAssessment {
    data class Available(
        val source: AgentRunRecoveryEvidenceSource,
        val executions: List<AgentPersistedToolRecoveryEvidence>,
    ) : AgentRunRecoveryEvidenceAssessment

    data class Invalid(
        val reason: String,
    ) : AgentRunRecoveryEvidenceAssessment

    data class CommitUnknown(
        val reason: String,
    ) : AgentRunRecoveryEvidenceAssessment
}

internal fun AgentRunDetailRecord.hasPersistedToolExecutionBoundary(expectedExecutionCount: Int): Boolean {
    val executionSteps = snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
    if (executionSteps.size != expectedExecutionCount || executionSteps.isEmpty()) return false
    // long: 前序调用只有在执行步骤完成后才可能进入下一次调用；链尾则允许停在工具返回前或结果落库后的短窗口，用于保守识别提交状态未知。
    return executionSteps.dropLast(1).all { it.status == AgentStepStatus.COMPLETED } &&
        executionSteps.last().status in setOf(AgentStepStatus.RUNNING, AgentStepStatus.COMPLETED)
}

object AgentRunRecoveryEvidencePolicy {
    fun readPendingApproval(detail: AgentRunDetailRecord): AgentPendingApprovalRecoveryEvidenceAssessment {
        return when (val consistency = AgentToolLedgerConsistencyPolicy.inspect(detail)) {
            AgentToolLedgerConsistencyAssessment.Empty -> readPendingApprovalEventFallback(detail)
            is AgentToolLedgerConsistencyAssessment.Invalid -> pendingInvalid(consistency.reason)
            is AgentToolLedgerConsistencyAssessment.Available -> {
                val executions = consistency.executions
                if (executions.isEmpty()) return pendingInvalid("工具账本没有待审批 ToolCall")
                val pending = executions.last()
                if (pending.call.validatedEventId == null || pending.result != null) {
                    return pendingInvalid("待审批 ToolCall 必须已校验且尚未产生工具结果")
                }
                val verifiedPrefix = executions.dropLast(1).map { execution ->
                    execution.toVerifiedExecution()
                        ?: return pendingInvalid("前序工具必须成功执行并通过验证：${execution.call.id}")
                }
                AgentPendingApprovalRecoveryEvidenceAssessment.Available(
                    AgentPendingApprovalRecoveryEvidence(
                        pendingToolCall = pending.call.toToolCall(),
                        verifiedPrefix = verifiedPrefix,
                        source = AgentRunRecoveryEvidenceSource.LEDGER,
                    ),
                )
            }
        }
    }

    fun read(detail: AgentRunDetailRecord): AgentRunRecoveryEvidenceAssessment {
        return when (val consistency = AgentToolLedgerConsistencyPolicy.inspect(detail)) {
            AgentToolLedgerConsistencyAssessment.Empty -> readEventFallback(detail)
            is AgentToolLedgerConsistencyAssessment.Invalid -> invalid(consistency.reason)
            is AgentToolLedgerConsistencyAssessment.Available -> {
                // long: 只有 validated 锚点能证明调用已经越过校验并进入执行边界；若仍停在 proposed，副作用尚未建立可信归属，应继续按恢复证据无效处理。
                consistency.executions.firstOrNull { it.result == null }?.let { execution ->
                    if (execution.call.validatedEventId == null) {
                        return invalid("工具调用缺少 validated 事件锚点：${execution.call.id}")
                    }
                    if (!detail.hasPersistedToolExecutionBoundary(consistency.executions.size)) {
                        return invalid("工具调用缺少对应的运行中执行步骤：${execution.call.id}")
                    }
                    // long: 已校验调用没有 ToolResult 时，进程中断点可能位于副作用提交之后、结果落库之前；必须标记提交未知并禁止重放旧执行栈。
                    return AgentRunRecoveryEvidenceAssessment.CommitUnknown(
                        reason = "工具调用缺少持久化结果，提交状态未知：${execution.call.id}",
                    )
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
        val validatedCallIds = mutableSetOf<String>()
        detail.snapshot.events.forEach { event ->
            val call = (event.metadata as? RunEventMetadata.ToolCall)
                ?.takeIf { event.type == "tool.call.proposed" || event.type == "tool.call.validated" }
                ?: return@forEach
            val existing = callMetadataById[call.id]
            if (existing != null && existing != call) {
                return invalid("旧 Run 的 ToolCall 身份或参数发生漂移：${call.id}")
            }
            if (event.type == "tool.call.validated" && !validatedCallIds.add(call.id)) {
                return invalid("旧 Run 的 ToolCall 存在重复 validated 事件：${call.id}")
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
        val resultCallIds = executions.mapTo(mutableSetOf()) { it.toolCall.id }
        val missingCalls = callMetadataById.values.filter { it.id !in resultCallIds }
        if (missingCalls.isNotEmpty()) {
            val missingCall = missingCalls.singleOrNull()
                ?: return invalid("旧 Run 存在多个缺少结果的 ToolCall")
            if (missingCall.id != callMetadataById.keys.lastOrNull() || missingCall.id !in validatedCallIds) {
                return invalid("旧 Run 只有链尾已校验 ToolCall 可以进入提交未知边界：${missingCall.id}")
            }
            if (!detail.hasPersistedToolExecutionBoundary(callMetadataById.size)) {
                return invalid("旧 Run 的 ToolCall 缺少对应的运行中执行步骤：${missingCall.id}")
            }
            // long: legacy Run 没有独立 Tool Ledger；只有 typed validated 调用、链尾缺结果和执行步骤三项同时成立时，才能保守认定副作用提交状态未知。
            return AgentRunRecoveryEvidenceAssessment.CommitUnknown(
                reason = "旧 Run 的工具调用缺少持久化结果，提交状态未知：${missingCall.id}",
            )
        }
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
            val toolCallId = verification.toolCallId
                ?: return invalid("旧 Run 的工具验证缺少 ToolCall ID：${verification.toolName}")
            // long: 工具名和事件顺序都不能证明验证属于哪次调用；缺少稳定 ID 时保留关联未知，避免把不完整历史升级成可恢复事实。
            val matched = executions.singleOrNull { evidence ->
                evidence.verificationStatus == null &&
                    evidence.toolCall.id == toolCallId &&
                    evidence.toolCall.name == verification.toolName
            }
            matched ?: return invalid("旧 Run 的工具验证无法唯一匹配结果：$toolCallId")
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

    private fun readPendingApprovalEventFallback(
        detail: AgentRunDetailRecord,
    ): AgentPendingApprovalRecoveryEvidenceAssessment {
        val callsById = linkedMapOf<String, MutablePendingApprovalEvidence>()
        detail.snapshot.events.forEachIndexed { position, event ->
            val call = (event.metadata as? RunEventMetadata.ToolCall)
                ?.takeIf { event.type == "tool.call.proposed" || event.type == "tool.call.validated" }
                ?: return@forEachIndexed
            val existing = callsById[call.id]
            if (existing == null) {
                if (event.type != "tool.call.proposed") {
                    return pendingInvalid("旧 Run 的 ToolCall 缺少 proposed 事件：${call.id}")
                }
                callsById[call.id] = MutablePendingApprovalEvidence(
                    toolCall = ToolCall(call.id, call.toolName, call.arguments, call.risk),
                    proposedPosition = position,
                )
            } else {
                if (existing.toolCall != ToolCall(call.id, call.toolName, call.arguments, call.risk)) {
                    return pendingInvalid("旧 Run 的 ToolCall 身份或参数发生漂移：${call.id}")
                }
                if (event.type != "tool.call.validated" || existing.validatedPosition != null) {
                    return pendingInvalid("旧 Run 的 ToolCall 校验事件重复或顺序异常：${call.id}")
                }
                existing.validatedPosition = position
            }
        }
        if (callsById.isEmpty()) return pendingInvalid("旧 Run 没有 typed ToolCall 证据")

        detail.snapshot.events.forEachIndexed { position, event ->
            when (val metadata = event.metadata) {
                is RunEventMetadata.ToolResult -> {
                    if (event.type != "tool.result") return@forEachIndexed
                    val toolCallId = metadata.toolCallId
                        ?: return pendingInvalid("旧 Run 的工具结果缺少 ToolCall ID")
                    val evidence = callsById[toolCallId]
                        ?.takeIf { it.toolCall.name == metadata.toolName }
                        ?: return pendingInvalid("旧 Run 的工具结果无法匹配 ToolCall：$toolCallId")
                    if (evidence.result != null) return pendingInvalid("旧 Run 的同一 ToolCall 存在多个结果：$toolCallId")
                    evidence.result = metadata
                    evidence.resultPosition = position
                }

                is RunEventMetadata.ToolVerification -> {
                    if (event.type != "tool.verify") return@forEachIndexed
                    val toolCallId = metadata.toolCallId
                        ?: return pendingInvalid("旧 Run 的工具验证缺少 ToolCall ID")
                    val evidence = callsById[toolCallId]
                        ?.takeIf { it.toolCall.name == metadata.toolName }
                        ?: return pendingInvalid("旧 Run 的工具验证无法匹配 ToolCall：$toolCallId")
                    if (evidence.verificationStatus != null) {
                        return pendingInvalid("旧 Run 的同一 ToolCall 存在多个验证结果：$toolCallId")
                    }
                    evidence.verificationStatus = metadata.status
                    evidence.verifiedPosition = position
                }

                else -> Unit
            }
        }

        val executions = callsById.values.sortedBy { it.proposedPosition }
        val pending = executions.last()
        if (pending.validatedPosition == null || pending.result != null || pending.verificationStatus != null) {
            return pendingInvalid("最后一个 ToolCall 必须已校验且停在执行前")
        }
        val verifiedPrefix = executions.dropLast(1).map { evidence ->
            val result = evidence.result
                ?: return pendingInvalid("前序工具缺少结果：${evidence.toolCall.id}")
            if (
                evidence.validatedPosition == null ||
                !result.success ||
                evidence.verificationStatus != ToolVerificationStatus.PASSED ||
                evidence.proposedPosition >= checkNotNull(evidence.validatedPosition) ||
                checkNotNull(evidence.validatedPosition) >= checkNotNull(evidence.resultPosition) ||
                checkNotNull(evidence.resultPosition) >= checkNotNull(evidence.verifiedPosition)
            ) {
                return pendingInvalid("前序工具没有形成成功且有序的验证链：${evidence.toolCall.id}")
            }
            AgentToolExecution(
                toolCall = evidence.toolCall,
                toolResult = result.toExecutionResult(),
            )
        }
        for (index in 0 until executions.lastIndex) {
            if (checkNotNull(executions[index].verifiedPosition) >= executions[index + 1].proposedPosition) {
                return pendingInvalid("旧 Run 的工具事件不符合严格串行顺序")
            }
        }
        return AgentPendingApprovalRecoveryEvidenceAssessment.Available(
            AgentPendingApprovalRecoveryEvidence(
                pendingToolCall = pending.toolCall,
                verifiedPrefix = verifiedPrefix,
                source = AgentRunRecoveryEvidenceSource.EVENT_FALLBACK,
            ),
        )
    }

    private fun invalid(reason: String) = AgentRunRecoveryEvidenceAssessment.Invalid(reason)

    private fun pendingInvalid(reason: String) = AgentPendingApprovalRecoveryEvidenceAssessment.Invalid(reason)

    private fun AgentToolLedgerExecutionRecord.toVerifiedExecution(): AgentToolExecution? {
        val result = result ?: return null
        if (!result.success || result.verificationStatus != ToolVerificationStatus.PASSED) return null
        return AgentToolExecution(
            toolCall = call.toToolCall(),
            toolResult = result.toEventMetadata().toExecutionResult(),
        )
    }

    private fun AgentToolCallRecord.toToolCall() = ToolCall(
        id = id,
        name = toolName,
        arguments = arguments,
        risk = risk,
    )

    private fun RunEventMetadata.ToolResult.toExecutionResult() = ToolExecutionResult(
        success = success,
        content = content,
        verified = verified,
        memoryIdsUsed = memoryIdsUsed,
        knowledgeReferences = knowledgeReferences,
        executionReceipt = executionReceipt,
    )

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

    private data class MutablePendingApprovalEvidence(
        val toolCall: ToolCall,
        val proposedPosition: Int,
        var validatedPosition: Int? = null,
        var result: RunEventMetadata.ToolResult? = null,
        var resultPosition: Int? = null,
        var verificationStatus: ToolVerificationStatus? = null,
        var verifiedPosition: Int? = null,
    )
}
