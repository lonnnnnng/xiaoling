package com.longdev.xiaoling.agent

data class AgentNotCommittedReplayQualification(
    val toolCall: ToolCall,
    val recoveryContract: ToolDefinitionRecoverySnapshot,
)

sealed interface AgentNotCommittedReplayQualificationAssessment {
    data class Eligible(
        val qualification: AgentNotCommittedReplayQualification,
    ) : AgentNotCommittedReplayQualificationAssessment

    data class Ineligible(
        val reason: String,
    ) : AgentNotCommittedReplayQualificationAssessment
}

object AgentNotCommittedReplayQualificationPolicy {
    fun assessRecovered(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
        definitionLookup: (String) -> ToolDefinition?,
    ): AgentNotCommittedReplayQualificationAssessment {
        if (detail.snapshot.run.status != AgentRunStatus.CANCELLED) {
            return ineligible("只有已经完成启动收敛的 Run 才能重新核验受控重放资格")
        }
        val recoveryIndex = detail.snapshot.events.indexOfLast { it.type == "run.recovered" }
        if (recoveryIndex < 0) return ineligible("Run 缺少最新的启动恢复事件")
        val recoveryEvent = detail.snapshot.events[recoveryIndex]
        val trailingEvents = detail.snapshot.events.drop(recoveryIndex + 1)
        if (
            trailingEvents.size != 1 ||
            trailingEvents.single().let { event ->
                event.type != "run.status" ||
                    event.message != AgentRunStatus.CANCELLED.name ||
                    event.metadata != null
            }
        ) {
            return ineligible("启动恢复事件之后出现了非预期运行证据")
        }
        val recovery = recoveryEvent.metadata as? RunEventMetadata.Recovery
            ?: return ineligible("最新启动恢复事件缺少结构化证据")
        if (
            recovery.fromStatus != AgentRunStatus.EXECUTING ||
            recovery.toStatus != AgentRunStatus.CANCELLED ||
            recovery.resumeKind != AgentRunResumeKind.RESTART_REQUIRED ||
            recovery.restartDisposition?.code !=
            AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE ||
            recovery.retryEvidenceCode != AgentTaskRetryEvidenceCode.NOT_COMMITTED
        ) {
            return ineligible("启动恢复事件不再匹配尚未提交受控重放资格")
        }
        val persistedFingerprint = recovery.retryEvidenceFingerprint
            ?: return ineligible("启动恢复事件缺少重试证据指纹")
        if (persistedFingerprint != AgentTaskRetryEvidenceFingerprint.calculate(detail)) {
            return ineligible("启动恢复后的 Tool Ledger 或事件证据已经漂移")
        }

        // long: 旧 Run 已被永久收敛为 CANCELLED；这里仅构造不落库的收敛前视图复用完整资格规则，避免为了重试再次修改旧 Run 或放宽原始执行边界。
        val preRecoveryView = detail.copy(
            snapshot = detail.snapshot.copy(
                run = detail.snapshot.run.copy(
                    status = recovery.fromStatus,
                    completedAt = null,
                ),
                events = detail.snapshot.events.take(recoveryIndex),
            ),
        )
        return assess(preRecoveryView, agentProfile, definitionLookup)
    }

    fun assess(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
        definitionLookup: (String) -> ToolDefinition?,
    ): AgentNotCommittedReplayQualificationAssessment {
        if (detail.snapshot.run.status != AgentRunStatus.EXECUTING) {
            return ineligible("只有停在执行步骤落库前的 Run 才能评估尚未提交重放资格")
        }
        val profile = agentProfile ?: return ineligible("原 Run 缺少 Agent Profile 能力快照")
        if (detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }) {
            return ineligible("Run 仍有待处理审批，不能认定用户审批语义已经冻结")
        }
        val consistency = AgentToolLedgerConsistencyPolicy.inspect(detail)
        val executions = when (consistency) {
            AgentToolLedgerConsistencyAssessment.Empty -> return ineligible("旧 Run 缺少独立 Tool Ledger")
            is AgentToolLedgerConsistencyAssessment.Invalid -> return ineligible(consistency.reason)
            is AgentToolLedgerConsistencyAssessment.Available -> consistency.executions
        }
        if (executions.isEmpty()) return ineligible("Tool Ledger 没有可重建的调用")
        val pending = executions.last()
        if (pending.call.validatedEventId == null || pending.result != null) {
            return ineligible("链尾 ToolCall 必须已经校验且尚未产生 ToolResult")
        }
        if (executions.dropLast(1).any { execution ->
                execution.result?.let { it.success && it.verificationStatus == ToolVerificationStatus.PASSED } != true
            }
        ) {
            return ineligible("链尾之前的工具没有形成成功且已验证的持久化前缀")
        }

        val executionSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        val prefixSize = executions.lastIndex
        if (
            executionSteps.size != prefixSize ||
            verificationSteps.size != prefixSize ||
            executionSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.any { it.status != AgentStepStatus.COMPLETED }
        ) {
            return ineligible("执行与验证步骤不能证明链尾调用尚未进入副作用边界")
        }

