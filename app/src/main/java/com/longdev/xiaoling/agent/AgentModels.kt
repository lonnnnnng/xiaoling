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
)

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
