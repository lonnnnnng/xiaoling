package com.longdev.xiaoling.agent

sealed interface AgentTaskRetryEligibility {
    data class Retryable(
        val requiresConfirmation: Boolean,
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
            AgentTaskRetryEligibility.Retryable(
                requiresConfirmation = assessEvidence(detail).requiresConfirmation,
            )
        } else {
            AgentTaskRetryEligibility.NotRetryable
        }
    }

    fun assessEvidence(detail: AgentRunDetailRecord): AgentTaskRetryEvidence {
        val recoverySnapshot = detail.snapshot.events.asReversed().firstNotNullOfOrNull { event ->
            (event.metadata as? RunEventMetadata.Recovery)
                ?.takeIf { it.toStatus == AgentRunStatus.CANCELLED }
        }
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
