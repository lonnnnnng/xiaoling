package com.longdev.xiaoling.shared.agent

/**
 * long: 共享层只描述 Agent 的稳定输入输出和平台能力端口，避免把 Android Context、Room、Keystore、Accessibility 或 Compose 带进跨平台核心。
 */
object AgentRuntimeContract {
    const val VERSION = 3
}

data class SharedToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>,
)

data class SharedToolDefinition(
    val name: String,
    val description: String,
    val requiresApproval: Boolean,
    val supportsBackground: Boolean,
)

data class SharedToolCatalogEntry(
    val definition: SharedToolDefinition,
    val sources: Set<String> = setOf("native"),
) {
    init {
        require(sources.isNotEmpty()) { "共享工具目录来源不能为空" }
        require(sources.none { it.isBlank() }) { "共享工具目录来源不能包含空值" }
    }
}

/**
 * long: 共享目录只冻结模型可见工具和来源指纹；权限、审批和副作用仍由各平台 adapter 在执行前重新核查。
 */
data class SharedToolCatalog(
    val version: Int,
    val entries: List<SharedToolCatalogEntry>,
    val fingerprint: String,
) {
    init {
        require(version > 0) { "共享工具目录版本必须大于 0" }
        require(entries.map { it.definition.name }.distinct().size == entries.size) {
            "共享工具目录不能包含重复工具名"
        }
        require(entries == entries.sortedBy { it.definition.name }) {
            "共享工具目录必须按工具名稳定排序"
        }
        require(fingerprint == fingerprintFor(version, entries)) {
            "共享工具目录指纹与内容不一致"
        }
    }

    val definitions: List<SharedToolDefinition>
        get() = entries.map { it.definition }

    fun definition(name: String): SharedToolDefinition? =
        entries.firstOrNull { it.definition.name == name }?.definition

    companion object {
        const val CURRENT_VERSION: Int = 1

        fun from(
            definitions: List<SharedToolDefinition>,
            version: Int = CURRENT_VERSION,
        ): SharedToolCatalog = fromEntries(
            entries = definitions.map { SharedToolCatalogEntry(it) },
            version = version,
        )

        fun fromEntries(
            entries: List<SharedToolCatalogEntry>,
            version: Int = CURRENT_VERSION,
        ): SharedToolCatalog {
            val sorted = entries.sortedBy { it.definition.name }
            require(sorted.map { it.definition.name }.distinct().size == sorted.size) {
                "共享工具目录不能包含重复工具名：${sorted.groupingBy { it.definition.name }.eachCount().filterValues { it > 1 }.keys.sorted().joinToString() }"
            }
            return SharedToolCatalog(
                version = version,
                entries = sorted,
                fingerprint = fingerprintFor(version, sorted),
            )
        }

        private fun fingerprintFor(
            version: Int,
            entries: List<SharedToolCatalogEntry>,
        ): String {
            val canonical = buildString {
                append(version).append('|')
                entries.forEach { entry ->
                    append(entry.definition.name.length).append(':').append(entry.definition.name)
                    append(entry.definition.description.length).append(':').append(entry.definition.description)
                    append(entry.definition.requiresApproval).append('|')
                    append(entry.definition.supportsBackground).append('|')
                    entry.sources.sorted().forEach { source ->
                        append(source.length).append(':').append(source)
                    }
                    append(';')
                }
            }
            // long: shared 不能依赖 Android/JVM 加密库，使用确定性的 64 位摘要保证跨平台排序和快照漂移可见；敏感权限仍不进入 shared 目录。
            var hash = 1125899906842597L
            canonical.forEach { character -> hash = 31L * hash + character.code }
            return hash.toString(16)
        }
    }
}

sealed interface SharedAgentPlanDecision {
    data class CallTool(val toolCall: SharedToolCall) : SharedAgentPlanDecision
    data object Complete : SharedAgentPlanDecision
}

interface SharedAgentLlm {
    suspend fun proposeNextAction(
        goal: String,
        tools: List<SharedToolDefinition>,
        completedCalls: List<SharedToolCall>,
    ): SharedAgentPlanDecision

    suspend fun summarize(
        goal: String,
        completedCalls: List<SharedToolCall>,
    ): String
}

interface SharedApprovalGate {
    suspend fun requestApproval(
        runId: String,
        toolCall: SharedToolCall,
        definition: SharedToolDefinition,
    ): Boolean
}

data class SharedWorkspaceCommand(
    val commandId: String,
    val args: List<String> = emptyList(),
)

