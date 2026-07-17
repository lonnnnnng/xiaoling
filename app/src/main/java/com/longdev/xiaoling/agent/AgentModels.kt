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

object AgentStepTypes {
    const val TOOL_EXECUTE = "tool.execute"
    const val TOOL_VERIFY = "tool.verify"
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
    val retryOfRunId: String? = null,
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
    val metadata: RunEventMetadata? = null,
)

sealed interface RunEventMetadata {
    data class ToolCall(
        val id: String,
        val toolName: String,
        val risk: ToolRisk,
        val arguments: Map<String, String>,
    ) : RunEventMetadata

    data class ToolResult(
        val toolName: String,
        val content: String,
        val durationMs: Long,
        val success: Boolean,
        val verified: Boolean?,
    ) : RunEventMetadata

    data class ApprovalRequest(
        val id: String,
        val toolName: String,
        val risk: ToolRisk,
        val arguments: Map<String, String>,
        val status: ApprovalRequestStatus,
        val expiresAt: Long,
        val reason: String?,
    ) : RunEventMetadata

    data class ApprovalDecision(
        val toolName: String,
        val approved: Boolean,
        val reason: String,
    ) : RunEventMetadata

    data class ApprovalSkipped(
        val toolName: String,
        val reason: String,
    ) : RunEventMetadata

    data class ToolVerification(
        val toolName: String,
        val status: ToolVerificationStatus,
    ) : RunEventMetadata

    data class Reason(
        val reason: String,
    ) : RunEventMetadata

    data class Recovery(
        val fromStatus: AgentRunStatus,
        val toStatus: AgentRunStatus,
        val reason: String,
    ) : RunEventMetadata
}

enum class ToolVerificationStatus {
    PASSED,
}

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

// long: 当前交互审批由用户明确批准、拒绝或任务取消来收敛，不按固定倒计时自动过期；保留 expiresAt 字段时用该哨兵值表达“无主动过期”。
const val APPROVAL_REQUEST_NO_EXPIRY_AT: Long = Long.MAX_VALUE

fun Long.isApprovalRequestWithoutActiveExpiry(): Boolean = this == APPROVAL_REQUEST_NO_EXPIRY_AT

fun Long.toApprovalExpiryPolicyLabel(): String {
    return if (isApprovalRequestWithoutActiveExpiry()) {
        "无主动过期"
    } else {
        toString()
    }
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

fun ApprovalRequestRecord.isWaitingForInteractiveApprovalDecision(): Boolean {
    // long: 交互审批的待处理状态只看用户是否已经做出决定，不用 expiresAt 推导过期；这样旧数据或未来策略字段变化不会在读取列表时偷偷改写用户审批结果。
    return status == ApprovalRequestStatus.PENDING
}

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
    val verified: Boolean? = null,
)

interface AgentClock {
    fun nowMillis(): Long
    fun formattedNow(): String
    fun zoneId(): String
}

data class AgentMemoryRecord(
    val id: String,
    val content: String,
    val tags: String,
    val type: String,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val sourceSummary: String,
    val confidence: Double,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

data class AgentMemorySource(
    val conversationId: String?,
    val runId: String?,
    val summary: String,
)

interface AgentMemoryStore {
    suspend fun remember(content: String, tags: String, type: String, source: AgentMemorySource, confidence: Double): AgentMemoryRecord
    suspend fun search(query: String, limit: Int, enabledOnly: Boolean = true): List<AgentMemoryRecord>
}

enum class AgentMemoryFilter {
    ALL,
    ENABLED,
    DISABLED,
}

data class AgentMemoryUpdate(
    val content: String,
    val tags: String,
    val type: String,
    val confidence: Double,
)

interface AgentMemoryManager {
    suspend fun list(query: String, filter: AgentMemoryFilter, limit: Int = 100): List<AgentMemoryRecord>
    suspend fun update(memoryId: String, update: AgentMemoryUpdate): AgentMemoryRecord?
    suspend fun setEnabled(memoryId: String, enabled: Boolean): AgentMemoryRecord?
    suspend fun setPinned(memoryId: String, pinned: Boolean): AgentMemoryRecord?
    suspend fun delete(memoryId: String): Boolean
}

data class AgentConversationRecord(
    val id: String,
    val title: String,
    val summary: String,
    val messageCount: Int,
    val updatedAt: Long,
)

interface AgentConversationStore {
    suspend fun list(limit: Int): List<AgentConversationRecord>
    suspend fun search(query: String, limit: Int): List<AgentConversationRecord>
}

data class AgentNoteRecord(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

interface AgentNoteStore {
    suspend fun list(limit: Int): List<AgentNoteRecord>
    suspend fun search(query: String, limit: Int): List<AgentNoteRecord>
    suspend fun create(title: String, content: String): AgentNoteRecord
    suspend fun get(id: String): AgentNoteRecord?
}

data class AgentToolExecutionContext(
    val conversationId: String,
    val userMessageId: String,
    val runId: String,
    val goal: String,
)

interface AgentRunContextAwareToolRegistry {
    fun bindRunContext(context: AgentToolExecutionContext)
}

data class ApprovalDecision(
    val approved: Boolean,
    val reason: String,
)

data class AgentRunSummary(
    val runId: String,
    val status: AgentRunStatus,
    val responseText: String,
    val verifiedContext: VerifiedAgentContext,
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
