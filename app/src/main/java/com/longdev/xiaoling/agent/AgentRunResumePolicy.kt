package com.longdev.xiaoling.agent

enum class AgentRunResumeKind {
    APPROVAL_WAIT,
    COMMITTED_TOOL_VERIFICATION,
    VERIFIED_TOOL_COMPLETION,
    PERSISTED_TOOL_FAILURE_SETTLEMENT,
    PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
    RESTART_REQUIRED,
}

enum class AgentRunRestartDispositionCode {
    RUN_STATE_NOT_RESUMABLE,
    PROFILE_EVIDENCE_INVALID,
    EXECUTION_BUDGET_INVALID,
    APPROVAL_BOUNDARY_INVALID,
    RECOVERY_EVIDENCE_INVALID,
    COMMIT_UNKNOWN,
    NOT_COMMITTED_REPLAY_ELIGIBLE,
    PROFILE_CAPABILITY_MISMATCH,
    EXECUTION_STEP_EVIDENCE_INVALID,
    EPHEMERAL_TOOL_INPUT_UNAVAILABLE,
    COMMITTED_VERIFICATION_UNAVAILABLE,
    TOOL_DEFINITION_UNAVAILABLE,
    COMMITTED_EFFECT_EVIDENCE_INVALID,
}

data class AgentRunRestartDisposition(
    val code: AgentRunRestartDispositionCode,
    val reason: String,
    val evidenceBoundary: String,
    val suggestedAction: String,
)

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
    val recoverySummaryStepId: String? = null,
)

data class AgentPersistedToolFailureRecovery(
    val toolCall: ToolCall,
    val executionStepId: String,
    val failureReason: String,
)

data class AgentPersistedToolVerificationFailureRecovery(
    val toolCall: ToolCall,
    val verificationStepId: String,
    val failureReason: String,
)

