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
        val toolResults = detail.snapshot.events.mapNotNull { event ->
            (event.metadata as? RunEventMetadata.ToolResult)?.takeIf { event.type == "tool.result" }
        }
        val passedVerifications = detail.snapshot.events.mapNotNull { event ->
            (event.metadata as? RunEventMetadata.ToolVerification)?.takeIf { event.type == "tool.verify" }
        }
        if (executionSteps.isEmpty() || executionSteps.size != toolResults.size) {
            return restartRequired("工具执行步骤与持久化结果无法一一对应")
        }
        if (passedVerifications.size != toolResults.size - 1) {
            return restartRequired("只能恢复最后一个已提交但尚未验证的工具结果")
        }
        if (verificationSteps.size !in setOf(passedVerifications.size, toolResults.size)) {
            return restartRequired("工具验证步骤与持久化验证事件不一致")
        }
        val verifiedPrefix = mutableListOf<AgentToolExecution>()
        toolResults.dropLast(1).forEachIndexed { index, result ->
            if (!result.success || executionSteps[index].status != AgentStepStatus.COMPLETED) {
                return restartRequired("前序工具结果不是已完成成功状态")
            }
            val verificationStep = verificationSteps.getOrNull(index)
            val verification = passedVerifications.getOrNull(index)
            if (
                verificationStep?.status != AgentStepStatus.COMPLETED ||
                verification?.status != ToolVerificationStatus.PASSED ||
                verification.toolName != result.toolName
            ) {
                return restartRequired("前序工具结果缺少按顺序对应的成功验证")
            }
            val prefixCall = findUniqueToolCall(detail, result)
                ?: return restartRequired("前序工具结果无法唯一匹配原始 ToolCall")
            verifiedPrefix += AgentToolExecution(
                toolCall = prefixCall,
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
        val verificationStep = verificationSteps.getOrNull(toolResults.lastIndex)
        if (verificationStep != null && verificationStep.status != AgentStepStatus.RUNNING) {
            return restartRequired("待恢复的工具验证步骤不是运行中状态")
        }
        val persistedResult = toolResults.last()
        val toolCall = findUniqueToolCall(detail, persistedResult)
            ?: return restartRequired("工具结果无法唯一匹配原始 ToolCall")
        if (!committedVerificationSupport(toolCall.name)) {
            return restartRequired("工具未开放已提交结果的只读恢复验证")
        }
        val definition = definitionLookup(toolCall.name)
            ?: return restartRequired("当前注册表中找不到历史工具定义")
        val evidence = ToolExecutionRecoveryEvidencePolicy.assess(definition, persistedResult)
        if (!evidence.canReuseCommittedEffect) {
            return restartRequired(evidence.reason)
        }
        // long: 策略只把已提交且尚未验证的最后一步交给恢复入口；既不重放写工具，也不恢复旧规划协程。
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION,
            reason = "已提交工具结果的幂等证据完整，只恢复后置验证和本地总结",
            committedTool = AgentCommittedToolRecovery(
                toolCall = toolCall,
                persistedResult = persistedResult,
                executionStepId = executionStep.id,
                verificationStepId = verificationStep?.id,
                verifiedPrefix = verifiedPrefix,
            ),
        )
    }

    private fun findUniqueToolCall(
        detail: AgentRunDetailRecord,
        result: RunEventMetadata.ToolResult,
    ): ToolCall? {
        val toolCallId = result.toolCallId ?: return null
        val matchingCalls = detail.snapshot.events.mapNotNull { event ->
            (event.metadata as? RunEventMetadata.ToolCall)
                ?.takeIf { event.type == "tool.call.proposed" || event.type == "tool.call.validated" }
                ?.takeIf { it.id == toolCallId && it.toolName == result.toolName }
        }.distinct()
        val metadata = matchingCalls.singleOrNull() ?: return null
        return ToolCall(
            id = metadata.id,
            name = metadata.toolName,
            arguments = metadata.arguments,
            risk = metadata.risk,
        )
    }

    private fun restartRequired(reason: String) = AgentRunResumeAssessment(
        kind = AgentRunResumeKind.RESTART_REQUIRED,
        reason = reason,
    )
}
