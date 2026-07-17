package com.longdev.xiaoling.agent

interface ToolRegistry {
    fun availableTools(): List<ToolDefinition>
    fun definition(name: String): ToolDefinition?
    suspend fun execute(call: ToolCall): ToolExecutionResult
}

object ToolRegistryContract {
    fun requireValid(definitions: List<ToolDefinition>) {
        val duplicates = definitions
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        require(duplicates.isEmpty()) { "发现重复工具名称：${duplicates.joinToString(", ")}" }
    }
}

class FakeToolRegistry : ToolRegistry {
    private val echoTool = ToolDefinition(
        name = "fake.echo",
        description = "回显用户目标，用于验证 Agent Run、审批、工具执行和后置验证链路。",
        risk = ToolRisk.REQUIRES_APPROVAL,
        inputSchema = listOf(
            ToolInputField(
                name = "goal",
                description = "用户希望 Agent 完成或验证的目标。",
                required = true,
            ),
        ),
    )

    init {
        ToolRegistryContract.requireValid(listOf(echoTool))
    }

    override fun availableTools(): List<ToolDefinition> = listOf(echoTool)

    override fun definition(name: String): ToolDefinition? = availableTools().firstOrNull { it.name == name }

    fun fallbackCall(goal: String): ToolCall {
        return ToolCall(
            name = "fake.echo",
            arguments = mapOf("goal" to goal),
            risk = echoTool.risk,
        )
    }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        val goal = call.arguments["goal"].orEmpty()
        return ToolExecutionResult(
            success = true,
            content = "fake.echo 已执行：${goal.ifBlank { "空任务" }}",
        )
    }
}
