package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException

/**
 * ACI 的最小只读桥接层。
 *
 * long: ACI 不复用 Profile 的完整工具白名单；调用者必须显式传入 SAFE 能力集合，且只允许前台直接调用。
 */
data class AciCapabilityDescriptor(
    val id: String,
    val description: String,
    val inputSchema: List<ToolInputField>,
    val readOnly: Boolean = true,
)

data class AciInvocationContext(
    val executionOrigin: AgentExecutionOrigin,
    val invocationSource: AgentInvocationSource,
    val allowedToolNames: Set<String>,
)

sealed interface AciCallResult {
    data class Success(val content: String) : AciCallResult
    data class Rejected(val reason: String) : AciCallResult
}

object AciPolicy {
    const val MAX_RESULT_CHARS = 20_000

    val DEFAULT_READ_ONLY_CAPABILITY_NAMES: Set<String> = setOf(
        "app.current_time",
        "app.get_info",
        "app.get_battery",
        "app.get_connectivity",
        "app.get_storage",
        "app.list_conversations",
        "app.search_conversations",
        "notes.list",
        "notes.search",
        "notes.get",
        "memory.search",
        "memory.get",
        "knowledge.search",
    )

    fun validateInvocation(context: AciInvocationContext) {
        require(context.executionOrigin == AgentExecutionOrigin.FOREGROUND) {
            "ACI 只读能力目前只允许前台调用"
        }
        require(context.invocationSource == AgentInvocationSource.DIRECT) {
            "ACI 暂不允许 Workflow、后台或远程入口调用"
        }
    }
}

class ReadOnlyAciCapabilityBridge(
    private val registry: ToolRegistry,
    exposedCapabilityNames: Set<String> = AciPolicy.DEFAULT_READ_ONLY_CAPABILITY_NAMES,
) {
    private val exposedCapabilityNames = exposedCapabilityNames.toSet()

    init {
        require(this.exposedCapabilityNames.isNotEmpty()) { "ACI 至少需要一个显式能力" }
        val invalid = this.exposedCapabilityNames.filter { name ->
            val definition = registry.registeredDefinition(name)
            definition == null || definition.risk != ToolRisk.SAFE
        }
        require(invalid.isEmpty()) {
            "ACI 只能暴露已注册的 SAFE 能力：${invalid.sorted().joinToString()}"
        }
    }

    fun discover(context: AciInvocationContext): List<AciCapabilityDescriptor> {
        AciPolicy.validateInvocation(context)
        val availableNames = registry.availableTools().mapTo(hashSetOf(), ToolDefinition::name)
        return exposedCapabilityNames
            .filter { it in context.allowedToolNames && it in availableNames }
            .mapNotNull { name -> registry.definition(name)?.takeIf { it.risk == ToolRisk.SAFE }?.toDescriptor() }
            .sortedBy(AciCapabilityDescriptor::id)
    }

    suspend fun call(
        capabilityId: String,
        arguments: Map<String, String>,
        context: AciInvocationContext,
    ): AciCallResult {
        val validation = runCatching { AciPolicy.validateInvocation(context) }
            .exceptionOrNull()
        if (validation != null) return AciCallResult.Rejected(validation.message.orEmpty())
        if (capabilityId !in exposedCapabilityNames) {
            return AciCallResult.Rejected("ACI 能力未公开：$capabilityId")
        }
        if (capabilityId !in context.allowedToolNames) {
            return AciCallResult.Rejected("当前 Profile 未授权 ACI 能力：$capabilityId")
        }
        val availableNames = registry.availableTools().mapTo(hashSetOf(), ToolDefinition::name)
        if (capabilityId !in availableNames) {
            return AciCallResult.Rejected("当前 Run 不可使用 ACI 能力：$capabilityId")
        }
        val definition = registry.definition(capabilityId)
            ?: return AciCallResult.Rejected("当前 Run 未注册 ACI 能力：$capabilityId")
        if (definition.risk != ToolRisk.SAFE) {
            return AciCallResult.Rejected("ACI 只允许 SAFE 能力：$capabilityId")
        }
        val argumentErrors = definition.validateArguments(arguments).errors
        if (argumentErrors.isNotEmpty()) {
            return AciCallResult.Rejected(argumentErrors.joinToString("；"))
        }
        val result = try {
            registry.execute(
                ToolCall(
                    name = capabilityId,
                    arguments = arguments.toMap(),
                    risk = ToolRisk.SAFE,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return AciCallResult.Rejected(error.message ?: "ACI 能力执行失败")
        }
        return if (result.success) {
            AciCallResult.Success(result.content.take(AciPolicy.MAX_RESULT_CHARS))
        } else {
            AciCallResult.Rejected(result.content.take(AciPolicy.MAX_RESULT_CHARS))
        }
    }

    private fun ToolDefinition.toDescriptor(): AciCapabilityDescriptor = AciCapabilityDescriptor(
        id = name,
        description = description,
        inputSchema = inputSchema,
    )
}
