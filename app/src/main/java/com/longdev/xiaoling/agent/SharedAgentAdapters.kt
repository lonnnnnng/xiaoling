package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentPlanDecision
import com.longdev.xiaoling.shared.agent.SharedApprovalGate
import com.longdev.xiaoling.shared.agent.SharedAgentLlm
import com.longdev.xiaoling.shared.agent.SharedToolCall
import com.longdev.xiaoling.shared.agent.SharedToolDefinition
import com.longdev.xiaoling.shared.agent.SharedToolExecutionResult
import com.longdev.xiaoling.shared.agent.SharedToolExecutor

fun ToolDefinition.toSharedAgentDefinition(): SharedToolDefinition = SharedToolDefinition(
    name = name,
    description = description,
    requiresApproval = approvalPolicy == ToolApprovalPolicy.REQUIRE_CONFIRMATION,
    supportsBackground = permissionPolicy.supportsBackground,
)

fun ToolCall.toSharedAgentCall(): SharedToolCall = SharedToolCall(
    id = id,
    name = name,
    arguments = arguments,
)

fun SharedToolCall.toAndroidToolCall(definition: ToolDefinition): ToolCall = ToolCall(
    id = id,
    name = name,
    arguments = arguments,
    risk = definition.risk,
)

fun SharedAgentPlanDecision.toAndroidPlanDecision(
    definitionLookup: (String) -> ToolDefinition?,
): AgentPlanDecision = when (this) {
    SharedAgentPlanDecision.Complete -> AgentPlanDecision.Complete
    is SharedAgentPlanDecision.CallTool -> {
        val definition = definitionLookup(toolCall.name)
            ?: error("共享 Agent 提出未注册工具：${toolCall.name}")
        AgentPlanDecision.CallTool(toolCall.toAndroidToolCall(definition))
    }
}

fun ToolExecutionResult.toSharedAgentResult(): SharedToolExecutionResult = SharedToolExecutionResult(
    success = success,
    content = content,
)

/**
 * long: shared runtime 的审批必须重新查 Android 当前工具定义，避免共享层快照绕过 Profile、Skill 或当前 Run 的动态门禁。
 */
class AndroidSharedApprovalGate(
    private val delegate: ApprovalGate,
    private val definitionLookup: (String) -> ToolDefinition?,
) : SharedApprovalGate {
    override suspend fun requestApproval(
        runId: String,
        toolCall: SharedToolCall,
        definition: SharedToolDefinition,
    ): Boolean {
        val androidDefinition = definitionLookup(toolCall.name) ?: return false
        if (androidDefinition.toSharedAgentDefinition() != definition) return false
        return delegate.requestApproval(
            runId = runId,
            toolCall = toolCall.toAndroidToolCall(androidDefinition),
            definition = androidDefinition,
        ).approved
    }
}

/**
 * long: 执行 adapter 不信任 shared runtime 携带的风险字段，执行前再次从 Android Registry 查定义并恢复原生 ToolCall。
 */
class AndroidSharedToolExecutor(
    private val registry: ToolRegistry,
) : SharedToolExecutor {
    override suspend fun execute(toolCall: SharedToolCall): SharedToolExecutionResult {
        val definition = registry.definition(toolCall.name)
            ?: return SharedToolExecutionResult(false, "共享 Agent 工具未注册：${toolCall.name}")
        return registry.execute(toolCall.toAndroidToolCall(definition)).toSharedAgentResult()
    }
}

/**
 * long: 该 adapter 只做类型和工具目录转换；模型网络、遥测和 Android 失败分类继续由现有 AgentLlm 实现负责。
 */
class AndroidSharedAgentLlm(
    private val delegate: SharedAgentLlm,
) : SharedAgentLlm by delegate
