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
