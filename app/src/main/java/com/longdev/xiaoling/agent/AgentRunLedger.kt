package com.longdev.xiaoling.agent

interface AgentRunLedger {
    suspend fun createRun(conversationId: String, userMessageId: String, goal: String): AgentRunRecord
    suspend fun updateRunStatus(runId: String, status: AgentRunStatus, result: String? = null, errorMessage: String? = null)
    suspend fun appendStep(runId: String, type: String, title: String, detail: String, status: AgentStepStatus): AgentStepRecord
    suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String? = null)
    suspend fun appendEvent(runId: String, type: String, message: String)
    suspend fun snapshot(runId: String): AgentRunSnapshot
}
