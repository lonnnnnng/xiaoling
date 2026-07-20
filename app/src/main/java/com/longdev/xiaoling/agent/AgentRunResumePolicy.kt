package com.longdev.xiaoling.agent

enum class AgentRunResumeKind {
    APPROVAL_WAIT,
    COMMITTED_TOOL_VERIFICATION,
    VERIFIED_TOOL_COMPLETION,
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

data class AgentApprovalWaitRecovery(
    val approvalRequestId: String,
    val toolCall: ToolCall,
    val approvalStepId: String,
    val verifiedPrefix: List<AgentToolExecution>,
    val evidenceSource: AgentRunRecoveryEvidenceSource,
)

data class AgentVerifiedToolRecovery(
    val verifiedTools: List<AgentToolExecution>,
    val lastVerificationStepId: String,
    val evidenceSource: AgentRunRecoveryEvidenceSource,
)

data class AgentRunResumeAssessment(
    val kind: AgentRunResumeKind,
    val reason: String,
    val committedTool: AgentCommittedToolRecovery? = null,
    val approvalWait: AgentApprovalWaitRecovery? = null,
    val verifiedTool: AgentVerifiedToolRecovery? = null,
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
        val agentProfile = when (val assessment = detail.inspectAgentProfileAudit()) {
            AgentProfileAuditAssessment.Legacy -> null
            is AgentProfileAuditAssessment.Available -> assessment.profile
            is AgentProfileAuditAssessment.Invalid -> return restartRequired(assessment.reason)
        }
        if (snapshot.run.status == AgentRunStatus.WAITING_APPROVAL) {
            return assessApprovalWait(detail, agentProfile)
        }
        if (snapshot.run.status != AgentRunStatus.EXECUTING && snapshot.run.status != AgentRunStatus.VERIFYING) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "只有等待用户审批且尚未执行工具的 Run 可以原地恢复",
            )
        }
        assessVerifiedToolCompletion(detail, agentProfile)?.let { return it }
        return assessCommittedToolVerification(detail, agentProfile, definitionLookup, committedVerificationSupport)
    }

    private fun assessVerifiedToolCompletion(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
    ): AgentRunResumeAssessment? {
        if (detail.snapshot.run.status != AgentRunStatus.VERIFYING) return null
        if (detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }) {
            return restartRequired("验证中 Run 不能同时保留待审批请求")
        }
        val recoveryEvidence = when (val assessment = AgentRunRecoveryEvidencePolicy.read(detail)) {
            is AgentRunRecoveryEvidenceAssessment.Available -> assessment
            is AgentRunRecoveryEvidenceAssessment.Invalid -> return restartRequired(assessment.reason)
        }
        val persistedExecutions = recoveryEvidence.executions
        if (persistedExecutions.isEmpty() || persistedExecutions.any {
                it.verificationStatus != ToolVerificationStatus.PASSED
            }
        ) {
            return null
        }
        if (agentProfile != null && persistedExecutions.any { it.toolCall.name !in agentProfile.allowedToolNames }) {
            return restartRequired("已验证工具超出原 Agent Profile 白名单")
        }
        val executionSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        if (
            executionSteps.size != persistedExecutions.size ||
            verificationSteps.size != persistedExecutions.size ||
            executionSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.dropLast(1).any { it.status != AgentStepStatus.COMPLETED }
        ) {
            return restartRequired("已验证工具的执行与验证步骤无法一一对应")
        }
        val lastVerificationStep = verificationSteps.last()
        if (lastVerificationStep.status != AgentStepStatus.RUNNING && lastVerificationStep.status != AgentStepStatus.COMPLETED) {
            return restartRequired("最后一个已验证工具步骤处于不可恢复终态")
        }
        if (detail.snapshot.steps.any { it.sequence > lastVerificationStep.sequence }) {
            return restartRequired("最后一个已验证工具之后已经出现新的运行步骤")
        }
        val verifiedTools = persistedExecutions.mapIndexed { index, persisted ->
            val result = persisted.result
            if (!result.success || result.toolName != persisted.toolCall.name) {
                return restartRequired("已验证工具结果不是成功且身份一致的终态：${persisted.toolCall.id}")
            }
            val verificationStep = verificationSteps[index]
            if (index < verificationSteps.lastIndex && verificationStep.status != AgentStepStatus.COMPLETED) {
                return restartRequired("前序工具验证步骤尚未完成：${persisted.toolCall.id}")
            }
            AgentToolExecution(
                toolCall = persisted.toolCall,
                toolResult = ToolExecutionResult(
                    success = result.success,
                    content = result.content,
                    verified = result.verified,
                    memoryIdsUsed = result.memoryIdsUsed,
                    knowledgeReferences = result.knowledgeReferences,
                    executionReceipt = result.executionReceipt,
                ),
            )
        }
        // long: tool.verify 已证明副作用和后置检查完成；这里只恢复控制面收尾，不重放工具，也不重建已经丢失的模型规划协程。
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.VERIFIED_TOOL_COMPLETION,
            reason = "全部工具结果和验证证据已持久化，只补齐验证步骤并生成本地可信总结",
            verifiedTool = AgentVerifiedToolRecovery(
                verifiedTools = verifiedTools,
                lastVerificationStepId = lastVerificationStep.id,
                evidenceSource = recoveryEvidence.source,
            ),
        )
    }

    private fun assessApprovalWait(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
    ): AgentRunResumeAssessment {
        val snapshot = detail.snapshot
        val pendingApprovals = detail.approvals.filter { it.status == ApprovalRequestStatus.PENDING }
        if (pendingApprovals.size != 1) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "Run 必须恰好存在一个待处理审批，不能恢复含糊的审批边界",
            )
        }
        val pendingApproval = pendingApprovals.single()
        if (agentProfile != null && pendingApproval.toolName !in agentProfile.allowedToolNames) {
            return restartRequired("待审批工具超出原 Agent Profile 白名单")
        }
        val evidence = when (val assessment = AgentRunRecoveryEvidencePolicy.readPendingApproval(detail)) {
            is AgentPendingApprovalRecoveryEvidenceAssessment.Available -> assessment.evidence
            is AgentPendingApprovalRecoveryEvidenceAssessment.Invalid -> return restartRequired(assessment.reason)
        }
        val pendingToolCall = evidence.pendingToolCall
        if (
            pendingApproval.runId != snapshot.run.id ||
            pendingApproval.toolCallId != pendingToolCall.id ||
            pendingApproval.toolName != pendingToolCall.name ||
            pendingApproval.arguments != pendingToolCall.arguments ||
            pendingApproval.risk != pendingToolCall.risk
        ) {
            return restartRequired("待审批请求与最后一个未执行 ToolCall 不一致")
        }
        val executionSteps = snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        if (
            executionSteps.size != evidence.verifiedPrefix.size ||
            verificationSteps.size != evidence.verifiedPrefix.size ||
            executionSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.any { it.status != AgentStepStatus.COMPLETED }
        ) {
            return restartRequired("前序工具步骤与已验证恢复证据不一致")
        }
        val approvalSteps = snapshot.steps.filter { it.type == "approval" }
        val activeApprovalSteps = approvalSteps.filter { it.status == AgentStepStatus.RUNNING }
        if (activeApprovalSteps.size != 1 || approvalSteps.lastOrNull()?.id != activeApprovalSteps.single().id) {
            return restartRequired("无法唯一定位最后一个待审批步骤")
        }
        if (snapshot.steps.any { it.sequence > activeApprovalSteps.single().sequence }) {
            return restartRequired("待审批步骤之后已经出现新的执行步骤")
        }
        // long: 进程重建只接受“前序全部验证、链尾尚未执行”的确定边界，恢复后继续当前 ToolCall，不重放任何已完成副作用。
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.APPROVAL_WAIT,
            reason = if (evidence.verifiedPrefix.isEmpty()) {
                "首个工具尚未执行，保留原 Run 和审批请求等待用户决定"
            } else {
                "前序 ${evidence.verifiedPrefix.size} 个工具均已验证，只恢复最后一个尚未执行的审批请求"
            },
            approvalWait = AgentApprovalWaitRecovery(
                approvalRequestId = pendingApproval.id,
                toolCall = pendingToolCall,
                approvalStepId = activeApprovalSteps.single().id,
                verifiedPrefix = evidence.verifiedPrefix,
                evidenceSource = evidence.source,
            ),
        )
    }

    private fun assessCommittedToolVerification(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
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
        if (agentProfile != null && persistedExecutions.any { it.toolCall.name !in agentProfile.allowedToolNames }) {
            return restartRequired("持久化工具结果超出原 Agent Profile 白名单")
        }
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
                    knowledgeReferences = result.knowledgeReferences,
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
