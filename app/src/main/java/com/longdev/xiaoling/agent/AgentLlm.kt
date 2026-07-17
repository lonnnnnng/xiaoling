package com.longdev.xiaoling.agent

interface AgentLlm {
    suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall
    suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String
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
