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

enum class AgentExecutionOrigin {
    FOREGROUND,
    BACKGROUND,
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
        val memoryIdsUsed: List<String> = emptyList(),
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
    val businessValidators: List<ToolBusinessValidator> = emptyList(),
    val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy(),
    val approvalPolicy: ToolApprovalPolicy = risk.defaultApprovalPolicy(),
    val verificationPolicy: ToolVerificationPolicy = ToolVerificationPolicy.RESULT_READABLE,
    val timeoutMs: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "工具名称不能为空" }
        require(description.isNotBlank()) { "工具描述不能为空" }
        require(timeoutMs == null || timeoutMs > 0) { "工具超时时间必须大于 0" }
        require(inputSchema.map { it.name }.distinct().size == inputSchema.size) { "工具参数名称不能重复" }
        require(risk == ToolRisk.SAFE || approvalPolicy == ToolApprovalPolicy.REQUIRE_CONFIRMATION) {
            "非 SAFE 工具必须要求用户确认"
        }
    }

    fun validateArguments(arguments: Map<String, String>): ToolValidationResult {
        val errors = mutableListOf<String>()
        val declaredFields = inputSchema.associateBy { it.name }
        arguments.keys
            .filterNot(declaredFields::containsKey)
            .sorted()
            .forEach { errors += "参数 $it 未在 Schema 中声明" }

        inputSchema.forEach { field ->
            val rawValue = arguments[field.name]
            if (rawValue.isNullOrBlank()) {
                if (field.required) errors += "缺少必填参数 ${field.name}"
                return@forEach
            }
            errors += field.validate(rawValue)
        }
        // long: 业务规则只接收已通过 Schema 的参数，避免每个校验器重复处理缺参、类型错误和未知字段。
        if (errors.isEmpty()) {
            businessValidators.forEach { validator -> errors += validator.validate(arguments) }
        }
        return ToolValidationResult(errors)
    }
}

fun interface ToolBusinessValidator {
    fun validate(arguments: Map<String, String>): List<String>
}

data class ToolPermissionPolicy(
    val requiredAndroidPermissions: Set<String> = emptySet(),
    val supportsBackground: Boolean = false,
) {
    init {
        require(requiredAndroidPermissions.none { it.isBlank() }) { "Android 权限名称不能为空" }
    }
}

fun interface ToolPermissionChecker {
    fun missingPermissions(requiredPermissions: Set<String>): Set<String>
}

object FailClosedToolPermissionChecker : ToolPermissionChecker {
    override fun missingPermissions(requiredPermissions: Set<String>): Set<String> = requiredPermissions
}

enum class ToolApprovalPolicy {
    NONE,
    REQUIRE_CONFIRMATION,
}

enum class ToolVerificationPolicy {
    RESULT_READABLE,
    EXECUTOR_VERIFIED,
}

private fun ToolRisk.defaultApprovalPolicy(): ToolApprovalPolicy {
    return if (this == ToolRisk.SAFE) ToolApprovalPolicy.NONE else ToolApprovalPolicy.REQUIRE_CONFIRMATION
}

enum class ToolInputType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
}

