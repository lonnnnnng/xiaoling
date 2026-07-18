package com.longdev.xiaoling.agent

data class AgentLlmRequestTelemetry(
    val model: String,
    val latencyMs: Long,
    val firstByteLatencyMs: Long?,
    val promptBytes: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

data class AgentLlmCallResult<out T>(
    val value: T,
    val telemetry: AgentLlmRequestTelemetry?,
)

class AgentLlmResponseException(
    message: String,
    cause: Throwable,
    val telemetry: AgentLlmRequestTelemetry,
) : IllegalStateException(message, cause)

interface AgentLlm {
    suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall

    suspend fun proposeNextAction(
        goal: String,
        tools: List<ToolDefinition>,
        completedTools: List<AgentToolExecution>,
    ): AgentPlanDecision {
        return if (completedTools.isEmpty()) {
            AgentPlanDecision.CallTool(proposeToolCall(goal, tools))
        } else {
            AgentPlanDecision.Complete
        }
    }

    suspend fun proposeNextActionWithTelemetry(
        goal: String,
        tools: List<ToolDefinition>,
        completedTools: List<AgentToolExecution>,
    ): AgentLlmCallResult<AgentPlanDecision> = AgentLlmCallResult(
        value = proposeNextAction(goal, tools, completedTools),
        telemetry = null,
    )

    suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String

    suspend fun summarize(goal: String, completedTools: List<AgentToolExecution>): String {
        val completed = completedTools.lastOrNull() ?: error("没有已完成工具，无法生成 Agent 总结")
        return summarize(goal, completed.toolCall, completed.toolResult)
    }

    suspend fun summarizeWithTelemetry(
        goal: String,
        completedTools: List<AgentToolExecution>,
    ): AgentLlmCallResult<String> = AgentLlmCallResult(
        value = summarize(goal, completedTools),
        telemetry = null,
    )
}

interface ApprovalGate {
    suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision
}

class AutoApprovalGate : ApprovalGate {
    override suspend fun requestApproval(
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): ApprovalDecision {
        // long: 自动审批只用于单元测试和非 UI 场景；生产对话链路会注入交互式审批 gate，写入类工具必须由用户明确确认。
        return ApprovalDecision(
            approved = definition.risk != ToolRisk.DANGEROUS,
            reason = if (definition.risk == ToolRisk.DANGEROUS) {
                "危险工具不能自动批准"
            } else {
                "演示工具自动批准"
            },
        )
    }
}