        val orderedEvents = detail.snapshot.events
        val eventsById = orderedEvents.associateBy { it.id }
        if (eventsById.size != orderedEvents.size) return ineligible("RunEvent 存在重复事件 ID")
        val proposedEvent = pending.call.proposedEventId
            ?.let(eventsById::get)
            ?: return ineligible("链尾 ToolCall 缺少 proposed 恢复契约快照")
        val proposed = proposedEvent.metadata as? RunEventMetadata.ToolCall
            ?: return ineligible("链尾 ToolCall 缺少 proposed 恢复契约快照")
        val validatedEvent = checkNotNull(pending.call.validatedEventId)
            .let(eventsById::get)
            ?: return ineligible("链尾 ToolCall 缺少 validated 恢复契约快照")
        val validated = validatedEvent.metadata as? RunEventMetadata.ToolCall
            ?: return ineligible("链尾 ToolCall 缺少 validated 恢复契约快照")
        val recoveryContract = validated.recoveryContract
            ?: return ineligible("链尾 ToolCall 是缺少恢复契约快照的历史记录")
        if (proposed.recoveryContract != recoveryContract) {
            return ineligible("ToolCall 的 proposed 与 validated 恢复契约发生漂移")
        }
        if (recoveryContract.notCommittedReplayPolicy != ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL) {
            return ineligible("工具执行时没有声明尚未提交受控重放")
        }

        val toolCall = ToolCall(
            id = pending.call.id,
            name = pending.call.toolName,
            arguments = pending.call.arguments,
            risk = pending.call.risk,
        )
        if (toolCall.name !in profile.allowedToolNames) {
            return ineligible("链尾工具超出原 Agent Profile 能力快照")
        }
        val definition = definitionLookup(toolCall.name)
            ?: return ineligible("当前 Registry 缺少历史工具定义")
        if (!ToolDefinitionRecoveryContract.matches(definition, recoveryContract)) {
            return ineligible("当前工具定义与执行时恢复契约指纹不一致")
        }

        val approvals = detail.approvals.filter { it.toolCallId == toolCall.id }
        val approval = approvals.singleOrNull()
            ?: return ineligible("链尾 ToolCall 没有唯一审批记录")
        if (
            approval.status != ApprovalRequestStatus.APPROVED ||
            approval.decidedAt == null ||
            approval.runId != detail.snapshot.run.id ||
            approval.toolName != toolCall.name ||
            approval.risk != toolCall.risk ||
            approval.arguments != toolCall.arguments
        ) {
            return ineligible("用户审批记录与链尾 ToolCall 不一致或尚未批准")
        }
        val requestedEvent = orderedEvents.singleOrNull { event ->
            event.type == "approval.requested" &&
                (event.metadata as? RunEventMetadata.ApprovalRequest)?.id == approval.id
        }
            ?: return ineligible("审批记录缺少唯一 requested 事件")
        val requested = requestedEvent.metadata as RunEventMetadata.ApprovalRequest
        val decidedEvent = orderedEvents.singleOrNull { event ->
            event.type == "approval.request_decided" &&
                (event.metadata as? RunEventMetadata.ApprovalRequest)?.id == approval.id
        }
            ?: return ineligible("审批记录缺少唯一 decided 事件")
        val decided = decidedEvent.metadata as RunEventMetadata.ApprovalRequest
        if (
            requested.toolName != toolCall.name ||
            requested.risk != toolCall.risk ||
            requested.arguments != toolCall.arguments ||
            requested.status != ApprovalRequestStatus.PENDING ||
            requested.definitionFingerprint != recoveryContract.definitionFingerprint ||
            decided.toolName != toolCall.name ||
            decided.risk != toolCall.risk ||
            decided.arguments != toolCall.arguments ||
            decided.definitionFingerprint != recoveryContract.definitionFingerprint ||
            decided.status != ApprovalRequestStatus.APPROVED
        ) {
            return ineligible("审批事件与冻结工具定义或 ToolCall 语义发生漂移")
        }
        val validatedIndex = orderedEvents.indexOfFirst { it.id == validatedEvent.id }
        val requestedIndex = orderedEvents.indexOfFirst { it.id == requestedEvent.id }
        val decidedIndex = orderedEvents.indexOfFirst { it.id == decidedEvent.id }
        if (!(validatedIndex < requestedIndex && requestedIndex < decidedIndex)) {
            return ineligible("审批事件顺序不能证明 ToolCall 校验后才发起并完成用户审批")
        }
        val lastApprovalStep = detail.snapshot.steps.lastOrNull { it.type == "approval" }
            ?: return ineligible("链尾 ToolCall 缺少审批步骤")
        if (
            lastApprovalStep.status != AgentStepStatus.COMPLETED ||
            detail.snapshot.steps.any { it.sequence > lastApprovalStep.sequence }
        ) {
            return ineligible("审批步骤之后已出现新的运行步骤，不能证明副作用边界未进入")
        }

        // long: 这里仅签发“未来可创建关联新 Run 的资格”，旧 Run、旧模型协程和旧 Executor 仍必须按启动收敛进入终态。
        return AgentNotCommittedReplayQualificationAssessment.Eligible(
            AgentNotCommittedReplayQualification(
                toolCall = toolCall,
                recoveryContract = recoveryContract,
            ),
        )
    }

    private fun ineligible(reason: String) = AgentNotCommittedReplayQualificationAssessment.Ineligible(reason)
}
