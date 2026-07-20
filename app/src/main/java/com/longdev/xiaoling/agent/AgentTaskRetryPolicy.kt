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
        val interruptedDuringSideEffect = detail.snapshot.events.any { event ->
            val recovery = event.metadata as? RunEventMetadata.Recovery ?: return@any false
            recovery.fromStatus == AgentRunStatus.EXECUTING || recovery.fromStatus == AgentRunStatus.VERIFYING
        } || detail.snapshot.steps.any { step ->
            step.type in uncertainToolStepTypes && step.status in interruptedStepStatuses
        }
        // long: 重试前把副作用证据固定成稳定枚举，任务卡和确认弹窗共享同一结论，避免 UI 自己猜测 UNKNOWN/COMMITTED 边界。
        return AgentTaskRetryEvidencePolicy.assess(detail, interruptedDuringSideEffect)
    }

    fun canConfirmRetry(
        expectedEvidenceCode: AgentTaskRetryEvidenceCode,
        detail: AgentRunDetailRecord,
    ): Boolean {
        val eligibility = evaluate(detail)
        return eligibility is AgentTaskRetryEligibility.Retryable &&
            assessEvidence(detail).code == expectedEvidenceCode
    }

    private val retryableStatuses = setOf(
        AgentRunStatus.BLOCKED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.BUDGET_EXHAUSTED,
    )

    private val uncertainToolStepTypes = setOf(AgentStepTypes.TOOL_EXECUTE, AgentStepTypes.TOOL_VERIFY)
    private val interruptedStepStatuses = setOf(AgentStepStatus.FAILED, AgentStepStatus.CANCELLED)
}
