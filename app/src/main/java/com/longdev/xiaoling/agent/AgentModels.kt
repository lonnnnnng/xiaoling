package com.longdev.xiaoling.agent

import java.util.UUID

enum class AgentRunStatus {
    QUEUED,
    THINKING,
    WAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
}

enum class AgentStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AgentRunRecord(
    val id: String,
    val conversationId: String,
    val userMessageId: String,
    val goal: String,
    val status: AgentRunStatus,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

data class AgentStepRecord(
    val id: String,
    val runId: String,
    val sequence: Int,
    val type: String,
    val status: AgentStepStatus,
    val title: String,
    val detail: String,
    val createdAt: Long,
    val completedAt: Long?,
)

data class RunEventRecord(
    val id: String,
    val runId: String,
    val type: String,
    val message: String,
    val createdAt: Long,
)

data class AgentRunSnapshot(
    val run: AgentRunRecord,
    val steps: List<AgentStepRecord>,
    val events: List<RunEventRecord>,
)

enum class ApprovalRequestStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    CANCELLED,
}

data class ApprovalRequestRecord(
    val id: String,
    val runId: String,
    val conversationId: String,
    val toolCallId: String,
    val toolName: String,
    val toolDescription: String,
    val risk: ToolRisk,
    val arguments: Map<String, String>,
    val status: ApprovalRequestStatus,
    val decisionReason: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val decidedAt: Long?,
)

data class AgentRunDetailRecord(
    val snapshot: AgentRunSnapshot,
    val approvals: List<ApprovalRequestRecord>,
)

data class ToolCall(
    val id: String = "tool-call-${UUID.randomUUID()}",
    val name: String,
    val arguments: Map<String, String>,
    val risk: ToolRisk,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val inputSchema: List<ToolInputField> = emptyList(),
    val timeoutMs: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "工具名称不能为空" }
        require(timeoutMs == null || timeoutMs > 0) { "工具超时时间必须大于 0" }
    }
}

data class ToolInputField(
    val name: String,
    val description: String,
    val required: Boolean,
) {
    init {
        require(name.isNotBlank()) { "工具参数名称不能为空" }
    }
}

enum class ToolRisk {
    SAFE,
    REQUIRES_APPROVAL,
    DANGEROUS,
}

data class ToolExecutionResult(
    val success: Boolean,
    val content: String,
)

data class ApprovalDecision(
    val approved: Boolean,
    val reason: String,
)

data class AgentRunSummary(
    val runId: String,
    val status: AgentRunStatus,
    val responseText: String,
)

data class AgentRuntimeOptions(
    val maxToolCalls: Int = 4,
    val runTimeoutMs: Long = 120_000,
    val modelStepTimeoutMs: Long = 60_000,
    val toolStepTimeoutMs: Long = 30_000,
) {
    init {
        require(maxToolCalls >= 0) { "工具调用上限不能小于 0" }
        require(runTimeoutMs > 0) { "Agent Run 超时时间必须大于 0" }
        require(modelStepTimeoutMs > 0) { "模型步骤超时时间必须大于 0" }
        require(toolStepTimeoutMs > 0) { "工具步骤超时时间必须大于 0" }
    }
}

class AgentBudgetExceededException(message: String) : RuntimeException(message)

class AgentTimeoutException(message: String) : RuntimeException(message)
