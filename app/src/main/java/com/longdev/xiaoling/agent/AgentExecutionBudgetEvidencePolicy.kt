package com.longdev.xiaoling.agent

data class AgentExecutionBudgetSnapshot(
    val totalTimeoutMs: Long,
    val consumedMs: Long,
)

sealed interface AgentExecutionBudgetEvidenceAssessment {
    data object Legacy : AgentExecutionBudgetEvidenceAssessment

    data class Available(
        val snapshot: AgentExecutionBudgetSnapshot,
    ) : AgentExecutionBudgetEvidenceAssessment

    data class Invalid(
        val reason: String,
    ) : AgentExecutionBudgetEvidenceAssessment
}

object AgentExecutionBudgetEvidencePolicy {
    fun read(detail: AgentRunDetailRecord): AgentExecutionBudgetEvidenceAssessment {
        val budgetEvents = detail.snapshot.events.filter { event ->
            event.type == AgentEventTypes.EXECUTION_BUDGET_UPDATED
        }
        if (budgetEvents.isEmpty()) return AgentExecutionBudgetEvidenceAssessment.Legacy

        val snapshots = budgetEvents.map { event ->
            val metadata = event.metadata as? RunEventMetadata.ExecutionBudget
                ?: return AgentExecutionBudgetEvidenceAssessment.Invalid("执行预算事件缺少结构化快照")
            if (
                metadata.totalTimeoutMs <= 0 ||
                metadata.consumedMs < 0 ||
                metadata.consumedMs > metadata.totalTimeoutMs
            ) {
                return AgentExecutionBudgetEvidenceAssessment.Invalid("执行预算快照超出合法范围")
            }
            AgentExecutionBudgetSnapshot(
                totalTimeoutMs = metadata.totalTimeoutMs,
                consumedMs = metadata.consumedMs,
            )
        }
        if (snapshots.first().consumedMs != 0L) {
            return AgentExecutionBudgetEvidenceAssessment.Invalid("执行预算快照缺少初始零值")
        }
        if (snapshots.any { it.totalTimeoutMs != snapshots.first().totalTimeoutMs }) {
            return AgentExecutionBudgetEvidenceAssessment.Invalid("执行预算总额在同一 Run 内发生漂移")
        }
        if (snapshots.zipWithNext().any { (previous, current) -> current.consumedMs < previous.consumedMs }) {
            return AgentExecutionBudgetEvidenceAssessment.Invalid("执行预算累计值发生回退")
        }
        val lastBudgetEventIndex = detail.snapshot.events.indexOfLast { event ->
            event.type == AgentEventTypes.EXECUTION_BUDGET_UPDATED
        }
        val lastToolResultIndex = detail.snapshot.events.indexOfLast { event ->
            event.type == "tool.result"
        }
        if (lastToolResultIndex > lastBudgetEventIndex) {
            // long: 工具副作用与 ToolResult 已提交、预算事件尚未跟上时可能发生进程终止；恢复不能凭旧快照返还刚消耗的工具时间。
            return AgentExecutionBudgetEvidenceAssessment.Invalid("工具结果缺少后续执行预算快照")
        }

        // long: 恢复只信任 Runtime 逐段写入的累计快照；不能把 Step 墙上时间或可选模型 telemetry 拼成看似精确的执行预算。
        return AgentExecutionBudgetEvidenceAssessment.Available(snapshots.last())
    }
}
