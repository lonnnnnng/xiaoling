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
        // long: fake tool 只用于验证运行时闭环，因此当前自动批准；真实工具接入前必须把这里替换成交互式审批卡片。
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
