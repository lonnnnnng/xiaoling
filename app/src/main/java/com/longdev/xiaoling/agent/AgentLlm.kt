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

    /** long: 不支持输入捕获的旧模型保持原行为，不能由 Runtime 猜造完整 prompt；生产 OpenAI adapter 在发送前调用 capture。 */
    suspend fun proposeNextActionWithContext(
        goal: String,
        tools: List<ToolDefinition>,
        completedTools: List<AgentToolExecution>,
        capture: suspend (AgentContextInput) -> Unit,
    ): AgentLlmCallResult<AgentPlanDecision> = proposeNextActionWithTelemetry(goal, tools, completedTools)

    /** long: 新入口将派生来源与实际输入一并交给账本；旧模型仅保存原输入，不隐式启用压缩。 */
    suspend fun proposeNextActionWithCompression(
        goal: String,
        tools: List<ToolDefinition>,
        completedTools: List<AgentToolExecution>,
        capture: suspend (AgentContextInput, AgentContextCompressionCandidate?) -> Unit,
    ): AgentLlmCallResult<AgentPlanDecision> = proposeNextActionWithContext(goal, tools, completedTools) { capture(it, null) }

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

    suspend fun summarizeWithContext(
        goal: String,
        completedTools: List<AgentToolExecution>,
        capture: suspend (AgentContextInput) -> Unit,
    ): AgentLlmCallResult<String> = summarizeWithTelemetry(goal, completedTools)
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
