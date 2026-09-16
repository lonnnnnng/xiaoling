package com.longdev.xiaoling.agent

sealed interface AgentTaskRetryEligibility {
    data class Retryable(
        val requiresConfirmation: Boolean,
    ) : AgentTaskRetryEligibility

    data class ConfigurationRequired(
        val reason: String,
    ) : AgentTaskRetryEligibility

    data object NotRetryable : AgentTaskRetryEligibility
}

enum class AgentTaskRetryEvidenceCode {
    NO_SIDE_EFFECT,
    NOT_COMMITTED,
    COMMIT_UNKNOWN,
    COMMITTED_UNVERIFIED,
    COMMITTED_VERIFIED,
    EVIDENCE_INCOMPLETE,
}

data class AgentTaskRetryEvidence(
    val code: AgentTaskRetryEvidenceCode,
    val fingerprint: String = "",
) {
    val requiresConfirmation: Boolean
        get() = code !in setOf(
            AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT,
            AgentTaskRetryEvidenceCode.NOT_COMMITTED,
        )
}

object AgentTaskRetryPolicy {
    fun evaluate(detail: AgentRunDetailRecord): AgentTaskRetryEligibility {
        // long: 只有已明确结束且没有成功结果的 Run 才能重新运行；处理中或已完成 Run 禁止重试，避免同一目标被并发执行或重复产生结果。
        return if (detail.snapshot.run.status in retryableStatuses) {
            if (hasConfigurationRequired(detail)) {
                return AgentTaskRetryEligibility.ConfigurationRequired("模型请求失败，需要先修复 Provider、地址或模型配置")
            }
            AgentTaskRetryEligibility.Retryable(
                requiresConfirmation = assessEvidence(detail).requiresConfirmation ||
                    hasRestartDisposition(detail) ||
                    hasRecoveryFailure(detail) ||
                    hasLlmRetryConfirmation(detail),
            )
        } else {
            AgentTaskRetryEligibility.NotRetryable
        }
    }

    private fun hasConfigurationRequired(detail: AgentRunDetailRecord): Boolean {
        // long: 配置错误不会因重复提交而消失；任务中心隐藏重试入口，避免用户在同一错误配置下反复创建新 Run。
        return latestPlanningLlmFailure(detail)?.retryDisposition == AgentLlmRetryDisposition.CONFIGURATION_REQUIRED
    }

    internal fun latestPlanningLlmFailure(detail: AgentRunDetailRecord): RunEventMetadata.LlmFailure? {
        return detail.snapshot.events.asReversed().firstNotNullOfOrNull { event ->
            if (event.type != AgentEventTypes.LLM_REQUEST_FAILED) return@firstNotNullOfOrNull null
            (event.metadata as? RunEventMetadata.LlmFailure)
                ?.takeIf { it.phase == AgentLlmPhase.PLAN }
        }
    }

    private fun hasRestartDisposition(detail: AgentRunDetailRecord): Boolean {
        // long: 只要启动恢复已冻结结构化处置，后续就必须再经一次用户确认创建关联新 Run；“明确未提交”只能降低副作用风险，不能把旧 Run 的启动处置变成直接续跑授权。
        return detail.latestRecoveryMetadata()?.restartDisposition != null
    }

    private fun hasRecoveryFailure(detail: AgentRunDetailRecord): Boolean {
        // long: 设备观察过期、引用失效等失败没有外部副作用，但旧审批也不能继续使用；任务中心必须要求用户重新观察并确认后创建关联新 Run。
        return detail.snapshot.events.any { event ->
            event.type == AgentEventTypes.RECOVERY_FAILED &&
                event.metadata is RunEventMetadata.RecoveryFailure
        }
    }

    private fun hasLlmRetryConfirmation(detail: AgentRunDetailRecord): Boolean {
        // long: 规划阶段的模型失败处置是独立审计证据；响应歧义和配置问题不能因为没有 ToolResult 就自动进入新 Run。
        return latestPlanningLlmFailure(detail)?.retryDisposition in setOf(
            AgentLlmRetryDisposition.RETRY_WITH_CONFIRMATION,
            AgentLlmRetryDisposition.CONFIGURATION_REQUIRED,
        )
    }

