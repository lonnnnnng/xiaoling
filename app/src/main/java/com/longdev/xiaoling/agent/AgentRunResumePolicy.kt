package com.longdev.xiaoling.agent

enum class AgentRunResumeKind {
    APPROVAL_WAIT,
    COMMITTED_TOOL_VERIFICATION,
    RESTART_REQUIRED,
}

data class AgentCommittedToolRecovery(
    val toolCall: ToolCall,
    val persistedResult: RunEventMetadata.ToolResult,
    val executionStepId: String,
    val verificationStepId: String?,
    val verifiedPrefix: List<AgentToolExecution> = emptyList(),
    val evidenceSource: AgentRunRecoveryEvidenceSource,
)

data class AgentRunResumeAssessment(
    val kind: AgentRunResumeKind,
    val reason: String,
    val committedTool: AgentCommittedToolRecovery? = null,
) {
    val canResumeInPlace: Boolean get() = kind != AgentRunResumeKind.RESTART_REQUIRED
}

object AgentRunResumePolicy {
    fun assess(
        detail: AgentRunDetailRecord,
        definitionLookup: (String) -> ToolDefinition? = { null },
        committedVerificationSupport: (String) -> Boolean = { false },
    ): AgentRunResumeAssessment {
        val snapshot = detail.snapshot
        if (snapshot.run.status == AgentRunStatus.WAITING_APPROVAL) {
            return assessApprovalWait(detail)
        }
        if (snapshot.run.status != AgentRunStatus.EXECUTING && snapshot.run.status != AgentRunStatus.VERIFYING) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "只有等待用户审批且尚未执行工具的 Run 可以原地恢复",
            )
        }
        return assessCommittedToolVerification(detail, definitionLookup, committedVerificationSupport)
    }

    private fun assessApprovalWait(detail: AgentRunDetailRecord): AgentRunResumeAssessment {
        val snapshot = detail.snapshot
        val hasPendingApproval = detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }
        if (!hasPendingApproval) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "Run 没有待处理审批，不能恢复原审批边界",
            )
        }
        val hasToolExecution = snapshot.steps.any {
            it.type == AgentStepTypes.TOOL_EXECUTE || it.type == AgentStepTypes.TOOL_VERIFY
        } || snapshot.events.any {
            it.type == "tool.result" || it.type == "tool.verify"
        }
        if (hasToolExecution) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "工具执行或验证已经开始，必须安全重新运行，不能重复原地执行",
            )
        }
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.APPROVAL_WAIT,
            reason = "工具尚未执行，保留原 Run 和审批请求等待用户决定",
        )
    }

    private fun assessCommittedToolVerification(
        detail: AgentRunDetailRecord,
        definitionLookup: (String) -> ToolDefinition?,
        committedVerificationSupport: (String) -> Boolean,
    ): AgentRunResumeAssessment {
        if (detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }) {
            return restartRequired("执行或验证中 Run 不能同时保留待审批请求")
        }
        val executionSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        val recoveryEvidence = when (val assessment = AgentRunRecoveryEvidencePolicy.read(detail)) {
            is AgentRunRecoveryEvidenceAssessment.Available -> assessment
            is AgentRunRecoveryEvidenceAssessment.Invalid -> return restartRequired(assessment.reason)
        }
        val persistedExecutions = recoveryEvidence.executions
        val passedVerificationCount = persistedExecutions.count {
            it.verificationStatus == ToolVerificationStatus.PASSED
        }
        if (executionSteps.isEmpty() || executionSteps.size != persistedExecutions.size) {
            return restartRequired("工具执行步骤与持久化结果无法一一对应")
        }
        if (passedVerificationCount != persistedExecutions.size - 1) {
            return restartRequired("只能恢复最后一个已提交但尚未验证的工具结果")
        }
        if (verificationSteps.size !in setOf(passedVerificationCount, persistedExecutions.size)) {
            return restartRequired("工具验证步骤与持久化验证事件不一致")
        }
        val verifiedPrefix = mutableListOf<AgentToolExecution>()
        persistedExecutions.dropLast(1).forEachIndexed { index, persisted ->
            val result = persisted.result
            if (!result.success || executionSteps[index].status != AgentStepStatus.COMPLETED) {
                return restartRequired("前序工具结果不是已完成成功状态")
            }
            val verificationStep = verificationSteps.getOrNull(index)
            if (
                verificationStep?.status != AgentStepStatus.COMPLETED ||
                persisted.verificationStatus != ToolVerificationStatus.PASSED ||
                persisted.toolCall.name != result.toolName
            ) {
                return restartRequired("前序工具结果缺少按顺序对应的成功验证")
            }
            verifiedPrefix += AgentToolExecution(
                toolCall = persisted.toolCall,
                toolResult = ToolExecutionResult(
                    success = result.success,
                    content = result.content,
                    verified = result.verified,
                    memoryIdsUsed = result.memoryIdsUsed,
                    executionReceipt = result.executionReceipt,
                ),
            )
        }
        val executionStep = executionSteps.last()
        if (executionStep.status != AgentStepStatus.RUNNING && executionStep.status != AgentStepStatus.COMPLETED) {
            return restartRequired("待恢复的工具执行步骤已进入不可恢复终态")
        }
        val verificationStep = verificationSteps.getOrNull(persistedExecutions.lastIndex)
        if (verificationStep != null && verificationStep.status != AgentStepStatus.RUNNING) {
            return restartRequired("待恢复的工具验证步骤不是运行中状态")
        }
        val pendingVerification = persistedExecutions.last()
        if (pendingVerification.verificationStatus != null) {
            return restartRequired("最后一个工具结果已经存在验证终态，不能再次恢复验证")
        }
        val persistedResult = pendingVerification.result
        val toolCall = pendingVerification.toolCall
        if (!committedVerificationSupport(toolCall.name)) {
            return restartRequired("工具未开放已提交结果的只读恢复验证")
        }
        val definition = definitionLookup(toolCall.name)
            ?: return restartRequired("当前注册表中找不到历史工具定义")
        val replayEvidence = ToolExecutionRecoveryEvidencePolicy.assess(definition, persistedResult)
        if (!replayEvidence.canReuseCommittedEffect) {
            return restartRequired(replayEvidence.reason)
        }
        // long: 策略只把已提交且尚未验证的最后一步交给恢复入口；既不重放写工具，也不恢复旧规划协程。
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION,
            reason = when (recoveryEvidence.source) {
                AgentRunRecoveryEvidenceSource.LEDGER ->
                    "独立工具账本中的已提交幂等证据完整，只恢复后置验证和本地总结"
                AgentRunRecoveryEvidenceSource.EVENT_FALLBACK ->
                    "旧 Run typed event 中的已提交幂等证据完整，只恢复后置验证和本地总结"
            },
            committedTool = AgentCommittedToolRecovery(
                toolCall = toolCall,
                persistedResult = persistedResult,
                executionStepId = executionStep.id,
                verificationStepId = verificationStep?.id,
                verifiedPrefix = verifiedPrefix,
                evidenceSource = recoveryEvidence.source,
            ),
        )
    }

    private fun restartRequired(reason: String) = AgentRunResumeAssessment(
        kind = AgentRunResumeKind.RESTART_REQUIRED,
        reason = reason,
    )
}
