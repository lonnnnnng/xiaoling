package com.longdev.xiaoling.agent

sealed interface AgentTaskRetryEligibility {
    data class Retryable(
        val requiresConfirmation: Boolean,
    ) : AgentTaskRetryEligibility

    data object NotRetryable : AgentTaskRetryEligibility
}

object AgentTaskRetryPolicy {
    fun evaluate(detail: AgentRunDetailRecord): AgentTaskRetryEligibility {
        // long: 只有已明确结束且没有成功结果的 Run 才能重新运行；处理中或已完成 Run 禁止重试，避免同一目标被并发执行或重复产生结果。
        return if (detail.snapshot.run.status in retryableStatuses) {
            val successfulTools = detail.snapshot.events.mapNotNull { event ->
                (event.metadata as? RunEventMetadata.ToolResult)
                    ?.takeIf { it.success }
                    ?.toolName
            }.toSet()
            val successfulSideEffect = detail.snapshot.events.any { event ->
                val call = event.metadata as? RunEventMetadata.ToolCall ?: return@any false
                call.risk != ToolRisk.SAFE && call.toolName in successfulTools
            }
            val interruptedDuringSideEffect = detail.snapshot.events.any { event ->
                val recovery = event.metadata as? RunEventMetadata.Recovery ?: return@any false
                recovery.fromStatus == AgentRunStatus.EXECUTING || recovery.fromStatus == AgentRunStatus.VERIFYING
            }
            val interruptedToolStep = detail.snapshot.steps.any { step ->
                step.type in uncertainToolStepTypes && step.status in interruptedStepStatuses
            }
            // long: 非 SAFE 工具可能已经产生外部变化，执行/验证阶段中断也无法确认动作是否完成；这两类重试必须由用户再次确认，不能把不确定副作用静默执行第二次。
            AgentTaskRetryEligibility.Retryable(
                requiresConfirmation = successfulSideEffect || interruptedDuringSideEffect || interruptedToolStep,
            )
        } else {
            AgentTaskRetryEligibility.NotRetryable
        }
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
