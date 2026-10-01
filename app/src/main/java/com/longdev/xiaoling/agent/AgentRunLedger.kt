package com.longdev.xiaoling.agent

interface AgentRunLedger {
    suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String? = null,
    ): AgentRunRecord

    /**
     * long: 派生 Run 需要把父 Run 关系写进同一条 Room 账本；旧 Ledger 实现暂时走四参数兼容路径，避免恢复历史夹具时丢失默认行为。
     */
    suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
        parentRunId: String?,
    ): AgentRunRecord = createRun(conversationId, userMessageId, goal, retryOfRunId)

    suspend fun updateRunStatus(runId: String, status: AgentRunStatus, result: String? = null, errorMessage: String? = null)
    suspend fun appendStep(runId: String, type: String, title: String, detail: String, status: AgentStepStatus): AgentStepRecord
    suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String? = null)
    suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata? = null)
    suspend fun snapshot(runId: String): AgentRunSnapshot
}
