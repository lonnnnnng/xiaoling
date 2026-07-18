package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStepAgentRuntimeTest {
    @Test
    fun runExecutesTwoVerifiedToolsBeforeModelCompletes() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RecordingMultiStepToolRegistry()
        val llm = object : AgentLlm {
            override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                error("多步 Runtime 应调用带执行历史的规划入口")
            }

            override suspend fun proposeNextAction(
                goal: String,
                tools: List<ToolDefinition>,
                completedTools: List<AgentToolExecution>,
            ): AgentPlanDecision {
                return when (completedTools.size) {
                    0 -> AgentPlanDecision.CallTool(call("test.first", "first"))
                    1 -> AgentPlanDecision.CallTool(call("test.second", "second"))
                    else -> AgentPlanDecision.Complete
                }
            }

            override suspend fun summarize(
                goal: String,
                toolCall: ToolCall,
                toolResult: ToolExecutionResult,
            ): String = """{"style":"compact","tone":"neutral"}"""
        }

        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = llm,
        ).run(
            conversationId = "conversation-multi-step",
            userMessageId = "message-multi-step",
            goal = "先执行 first，再执行 second",
        )

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(listOf("test.first", "test.second"), registry.executedToolNames)
        assertEquals(3, snapshot.steps.count { it.type == "llm.plan" })
        assertEquals(2, snapshot.steps.count { it.type == AgentStepTypes.TOOL_EXECUTE })
        assertEquals(2, snapshot.steps.count { it.type == AgentStepTypes.TOOL_VERIFY })
        assertEquals(2, snapshot.events.count { it.type == "tool.result" })
        assertEquals(2, snapshot.events.count { it.type == "tool.verify" })
        assertEquals(listOf("test.first", "test.second"), summary.verifiedContext.toolExecutions.map { it.toolName })
        assertEquals(listOf("test.first:first", "test.second:second"), summary.verifiedContext.toolExecutions.map { it.rawResult })
        assertTrue(summary.responseText.contains("test.first:first"))
        assertTrue(summary.responseText.contains("test.second:second"))
    }

    private fun call(name: String, value: String) = ToolCall(
        name = name,
        arguments = mapOf("value" to value),
        risk = ToolRisk.SAFE,
    )
}

private class RecordingMultiStepToolRegistry : ToolRegistry {
    private val tools = listOf(
        toolDefinition("test.first"),
        toolDefinition("test.second"),
    )
    val executedToolNames = mutableListOf<String>()

    override fun availableTools(): List<ToolDefinition> = tools

    override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        executedToolNames += call.name
        return ToolExecutionResult(success = true, content = "${call.name}:${call.arguments.getValue("value")}")
    }

    private fun toolDefinition(name: String) = ToolDefinition(
        name = name,
        description = "多步 Runtime 测试工具 $name",
        risk = ToolRisk.SAFE,
        inputSchema = listOf(
            ToolInputField(
                name = "value",
                description = "用于区分执行顺序的测试值",
                required = true,
            ),
        ),
    )
}
