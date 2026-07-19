package com.longdev.xiaoling.agent

internal object AgentTaskRetryEvidencePolicy {
    fun hasSuccessfulOrUncertainSideEffect(detail: AgentRunDetailRecord): Boolean {
        return when (val consistency = AgentToolLedgerConsistencyPolicy.inspect(detail)) {
            AgentToolLedgerConsistencyAssessment.Empty -> readLegacyEvents(detail.snapshot.events)
            is AgentToolLedgerConsistencyAssessment.Invalid -> true
            is AgentToolLedgerConsistencyAssessment.Available -> consistency.executions.any { execution ->
                val result = execution.result ?: return@any false
                val receiptStatus = result.executionReceipt?.status
                // long: UNKNOWN 无法排除外部副作用已提交，必须与 COMMITTED 一样二次确认；明确 NOT_COMMITTED 且结果失败时才允许直接重试。
                execution.call.risk != ToolRisk.SAFE &&
                    (
                        result.success ||
                            receiptStatus == ToolExecutionReceiptStatus.COMMITTED ||
                            receiptStatus == ToolExecutionReceiptStatus.UNKNOWN
                        )
            }
        }
    }

    private fun readLegacyEvents(events: List<RunEventRecord>): Boolean {
        val successfulTools = events.mapNotNull { event ->
            (event.metadata as? RunEventMetadata.ToolResult)
                ?.takeIf { it.success }
                ?.toolName
        }.toSet()
        return events.any { event ->
            val call = event.metadata as? RunEventMetadata.ToolCall ?: return@any false
            call.risk != ToolRisk.SAFE && call.toolName in successfulTools
        }
    }
}
