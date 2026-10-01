package com.longdev.xiaoling.shared.agent

/**
 * long: 共享层只描述 Agent 的稳定输入输出和平台能力端口，避免把 Android Context、Room、Keystore、Accessibility 或 Compose 带进跨平台核心。
 */
object AgentRuntimeContract {
    const val VERSION = 1
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

data class SharedToolExecutionResult(
    val success: Boolean,
    val content: String,
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
