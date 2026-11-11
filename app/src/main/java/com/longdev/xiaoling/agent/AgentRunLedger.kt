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

    /** long: 完成必须确认真实落库，取消栅栏拒绝写入时不能向 Workflow 返回成功并继续后续步骤。 */
    suspend fun completeRun(runId: String, result: String): Boolean {
        updateRunStatus(runId, AgentRunStatus.COMPLETED, result = result)
        return snapshot(runId).run.let { it.status == AgentRunStatus.COMPLETED && it.result == result }
    }
    suspend fun appendStep(runId: String, type: String, title: String, detail: String, status: AgentStepStatus): AgentStepRecord
    suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String? = null)
    suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata? = null)
    suspend fun snapshot(runId: String): AgentRunSnapshot

    /** long: 停止入口与执行协程可以不在同一页面，等待模型或审批时也必须读取同一 Run 的持久取消意图。 */
    suspend fun isCancellationRequested(runId: String): Boolean = snapshot(runId).run.let {
        it.cancelRequestedAt != null || it.status == AgentRunStatus.CANCELLED
    }

    /** long: 捕获模型输入的 Ledger 必须真正接收快照，缺少实现时拒绝发送，不能静默丢失输入序列。 */
    suspend fun appendContextSnapshot(runId: String, stepId: String, input: AgentContextInput) {
        error("当前 Ledger 不支持上下文快照")
    }

    suspend fun contextTranscript(runId: String): List<AgentContextSnapshotRecord> = emptyList()

    suspend fun appendCompressedContextSnapshot(runId: String, stepId: String, input: AgentContextInput,
        candidate: AgentContextCompressionCandidate) {
        error("当前 Ledger 不支持压缩来源与快照共事务保存")
    }

    suspend fun contextCompressions(runId: String): List<AgentContextCompressionRecord> = emptyList()
}
