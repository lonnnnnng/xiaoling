package com.longdev.xiaoling.agent

internal object AgentTaskRetryEvidencePolicy {
    fun assess(
        detail: AgentRunDetailRecord,
        interruptedDuringSideEffect: Boolean,
    ): AgentTaskRetryEvidence {
        val code = when (val consistency = AgentToolLedgerConsistencyPolicy.inspect(detail)) {
            AgentToolLedgerConsistencyAssessment.Empty -> readLegacyEvents(
                detail.snapshot.events,
                interruptedDuringSideEffect,
            )
            is AgentToolLedgerConsistencyAssessment.Invalid -> AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE
            is AgentToolLedgerConsistencyAssessment.Available -> readLedger(
                consistency.executions,
                interruptedDuringSideEffect,
            )
        }
        return AgentTaskRetryEvidence(code)
    }

    private fun readLedger(
        executions: List<AgentToolLedgerExecutionRecord>,
        interruptedDuringSideEffect: Boolean,
    ): AgentTaskRetryEvidenceCode {
        val nonSafeExecutions = executions.filter { it.call.risk != ToolRisk.SAFE }
        if (nonSafeExecutions.isEmpty()) return AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT
        val codes = nonSafeExecutions.map { execution ->
            val result = execution.result ?: return@map if (interruptedDuringSideEffect) {
                AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN
            } else {
                AgentTaskRetryEvidenceCode.NOT_COMMITTED
            }
            when (result.executionReceipt?.status) {
                ToolExecutionReceiptStatus.UNKNOWN -> AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN
                ToolExecutionReceiptStatus.COMMITTED -> if (result.verificationStatus == ToolVerificationStatus.PASSED) {
                    AgentTaskRetryEvidenceCode.COMMITTED_VERIFIED
                } else {
                    AgentTaskRetryEvidenceCode.COMMITTED_UNVERIFIED
                }
                ToolExecutionReceiptStatus.NOT_COMMITTED -> if (result.success) {
                    AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE
                } else {
                    AgentTaskRetryEvidenceCode.NOT_COMMITTED
                }
                null -> if (result.success) {
                    AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN
                } else {
                    AgentTaskRetryEvidenceCode.NOT_COMMITTED
                }
            }
        }
        return codes.maxBy { it.confirmationPriority }
    }

    private fun readLegacyEvents(
        events: List<RunEventRecord>,
        interruptedDuringSideEffect: Boolean,
    ): AgentTaskRetryEvidenceCode {
        val successfulTools = events.mapNotNull { event ->
            (event.metadata as? RunEventMetadata.ToolResult)
                ?.takeIf { it.success }
                ?.toolName
        }.toSet()
        val hasSuccessfulNonSafeTool = events.any { event ->
            val call = event.metadata as? RunEventMetadata.ToolCall ?: return@any false
            call.risk != ToolRisk.SAFE && call.toolName in successfulTools
        }
        return when {
            hasSuccessfulNonSafeTool || interruptedDuringSideEffect -> AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN
            else -> AgentTaskRetryEvidenceCode.NOT_COMMITTED
        }
    }

    private val AgentTaskRetryEvidenceCode.confirmationPriority: Int
        get() = when (this) {
            AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE -> 5
            AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN -> 4
            AgentTaskRetryEvidenceCode.COMMITTED_UNVERIFIED -> 3
            AgentTaskRetryEvidenceCode.COMMITTED_VERIFIED -> 2
            AgentTaskRetryEvidenceCode.NOT_COMMITTED -> 1
            AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT -> 0
        }
}