data class SharedWorkspaceCommandResult(
    val commandId: String,
    val args: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

interface SharedWorkspaceRuntime {
    suspend fun execute(
        command: SharedWorkspaceCommand,
        cwd: String,
        timeoutMs: Long,
    ): SharedWorkspaceCommandResult
}

enum class SharedToolReceiptStatus {
    COMMITTED,
    NOT_COMMITTED,
    UNKNOWN,
}

data class SharedToolExecutionReceipt(
    val toolCallId: String,
    val operationId: String,
    val status: SharedToolReceiptStatus,
) {
    init {
        require(toolCallId.isNotBlank()) { "共享执行回执的工具调用 ID 不能为空" }
        require(operationId.isNotBlank()) { "共享执行回执的操作 ID 不能为空" }
    }
}

enum class SharedToolVerificationStatus {
    PASSED,
    FAILED,
}

data class SharedToolVerificationEvidence(
    val status: SharedToolVerificationStatus,
    val toolCallId: String?,
    val reasonCode: String?,
) {
    init {
        require(toolCallId == null || toolCallId.isNotBlank()) { "共享验证证据的工具调用 ID 不能为空白" }
        require(reasonCode == null || reasonCode.isNotBlank()) { "共享验证证据的原因码不能为空白" }
    }
}

data class SharedToolExecutionResult(
    val success: Boolean,
    val content: String,
    val verified: Boolean? = null,
    val executionReceipt: SharedToolExecutionReceipt? = null,
    val verification: SharedToolVerificationEvidence? = null,
)

data class SharedAgentExecution(
    val toolCall: SharedToolCall,
    val result: SharedToolExecutionResult,
)

enum class SharedAgentRunStatus {
    COMPLETED,
    APPROVAL_REJECTED,
    INVALID_TOOL,
    EXECUTION_FAILED,
    STEP_LIMIT_EXCEEDED,
}

data class SharedAgentRunResult(
    val status: SharedAgentRunStatus,
    val executions: List<SharedAgentExecution>,
    val summary: String,
)

fun interface SharedToolExecutor {
    suspend fun execute(toolCall: SharedToolCall): SharedToolExecutionResult
}

/**
 * long: 共享执行循环只处理工具目录、审批、步骤上限和结果账本，平台权限与实际副作用必须由各端 adapter 注入。
 */
class SharedAgentRuntime(
    private val llm: SharedAgentLlm,
    private val approvalGate: SharedApprovalGate,
    private val executor: SharedToolExecutor,
    private val maxSteps: Int = 8,
) {
    init {
        require(maxSteps > 0) { "共享 Agent 步骤上限必须大于 0" }
    }

    suspend fun run(
        runId: String,
        goal: String,
        catalog: SharedToolCatalog,
    ): SharedAgentRunResult = run(
        runId = runId,
        goal = goal,
        tools = catalog.definitions,
    )

    suspend fun run(
        runId: String,
        goal: String,
        tools: List<SharedToolDefinition>,
    ): SharedAgentRunResult {
        val executions = mutableListOf<SharedAgentExecution>()
        repeat(maxSteps) {
            when (val decision = llm.proposeNextAction(goal, tools, executions.map { it.toolCall })) {
                SharedAgentPlanDecision.Complete -> {
                    return SharedAgentRunResult(
                        status = SharedAgentRunStatus.COMPLETED,
                        executions = executions.toList(),
                        summary = llm.summarize(goal, executions.map { it.toolCall }),
                    )
                }

                is SharedAgentPlanDecision.CallTool -> {
                    val definition = tools.firstOrNull { it.name == decision.toolCall.name }
                        ?: return SharedAgentRunResult(
                            status = SharedAgentRunStatus.INVALID_TOOL,
                            executions = executions.toList(),
                            summary = "模型提出了未注册工具：${decision.toolCall.name}",
                        )
                    if (definition.requiresApproval && !approvalGate.requestApproval(runId, decision.toolCall, definition)) {
                        return SharedAgentRunResult(
                            status = SharedAgentRunStatus.APPROVAL_REJECTED,
                            executions = executions.toList(),
                            summary = "工具审批被拒绝：${decision.toolCall.name}",
                        )
                    }
                    val result = executor.execute(decision.toolCall)
                    executions += SharedAgentExecution(decision.toolCall, result)
                    if (!result.success) {
                        return SharedAgentRunResult(
                            status = SharedAgentRunStatus.EXECUTION_FAILED,
                            executions = executions.toList(),
                            summary = "工具执行失败：${decision.toolCall.name}",
                        )
                    }
                }
            }
        }
        return SharedAgentRunResult(
            status = SharedAgentRunStatus.STEP_LIMIT_EXCEEDED,
            executions = executions.toList(),
            summary = "共享 Agent 达到步骤上限：$maxSteps",
        )
    }
}