data class ToolInputField(
    val name: String,
    val description: String,
    val required: Boolean,
    val type: ToolInputType = ToolInputType.STRING,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val enumValues: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "工具参数名称不能为空" }
        require(description.isNotBlank()) { "工具参数描述不能为空" }
        require(minLength == null || minLength >= 0) { "字符串最小长度不能小于 0" }
        require(maxLength == null || maxLength >= 0) { "字符串最大长度不能小于 0" }
        require(minLength == null || maxLength == null || minLength <= maxLength) { "字符串最小长度不能大于最大长度" }
        require(minimum == null || maximum == null || minimum <= maximum) { "数值最小值不能大于最大值" }
        require(enumValues.none { it.isBlank() }) { "枚举值不能为空" }
        require(enumValues.isEmpty() || type == ToolInputType.STRING) { "只有字符串参数可以声明枚举" }
        require(minimum == null || minimum.isFinite()) { "数值最小值必须是有限数值" }
        require(maximum == null || maximum.isFinite()) { "数值最大值必须是有限数值" }
        require((minimum == null && maximum == null) || type == ToolInputType.INTEGER || type == ToolInputType.NUMBER) {
            "只有整数或数值参数可以声明数值范围"
        }
        require((minLength == null && maxLength == null) || type == ToolInputType.STRING) {
            "只有字符串参数可以声明长度范围"
        }
        require(type != ToolInputType.INTEGER || minimum == null || minimum % 1.0 == 0.0) {
            "整数参数的最小值必须是整数"
        }
        require(type != ToolInputType.INTEGER || maximum == null || maximum % 1.0 == 0.0) {
            "整数参数的最大值必须是整数"
        }
    }

    internal fun validate(rawValue: String): List<String> {
        val value = rawValue.trim()
        val errors = mutableListOf<String>()
        when (type) {
            ToolInputType.STRING -> {
                minLength?.takeIf { value.length < it }?.let { errors += "参数 $name 长度不能小于 $it" }
                maxLength?.takeIf { value.length > it }?.let { errors += "参数 $name 长度不能大于 $it" }
            }
            ToolInputType.INTEGER -> {
                val number = value.toLongOrNull()
                if (number == null) {
                    errors += "参数 $name 必须是整数"
                } else {
                    errors += validateNumberRange(number.toDouble())
                }
            }
            ToolInputType.NUMBER -> {
                val number = value.toDoubleOrNull()
                if (number == null || !number.isFinite()) {
                    errors += "参数 $name 必须是有限数值"
                } else {
                    errors += validateNumberRange(number)
                }
            }
            ToolInputType.BOOLEAN -> {
                if (!value.equals("true", ignoreCase = true) && !value.equals("false", ignoreCase = true)) {
                    errors += "参数 $name 必须是 true 或 false"
                }
            }
        }
        if (enumValues.isNotEmpty() && value !in enumValues) {
            errors += "参数 $name 只能是 ${enumValues.joinToString("、")}"
        }
        return errors
    }

    private fun validateNumberRange(number: Double): List<String> {
        val errors = mutableListOf<String>()
        minimum?.takeIf { number < it }?.let { errors += "参数 $name 必须不小于 ${it.toConstraintLabel()}" }
        maximum?.takeIf { number > it }?.let { errors += "参数 $name 必须不大于 ${it.toConstraintLabel()}" }
        return errors
    }
}

data class ToolValidationResult(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

private fun Double.toConstraintLabel(): String {
    return if (this % 1.0 == 0.0) toLong().toString() else toString()
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
    val memoryIdsUsed: List<String> = emptyList(),
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
    val expiresAt: Long? = null,
    val lastReferencedAt: Long? = null,
)

enum class AgentMemoryExpiryOption(val label: String) {
    NEVER("永久保留"),
    THIRTY_DAYS("30 天"),
    NINETY_DAYS("90 天"),
    ONE_YEAR("1 年"),
}

object AgentMemoryDecayPolicy {
    const val DAY_MILLIS = 86_400_000L

    fun isExpired(memory: AgentMemoryRecord, nowMillis: Long): Boolean {
        return memory.expiresAt?.let { it <= nowMillis } == true
    }

    fun expiresAt(option: AgentMemoryExpiryOption, nowMillis: Long): Long? {
        return when (option) {
            AgentMemoryExpiryOption.NEVER -> null
            AgentMemoryExpiryOption.THIRTY_DAYS -> nowMillis + 30 * DAY_MILLIS
            AgentMemoryExpiryOption.NINETY_DAYS -> nowMillis + 90 * DAY_MILLIS
            AgentMemoryExpiryOption.ONE_YEAR -> nowMillis + 365 * DAY_MILLIS
        }
    }

    fun score(memory: AgentMemoryRecord, nowMillis: Long): Double {
        if (memory.pinned) return Double.MAX_VALUE
        val referenceAt = memory.lastReferencedAt ?: memory.updatedAt
        val ageDays = (nowMillis - referenceAt).coerceAtLeast(0L).toDouble() / DAY_MILLIS
        val halfLifeDays = when (memory.type) {
            "ProfileFact" -> 730.0
            "Preference" -> 365.0
            "Procedure" -> 180.0
            "Episode" -> 90.0
            else -> 180.0
        }
        // long: 时间衰减只改变召回排序，不删除或改写用户事实；置顶记忆在上层优先级中保持稳定。
        return memory.confidence.coerceIn(0.0, 1.0) * Math.pow(0.5, ageDays / halfLifeDays)
    }
}

data class AgentMemorySource(
    val conversationId: String?,
    val runId: String?,
    val summary: String,
)

interface AgentMemoryStore {
    suspend fun remember(content: String, tags: String, type: String, source: AgentMemorySource, confidence: Double): AgentMemoryRecord
    suspend fun get(memoryId: String): AgentMemoryRecord?
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
    suspend fun setExpiresAt(memoryId: String, expiresAt: Long?): AgentMemoryRecord?
    suspend fun delete(memoryId: String): AgentMemoryRecord?
    suspend fun latestDeleted(): AgentMemoryRecord?
    suspend fun restore(memory: AgentMemoryRecord): AgentMemoryRecord
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
    val memoryRecallEnabled: Boolean = true,
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