    fun assessEvidence(detail: AgentRunDetailRecord): AgentTaskRetryEvidence {
        val recoverySnapshot = detail.latestRecoveryMetadata()
            ?.takeIf { it.toStatus == AgentRunStatus.CANCELLED }
        val persistedAtRecovery = recoverySnapshot?.retryEvidenceCode
        val current = assessCurrentEvidence(detail, recoverySnapshot)
        // long: 启动收敛会冻结当时的副作用分类和证据指纹；后续账本即使分类不变，只要身份或内容漂移也必须升级为证据不完整。
        val currentFingerprint = AgentTaskRetryEvidenceFingerprint.calculate(detail)
        return if (persistedAtRecovery != null &&
            (recoverySnapshot.retryEvidenceFingerprint == null ||
                recoverySnapshot.retryEvidenceFingerprint != currentFingerprint ||
                persistedAtRecovery != current.code)
        ) {
            AgentTaskRetryEvidence(
                code = AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE,
                fingerprint = currentFingerprint,
            )
        } else {
            current.copy(fingerprint = currentFingerprint)
        }
    }

    internal fun assessEvidenceBeforeRecovery(
        detail: AgentRunDetailRecord,
        fromStatus: AgentRunStatus,
    ): AgentTaskRetryEvidence {
        val interruptedDuringSideEffect = fromStatus in sideEffectStatuses || detail.snapshot.steps.any { step ->
            step.type in uncertainToolStepTypes && step.status == AgentStepStatus.RUNNING
        }
        // long: 证据必须在步骤被改成 CANCELLED 前计算，否则会失去“中断发生在哪个执行阶段”的原始边界。
        return AgentTaskRetryEvidencePolicy.assess(detail, interruptedDuringSideEffect)
            .copy(fingerprint = AgentTaskRetryEvidenceFingerprint.calculate(detail))
    }

    private fun assessCurrentEvidence(
        detail: AgentRunDetailRecord,
        recoverySnapshot: RunEventMetadata.Recovery?,
    ): AgentTaskRetryEvidence {
        val interruptedDuringSideEffect = if (recoverySnapshot?.retryEvidenceCode != null) {
            // long: 启动收敛把原本 PENDING 的步骤也写成 CANCELLED；有证据快照时只能沿用收敛前边界，避免把清理动作误判成副作用中断。
            recoverySnapshot.fromStatus in sideEffectStatuses ||
                recoverySnapshot.retryEvidenceCode in uncertainEvidenceCodes
        } else {
            detail.snapshot.events.any { event ->
                if (event.type != "run.recovered") return@any false
                val recovery = event.metadata as? RunEventMetadata.Recovery ?: return@any false
                recovery.fromStatus in sideEffectStatuses
            } || detail.snapshot.steps.any { step ->
                step.type in uncertainToolStepTypes && step.status in interruptedStepStatuses
            }
        }
        // long: 重试前把副作用证据固定成稳定枚举，任务卡和确认弹窗共享同一结论，避免 UI 自己猜测 UNKNOWN/COMMITTED 边界。
        return AgentTaskRetryEvidencePolicy.assess(detail, interruptedDuringSideEffect)
            .copy(fingerprint = AgentTaskRetryEvidenceFingerprint.calculate(detail))
    }

    fun canConfirmRetry(
        expectedEvidenceCode: AgentTaskRetryEvidenceCode,
        detail: AgentRunDetailRecord,
        expectedEvidenceFingerprint: String? = null,
    ): Boolean {
        if (detail.snapshot.run.status !in retryableStatuses) return false
        val current = assessEvidence(detail)
        return current.code == expectedEvidenceCode &&
            (expectedEvidenceFingerprint == null ||
                current.fingerprint == expectedEvidenceFingerprint)
    }

    private val retryableStatuses = setOf(
        AgentRunStatus.BLOCKED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.BUDGET_EXHAUSTED,
    )

    private val uncertainToolStepTypes = setOf(AgentStepTypes.TOOL_EXECUTE, AgentStepTypes.TOOL_VERIFY)
    private val interruptedStepStatuses = setOf(AgentStepStatus.FAILED, AgentStepStatus.CANCELLED)
    private val sideEffectStatuses = setOf(AgentRunStatus.EXECUTING, AgentRunStatus.VERIFYING)
    private val uncertainEvidenceCodes = setOf(
        AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
        AgentTaskRetryEvidenceCode.COMMITTED_UNVERIFIED,
        AgentTaskRetryEvidenceCode.COMMITTED_VERIFIED,
        AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE,
    )

}