data class AgentRunResumeAssessment(
    val kind: AgentRunResumeKind,
    val reason: String,
    val committedTool: AgentCommittedToolRecovery? = null,
    val approvalWait: AgentApprovalWaitRecovery? = null,
    val verifiedTool: AgentVerifiedToolRecovery? = null,
    val persistedToolFailure: AgentPersistedToolFailureRecovery? = null,
    val persistedToolVerificationFailure: AgentPersistedToolVerificationFailureRecovery? = null,
    val restartDisposition: AgentRunRestartDisposition? = null,
) {
    init {
        // long: RESTART_REQUIRED 如果没有结构化处置，持久化和任务中心只能依赖易变文案；构造时直接拒绝这种不完整结论。
        require((kind == AgentRunResumeKind.RESTART_REQUIRED) == (restartDisposition != null)) {
            "RESTART_REQUIRED 必须且只能携带 restartDisposition"
        }
        // long: 失败收敛载荷只传递事务结算需要的工具、执行 Step 和稳定失败原因；类型与载荷必须同时出现，避免 Repository 拿到半份控制面指令。
        require(
            (kind == AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT) ==
                (persistedToolFailure != null)
        ) {
            "PERSISTED_TOOL_FAILURE_SETTLEMENT 必须且只能携带 persistedToolFailure"
        }
        // long: 验证失败结算只携带已经落库的失败验证边界；类型与载荷必须同步，避免 Repository 根据空载荷猜测验证 Step。
        require(
            (kind == AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT) ==
                (persistedToolVerificationFailure != null)
        ) {
            "PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT 必须且只能携带 persistedToolVerificationFailure"
        }
    }

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
            is AgentProfileAuditAssessment.Invalid -> return restartRequired(
                AgentRunRestartDispositionCode.PROFILE_EVIDENCE_INVALID,
                assessment.reason,
            )
        }
        val budgetAssessment = AgentExecutionBudgetEvidencePolicy.read(detail)
        if (budgetAssessment is AgentExecutionBudgetEvidenceAssessment.Invalid) {
            return restartRequired(AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID, budgetAssessment.reason)
        }
        if (snapshot.run.status == AgentRunStatus.WAITING_APPROVAL) {
            return assessApprovalWait(detail, agentProfile)
        }
        if (snapshot.run.status != AgentRunStatus.EXECUTING && snapshot.run.status != AgentRunStatus.VERIFYING) {
            return restartRequired(
                code = AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
                reason = "只有等待用户审批且尚未执行工具的 Run 可以原地恢复",
            )
        }
        if (snapshot.run.status == AgentRunStatus.EXECUTING) {
            val replayQualification = AgentNotCommittedReplayQualificationPolicy.assess(
                detail = detail,
                agentProfile = agentProfile,
                definitionLookup = definitionLookup,
            )
            if (replayQualification is AgentNotCommittedReplayQualificationAssessment.Eligible) {
                return restartRequired(
                    code = AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
                    reason = "链尾 ToolCall 已具备尚未提交的受控重放资格；当前阶段仍只收敛旧 Run，不执行重放",
                )
            }
            assessPersistedToolFailureSettlement(detail, agentProfile)?.let { return it }
        }
        assessPersistedToolVerificationFailureSettlement(detail, agentProfile)?.let { return it }
        assessVerifiedToolCompletion(detail, agentProfile)?.let { return it }
        return assessCommittedToolVerification(detail, agentProfile, definitionLookup, committedVerificationSupport)
    }

    private fun assessPersistedToolVerificationFailureSettlement(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
    ): AgentRunResumeAssessment? {
        if (detail.snapshot.run.status != AgentRunStatus.VERIFYING) return null
        val recoveryEvidence = when (val assessment = AgentRunRecoveryEvidencePolicy.read(detail)) {
            is AgentRunRecoveryEvidenceAssessment.Available -> assessment
            is AgentRunRecoveryEvidenceAssessment.CommitUnknown,
            is AgentRunRecoveryEvidenceAssessment.Invalid -> return null
        }
        val executions = recoveryEvidence.executions
        val failedVerification = executions.lastOrNull()?.takeIf {
            it.result.success && it.verificationStatus == ToolVerificationStatus.FAILED
        } ?: return null
        if (recoveryEvidence.source != AgentRunRecoveryEvidenceSource.LEDGER) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "失败工具验证原地收敛只接受完整独立 Tool Ledger",
            )
        }
        if (AgentExecutionBudgetEvidencePolicy.read(detail) !is AgentExecutionBudgetEvidenceAssessment.Available) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID,
                "失败工具验证缺少完整且位于结果之后的执行预算快照",
            )
        }
        if (detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }) {
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "失败工具验证已落库时不能同时保留待审批请求",
            )
        }
        if (agentProfile != null && executions.any { it.toolCall.name !in agentProfile.allowedToolNames }) {
            return restartRequired(
                AgentRunRestartDispositionCode.PROFILE_CAPABILITY_MISMATCH,
                "失败工具验证超出原 Agent Profile 白名单",
            )
        }
        if (executions.dropLast(1).any {
                !it.result.success || it.verificationStatus != ToolVerificationStatus.PASSED
            }
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "失败验证之前的工具没有形成完整成功验证前缀",
            )
        }

        val executionSteps = detail.snapshot.steps
            .filter { it.type == AgentStepTypes.TOOL_EXECUTE }
            .sortedBy { it.sequence }
        val verificationSteps = detail.snapshot.steps
            .filter { it.type == AgentStepTypes.TOOL_VERIFY }
            .sortedBy { it.sequence }
        if (
            executionSteps.size != executions.size ||
            verificationSteps.size != executions.size ||
            executionSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.dropLast(1).any { it.status != AgentStepStatus.COMPLETED } ||
            detail.snapshot.steps.map { it.sequence }.distinct().size != detail.snapshot.steps.size
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "失败验证的执行与验证步骤无法一一对应",
            )
        }
        executions.indices.forEach { index ->
            val executionStep = executionSteps[index]
            val verificationStep = verificationSteps[index]
            val nextExecutionStep = executionSteps.getOrNull(index + 1)
            if (
                executionStep.sequence >= verificationStep.sequence ||
                nextExecutionStep?.let { verificationStep.sequence >= it.sequence } == true ||
                !hasMatchingStepCreatedEvent(detail, executionStep) ||
                !hasMatchingStepCompletionEvent(detail, executionStep) ||
                !hasMatchingStepCreatedEvent(detail, verificationStep) ||
                (index < executions.lastIndex && !hasMatchingStepCompletionEvent(detail, verificationStep))
            ) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "失败验证的执行与验证步骤顺序或 typed 身份不一致",
                )
            }
        }
        val failedVerificationStep = verificationSteps.last()
        if (
            failedVerificationStep.status != AgentStepStatus.RUNNING ||
            hasStepStatusEvent(detail, failedVerificationStep) ||
            detail.snapshot.steps.maxByOrNull { it.sequence }?.id != failedVerificationStep.id
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "链尾失败验证必须对应最后一个仍在运行的验证步骤",
            )
        }
        val ledgerResult = detail.toolLedger.results.singleOrNull {
            it.toolCallId == failedVerification.toolCall.id
        } ?: return restartRequired(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            "链尾失败验证缺少唯一 ToolResult 账本记录",
        )
        val verificationEvent = detail.snapshot.events.singleOrNull { it.id == ledgerResult.verifiedEventId }
            ?: return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "链尾失败验证缺少唯一事件锚点",
            )
        val verificationMetadata = verificationEvent.metadata as? RunEventMetadata.ToolVerification
        val verificationReason = verificationMetadata?.reason?.takeIf { it.isNotBlank() }
            ?: return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "链尾失败验证缺少稳定失败原因",
            )
        val verificationEventIndex = detail.snapshot.events.indexOfFirst { it.id == verificationEvent.id }
        if (
            verificationMetadata.status != ToolVerificationStatus.FAILED ||
            verificationMetadata.toolCallId != failedVerification.toolCall.id ||
            verificationMetadata.toolName != failedVerification.toolCall.name ||
            verificationEventIndex < 0 ||
            detail.snapshot.events.drop(verificationEventIndex + 1).isNotEmpty()
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "失败工具验证之后不能存在额外控制面或业务事件",
            )
        }
        if (
            detail.snapshot.run.result != null ||
            detail.snapshot.run.errorMessage != null ||
            detail.snapshot.run.completedAt != null
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
                "验证中 Run 已混入终态结果，不能自动补齐失败收敛",
            )
        }

        val failureReason = "工具验证失败：$verificationReason"
        // long: 验证失败事实已经原子写入 Event 与 Tool Ledger；唯一安全动作是补齐失败 Step/Run，不能再次调用验证器、Executor 或模型。
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
            reason = "失败工具验证已完整持久化，只补齐原 Run 失败终态",
            persistedToolVerificationFailure = AgentPersistedToolVerificationFailureRecovery(
                toolCall = failedVerification.toolCall,
                verificationStepId = failedVerificationStep.id,
                failureReason = failureReason,
            ),
        )
    }

    private fun assessPersistedToolFailureSettlement(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
    ): AgentRunResumeAssessment? {
        val recoveryEvidence = when (val assessment = AgentRunRecoveryEvidencePolicy.read(detail)) {
            is AgentRunRecoveryEvidenceAssessment.Available -> assessment
            is AgentRunRecoveryEvidenceAssessment.CommitUnknown,
            is AgentRunRecoveryEvidenceAssessment.Invalid -> return null
        }
        val executions = recoveryEvidence.executions
        val failedExecution = executions.lastOrNull()?.takeIf { !it.result.success } ?: return null
        if (recoveryEvidence.source != AgentRunRecoveryEvidenceSource.LEDGER) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "失败工具结果原地收敛只接受完整独立 Tool Ledger",
            )
        }
        if (AgentExecutionBudgetEvidencePolicy.read(detail) !is AgentExecutionBudgetEvidenceAssessment.Available) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID,
                "失败 ToolResult 缺少完整且位于结果之后的执行预算快照",
            )
        }
        if (detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }) {
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "失败工具结果已落库时不能同时保留待审批请求",
            )
        }
        if (agentProfile != null && executions.any { it.toolCall.name !in agentProfile.allowedToolNames }) {
            return restartRequired(
                AgentRunRestartDispositionCode.PROFILE_CAPABILITY_MISMATCH,
                "失败工具结果超出原 Agent Profile 白名单",
            )
        }
        if (executions.dropLast(1).any {
                !it.result.success || it.verificationStatus != ToolVerificationStatus.PASSED
            }
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "失败链尾之前的工具没有形成完整成功验证前缀",
            )
        }
        if (failedExecution.verificationStatus != null || failedExecution.result.content.isBlank()) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "链尾失败 ToolResult 必须保留非空错误且尚未产生验证事实",
            )
        }

        val executionSteps = detail.snapshot.steps
            .filter { it.type == AgentStepTypes.TOOL_EXECUTE }
            .sortedBy { it.sequence }
        val verificationSteps = detail.snapshot.steps
            .filter { it.type == AgentStepTypes.TOOL_VERIFY }
            .sortedBy { it.sequence }
        if (
            executionSteps.size != executions.size ||
            executionSteps.dropLast(1).any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.size != executions.lastIndex ||
            verificationSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            detail.snapshot.steps.map { it.sequence }.distinct().size != detail.snapshot.steps.size
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "失败链尾的执行与前序验证步骤无法一一对应",
            )
        }
        executions.indices.forEach { index ->
            val executionStep = executionSteps[index]
            if (!hasMatchingStepCreatedEvent(detail, executionStep)) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "失败工具执行步骤缺少唯一且身份一致的创建事件",
                )
            }
            val verificationStep = verificationSteps.getOrNull(index)
            if (index < executions.lastIndex) {
                val nextExecutionStep = executionSteps[index + 1]
                if (
                    verificationStep == null ||
                    executionStep.sequence >= verificationStep.sequence ||
                    verificationStep.sequence >= nextExecutionStep.sequence ||
                    !hasMatchingStepCompletionEvent(detail, executionStep) ||
                    !hasMatchingStepCreatedEvent(detail, verificationStep) ||
                    !hasMatchingStepCompletionEvent(detail, verificationStep)
                ) {
                    return restartRequired(
                        AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                        "失败链尾之前的执行与验证步骤顺序或 typed 身份不一致",
                    )
                }
            } else if (hasStepStatusEvent(detail, executionStep)) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "链尾运行中执行步骤不应已有状态终结事件",
                )
            }
        }
        val failedExecutionStep = executionSteps.last()
        if (
            failedExecutionStep.status != AgentStepStatus.RUNNING ||
            detail.snapshot.steps.maxByOrNull { it.sequence }?.id != failedExecutionStep.id
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "链尾失败 ToolResult 必须对应最后一个仍在运行的执行步骤",
            )
        }
        val ledgerResult = detail.toolLedger.results.singleOrNull {
            it.toolCallId == failedExecution.toolCall.id
        } ?: return restartRequired(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            "链尾失败 ToolResult 缺少唯一账本记录",
        )
        val resultEventIndex = detail.snapshot.events.indexOfFirst { it.id == ledgerResult.eventId }
        if (resultEventIndex < 0) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "链尾失败 ToolResult 缺少唯一事件锚点",
            )
        }
        val trailingEvents = detail.snapshot.events.drop(resultEventIndex + 1)
        if (
            trailingEvents.size != 1 ||
            trailingEvents.single().type != AgentEventTypes.EXECUTION_BUDGET_UPDATED
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "失败 ToolResult 之后只能存在一次完整执行预算快照",
            )
        }
        if (
            detail.snapshot.run.result != null ||
            detail.snapshot.run.errorMessage != null ||
            detail.snapshot.run.completedAt != null
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
                "执行中 Run 已混入终态结果，不能自动补齐失败收敛",
            )
        }

        val failureReason = "工具执行失败：${failedExecution.result.content}"
        // long: 失败结果和预算都已持久化后，唯一安全动作与正常 Runtime catch 完全一致：只补失败 Step/Run，不调用工具、验证器或模型。
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT,
            reason = "失败工具结果与执行预算已完整持久化，只补齐原 Run 失败终态",
            persistedToolFailure = AgentPersistedToolFailureRecovery(
                toolCall = failedExecution.toolCall,
                executionStepId = failedExecutionStep.id,
                failureReason = failureReason,
            ),
        )
    }

    private fun hasMatchingStepCreatedEvent(
        detail: AgentRunDetailRecord,
        step: AgentStepRecord,
    ): Boolean {
        val candidates = detail.snapshot.events.mapNotNull { event ->
            if (event.type != AgentEventTypes.STEP_CREATED) return@mapNotNull null
            (event.metadata as? RunEventMetadata.StepCreated)?.takeIf {
                it.stepId == step.id || it.sequence == step.sequence
            }
        }
        val metadata = candidates.singleOrNull() ?: return false
        return metadata.stepId == step.id &&
            metadata.sequence == step.sequence &&
            metadata.stepType == step.type &&
            metadata.status == AgentStepStatus.RUNNING
    }

    private fun hasMatchingStepCompletionEvent(
        detail: AgentRunDetailRecord,
        step: AgentStepRecord,
    ): Boolean {
        val candidates = matchingStepStatusEvents(detail, step)
        val metadata = candidates.singleOrNull() ?: return false
        return metadata.stepId == step.id &&
            metadata.sequence == step.sequence &&
            metadata.fromStatus == AgentStepStatus.RUNNING &&
            metadata.toStatus == AgentStepStatus.COMPLETED
    }

    private fun hasStepStatusEvent(
        detail: AgentRunDetailRecord,
        step: AgentStepRecord,
    ): Boolean = matchingStepStatusEvents(detail, step).isNotEmpty()

    private fun matchingStepStatusEvents(
        detail: AgentRunDetailRecord,
        step: AgentStepRecord,
    ): List<RunEventMetadata.StepStatus> = detail.snapshot.events.mapNotNull { event ->
        if (event.type != AgentEventTypes.STEP_STATUS) return@mapNotNull null
        (event.metadata as? RunEventMetadata.StepStatus)?.takeIf {
            it.stepId == step.id || it.sequence == step.sequence
        }
    }

    private fun assessVerifiedToolCompletion(
        detail: AgentRunDetailRecord,
        agentProfile: AgentProfileSnapshot?,
    ): AgentRunResumeAssessment? {
        if (detail.snapshot.run.status != AgentRunStatus.VERIFYING) return null
        if (detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }) {
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "验证中 Run 不能同时保留待审批请求",
            )
        }
        val recoveryEvidence = when (val assessment = AgentRunRecoveryEvidencePolicy.read(detail)) {
            is AgentRunRecoveryEvidenceAssessment.Available -> assessment
            is AgentRunRecoveryEvidenceAssessment.CommitUnknown -> return restartRequired(
                AgentRunRestartDispositionCode.COMMIT_UNKNOWN,
                assessment.reason,
            )
            is AgentRunRecoveryEvidenceAssessment.Invalid -> return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                assessment.reason,
            )
        }
        val persistedExecutions = recoveryEvidence.executions
        if (persistedExecutions.isEmpty() || persistedExecutions.any {
                it.verificationStatus != ToolVerificationStatus.PASSED
            }
        ) {
            return null
        }
        if (agentProfile != null && persistedExecutions.any { it.toolCall.name !in agentProfile.allowedToolNames }) {
            return restartRequired(
                AgentRunRestartDispositionCode.PROFILE_CAPABILITY_MISMATCH,
                "已验证工具超出原 Agent Profile 白名单",
            )
        }
        val executionSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        if (
            executionSteps.size != persistedExecutions.size ||
            verificationSteps.size != persistedExecutions.size ||
            executionSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.dropLast(1).any { it.status != AgentStepStatus.COMPLETED }
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "已验证工具的执行与验证步骤无法一一对应",
            )
        }
        val lastVerificationStep = verificationSteps.last()
        if (lastVerificationStep.status != AgentStepStatus.RUNNING && lastVerificationStep.status != AgentStepStatus.COMPLETED) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "最后一个已验证工具步骤处于不可恢复终态",
            )
        }
        val trailingSteps = detail.snapshot.steps.filter { it.sequence > lastVerificationStep.sequence }
        val recoverySummaryStep = when {
            trailingSteps.isEmpty() -> null
            trailingSteps.size == 1 && trailingSteps.single().type == AgentStepTypes.RECOVERY_SUMMARIZE &&
                trailingSteps.single().status in setOf(AgentStepStatus.RUNNING, AgentStepStatus.COMPLETED) ->
                trailingSteps.single()
            else -> return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "最后一个已验证工具之后出现了非幂等的运行步骤",
            )
        }
        val recoverySummarySteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.RECOVERY_SUMMARIZE }
        if (recoverySummarySteps.size > 1 || recoverySummarySteps.singleOrNull()?.id != recoverySummaryStep?.id) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "恢复总结步骤不是唯一且连续的控制面收尾",
            )
        }
        val lastVerificationEventIndex = detail.snapshot.events.indexOfLast { it.type == AgentStepTypes.TOOL_VERIFY }
        if (lastVerificationEventIndex < 0) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "最后一个工具验证步骤缺少对应的验证事件",
            )
        }
        val trailingEvents = detail.snapshot.events.drop(lastVerificationEventIndex + 1)
        var trailingCursor = 0
        // long: tool.verify 事件先于验证 Step COMPLETED 落库；首个可选 step.status 只用于补齐这一个验证步骤，不能被当成恢复总结的状态事件。
        if (
            lastVerificationStep.status == AgentStepStatus.COMPLETED &&
            trailingEvents.getOrNull(trailingCursor)?.type == AgentEventTypes.STEP_STATUS
        ) {
            val metadata = trailingEvents[trailingCursor].metadata as? RunEventMetadata.StepStatus
            if (
                metadata?.stepId != lastVerificationStep.id ||
                metadata.sequence != lastVerificationStep.sequence ||
                metadata.fromStatus != AgentStepStatus.RUNNING ||
                metadata.toStatus != AgentStepStatus.COMPLETED
            ) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "最后一个工具验证步骤的状态事件身份不一致",
                )
            }
            trailingCursor += 1
        }
        if (trailingEvents.getOrNull(trailingCursor)?.type == "run.recovered") {
            val metadata = trailingEvents[trailingCursor].metadata as? RunEventMetadata.Recovery
            if (
                metadata?.resumeKind != AgentRunResumeKind.VERIFIED_TOOL_COMPLETION ||
                metadata.recoveryBoundaryKey !=
                "${AgentRunResumeKind.VERIFIED_TOOL_COMPLETION.name}:${lastVerificationStep.id}" ||
                metadata.fromStatus != AgentRunStatus.VERIFYING ||
                metadata.toStatus != AgentRunStatus.VERIFYING
            ) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "已验证工具恢复 marker 与当前验证边界不一致",
                )
            }
            trailingCursor += 1
        }
        if (recoverySummaryStep == null) {
            if (trailingCursor != trailingEvents.size) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "最后一个工具验证事实之后出现了非幂等业务事件",
                )
            }
        } else {
            if (trailingEvents.getOrNull(trailingCursor)?.type == AgentEventTypes.STEP_CREATED) {
                val metadata = trailingEvents[trailingCursor].metadata as? RunEventMetadata.StepCreated
                if (
                    metadata?.stepId != recoverySummaryStep.id ||
                    metadata.sequence != recoverySummaryStep.sequence ||
                    metadata.stepType != AgentStepTypes.RECOVERY_SUMMARIZE ||
                    metadata.status != AgentStepStatus.RUNNING
                ) {
                    return restartRequired(
                        AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                        "恢复总结步骤创建事件身份不一致",
                    )
                }
                trailingCursor += 1
            }
            val recoverySummaryEvent = trailingEvents.getOrNull(trailingCursor)
                ?.takeIf { it.type == AgentEventTypes.RECOVERY_SUMMARY }
            if (recoverySummaryEvent != null) {
                val metadata = recoverySummaryEvent.metadata as? RunEventMetadata.Reason
                if (metadata?.reason.isNullOrBlank()) {
                    return restartRequired(
                        AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                        "恢复总结事件缺少唯一、完整的 Reason 元数据或对应控制面步骤",
                    )
                }
                trailingCursor += 1
            }
            // long: Runtime 的持久化顺序固定为 RUNNING Step -> typed summary event -> COMPLETED Step；COMPLETED 但缺事件属于不可达状态，不能在恢复时补造。
            if (recoverySummaryStep.status == AgentStepStatus.COMPLETED && recoverySummaryEvent == null) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "恢复总结步骤已完成但总结事件缺失",
                )
            }
            if (
                recoverySummaryStep.status == AgentStepStatus.COMPLETED &&
                trailingEvents.getOrNull(trailingCursor)?.type == AgentEventTypes.STEP_STATUS
            ) {
                val metadata = trailingEvents[trailingCursor].metadata as? RunEventMetadata.StepStatus
                if (
                    metadata?.stepId != recoverySummaryStep.id ||
                    metadata.sequence != recoverySummaryStep.sequence ||
                    metadata.fromStatus != AgentStepStatus.RUNNING ||
                    metadata.toStatus != AgentStepStatus.COMPLETED
                ) {
                    return restartRequired(
                        AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                        "恢复总结步骤状态事件身份不一致",
                    )
                }
                trailingCursor += 1
            }
            if (trailingCursor != trailingEvents.size) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "恢复总结控制面事件顺序与步骤状态不一致",
                )
            }
        }
        val verifiedTools = persistedExecutions.mapIndexed { index, persisted ->
            val result = persisted.result
            if (!result.success || result.toolName != persisted.toolCall.name) {
                return restartRequired(
                    AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                    "已验证工具结果不是成功且身份一致的终态：${persisted.toolCall.id}",
                )
            }
            val verificationStep = verificationSteps[index]
            if (index < verificationSteps.lastIndex && verificationStep.status != AgentStepStatus.COMPLETED) {
                return restartRequired(
                    AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                    "前序工具验证步骤尚未完成：${persisted.toolCall.id}",
                )
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
                recoverySummaryStepId = recoverySummaryStep?.id,
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
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "Run 必须恰好存在一个待处理审批，不能恢复含糊的审批边界",
            )
        }
        val pendingApproval = pendingApprovals.single()
        if (agentProfile != null && pendingApproval.toolName !in agentProfile.allowedToolNames) {
            return restartRequired(
                AgentRunRestartDispositionCode.PROFILE_CAPABILITY_MISMATCH,
                "待审批工具超出原 Agent Profile 白名单",
            )
        }
        val evidence = when (val assessment = AgentRunRecoveryEvidencePolicy.readPendingApproval(detail)) {
            is AgentPendingApprovalRecoveryEvidenceAssessment.Available -> assessment.evidence
            is AgentPendingApprovalRecoveryEvidenceAssessment.Invalid -> return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                assessment.reason,
            )
        }
        val pendingToolCall = evidence.pendingToolCall
        if (
            pendingApproval.runId != snapshot.run.id ||
            pendingApproval.toolCallId != pendingToolCall.id ||
            pendingApproval.toolName != pendingToolCall.name ||
            pendingApproval.arguments != pendingToolCall.arguments ||
            pendingApproval.risk != pendingToolCall.risk
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "待审批请求与最后一个未执行 ToolCall 不一致",
            )
        }
        if (pendingToolCall.name == DeviceTypeTextAuditPolicy.TOOL_NAME) {
            return restartRequired(
                AgentRunRestartDispositionCode.EPHEMERAL_TOOL_INPUT_UNAVAILABLE,
                "设备文本输入原文未持久化，应用重启后不能继续旧审批，请创建新 Run 重新确认",
            )
        }
        val executionSteps = snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        if (
            executionSteps.size != evidence.verifiedPrefix.size ||
            verificationSteps.size != evidence.verifiedPrefix.size ||
            executionSteps.any { it.status != AgentStepStatus.COMPLETED } ||
            verificationSteps.any { it.status != AgentStepStatus.COMPLETED }
        ) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "前序工具步骤与已验证恢复证据不一致",
            )
        }
        val approvalSteps = snapshot.steps.filter { it.type == "approval" }
        val activeApprovalSteps = approvalSteps.filter { it.status == AgentStepStatus.RUNNING }
        if (activeApprovalSteps.size != 1 || approvalSteps.lastOrNull()?.id != activeApprovalSteps.single().id) {
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "无法唯一定位最后一个待审批步骤",
            )
        }
        if (snapshot.steps.any { it.sequence > activeApprovalSteps.single().sequence }) {
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "待审批步骤之后已经出现新的执行步骤",
            )
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
            return restartRequired(
                AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
                "执行或验证中 Run 不能同时保留待审批请求",
            )
        }
        val executionSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_EXECUTE }
        val verificationSteps = detail.snapshot.steps.filter { it.type == AgentStepTypes.TOOL_VERIFY }
        val recoveryEvidence = when (val assessment = AgentRunRecoveryEvidencePolicy.read(detail)) {
            is AgentRunRecoveryEvidenceAssessment.Available -> assessment
            is AgentRunRecoveryEvidenceAssessment.CommitUnknown -> return restartRequired(
                AgentRunRestartDispositionCode.COMMIT_UNKNOWN,
                assessment.reason,
            )
            is AgentRunRecoveryEvidenceAssessment.Invalid -> return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                assessment.reason,
            )
        }
        val persistedExecutions = recoveryEvidence.executions
        if (agentProfile != null && persistedExecutions.any { it.toolCall.name !in agentProfile.allowedToolNames }) {
            return restartRequired(
                AgentRunRestartDispositionCode.PROFILE_CAPABILITY_MISMATCH,
                "持久化工具结果超出原 Agent Profile 白名单",
            )
        }
        val passedVerificationCount = persistedExecutions.count {
            it.verificationStatus == ToolVerificationStatus.PASSED
        }
        if (executionSteps.isEmpty() || executionSteps.size != persistedExecutions.size) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "工具执行步骤与持久化结果无法一一对应",
            )
        }
        if (passedVerificationCount != persistedExecutions.size - 1) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "只能恢复最后一个已提交但尚未验证的工具结果",
            )
        }
        if (verificationSteps.size !in setOf(passedVerificationCount, persistedExecutions.size)) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "工具验证步骤与持久化验证事件不一致",
            )
        }
        val verifiedPrefix = mutableListOf<AgentToolExecution>()
        persistedExecutions.dropLast(1).forEachIndexed { index, persisted ->
            val result = persisted.result
            if (!result.success || executionSteps[index].status != AgentStepStatus.COMPLETED) {
                return restartRequired(
                    AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                    "前序工具结果不是已完成成功状态",
                )
            }
            val verificationStep = verificationSteps.getOrNull(index)
            if (
                verificationStep?.status != AgentStepStatus.COMPLETED ||
                persisted.verificationStatus != ToolVerificationStatus.PASSED ||
                persisted.toolCall.name != result.toolName
            ) {
                return restartRequired(
                    AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                    "前序工具结果缺少按顺序对应的成功验证",
                )
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
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "待恢复的工具执行步骤已进入不可恢复终态",
            )
        }
        val verificationStep = verificationSteps.getOrNull(persistedExecutions.lastIndex)
        if (verificationStep != null && verificationStep.status != AgentStepStatus.RUNNING) {
            return restartRequired(
                AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
                "待恢复的工具验证步骤不是运行中状态",
            )
        }
        val pendingVerification = persistedExecutions.last()
        if (pendingVerification.verificationStatus != null) {
            return restartRequired(
                AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                "最后一个工具结果已经存在验证终态，不能再次恢复验证",
            )
        }
        val persistedResult = pendingVerification.result
        val toolCall = pendingVerification.toolCall
        val definition = definitionLookup(toolCall.name)
            ?: return restartRequired(
                AgentRunRestartDispositionCode.TOOL_DEFINITION_UNAVAILABLE,
                "当前注册表中找不到历史工具定义",
            )
        // long: 先固定历史定义和提交回执是否可信，再查询只读回查能力；这样损坏证据不会被较宽泛的“不支持恢复验证”遮蔽。
        val replayEvidence = ToolExecutionRecoveryEvidencePolicy.assess(definition, persistedResult)
        if (!replayEvidence.canReuseCommittedEffect) {
            return restartRequired(
                AgentRunRestartDispositionCode.COMMITTED_EFFECT_EVIDENCE_INVALID,
                replayEvidence.reason,
            )
        }
        if (!committedVerificationSupport(toolCall.name)) {
            return restartRequired(
                AgentRunRestartDispositionCode.COMMITTED_VERIFICATION_UNAVAILABLE,
                "工具未开放已提交结果的只读恢复验证",
            )
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

    private fun restartRequired(
        code: AgentRunRestartDispositionCode,
        reason: String,
    ) = AgentRunResumeAssessment(
        kind = AgentRunResumeKind.RESTART_REQUIRED,
        reason = reason,
        // long: 旧执行协程已经随进程消失；结构化处置只描述可证明的恢复边界，不能被解释为允许重放旧工具或继续旧模型调用。
        restartDisposition = AgentRunRestartDisposition(
            code = code,
            reason = reason,
            evidenceBoundary = code.toEvidenceBoundary(),
            suggestedAction = "保留旧 Run 及其证据，按重试门禁确认后创建关联新 Run。",
        ),
    )

    private fun AgentRunRestartDispositionCode.toEvidenceBoundary(): String = when (this) {
        AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE ->
            "旧模型协程与旧 Executor 已丢失，当前 Run 状态不具备可证明的安全续跑位置。"
        AgentRunRestartDispositionCode.PROFILE_EVIDENCE_INVALID ->
            "原 Run 的 Agent Profile 快照无法唯一验证，不能使用当前配置替代历史执行边界。"
        AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID ->
            "执行预算快照不连续或不可信，不能证明原 Run 还剩多少安全执行时间。"
        AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID ->
            "审批请求、链尾 ToolCall 或步骤位置无法唯一对应，不能证明待执行动作尚未发生。"
        AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID ->
            "Tool Ledger、typed event 或验证结果不完整或不一致，不能证明历史副作用边界。"
        AgentRunRestartDispositionCode.COMMIT_UNKNOWN ->
            "ToolCall 已进入执行边界但没有持久化结果或提交回执，无法证明副作用未发生。"
        AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE ->
            "ToolCall、工具恢复契约、原 Profile 与用户审批证据一致，且持久化 TOOL_EXECUTE 步骤明确尚未出现。"
        AgentRunRestartDispositionCode.PROFILE_CAPABILITY_MISMATCH ->
            "历史工具超出原 Agent Profile 能力快照，当前配置不能为旧 Run 事后扩权。"
        AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID ->
            "执行步骤、验证步骤与持久化工具事实无法一一对应，不能定位可信续跑点。"
        AgentRunRestartDispositionCode.EPHEMERAL_TOOL_INPUT_UNAVAILABLE ->
            "文本输入原文只存在于发起审批的应用进程，进程重建后不能从指纹恢复待执行内容。"
        AgentRunRestartDispositionCode.COMMITTED_VERIFICATION_UNAVAILABLE ->
            "当前工具未提供已提交结果的只读回查能力，不能验证副作用是否仍然成立。"
        AgentRunRestartDispositionCode.TOOL_DEFINITION_UNAVAILABLE ->
            "当前 Registry 缺少历史工具定义，无法按历史重放声明审计已提交结果。"
        AgentRunRestartDispositionCode.COMMITTED_EFFECT_EVIDENCE_INVALID ->
            "提交回执、幂等键或重放声明不足，不能证明复用历史副作用是安全的。"
    }
}
