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

    @Test
    fun modelAndToolSegmentsShareOneMonotonicRunBudget() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val clock = MutableMonotonicClock()
        val registry = RecordingMultiStepToolRegistry(onExecute = { clock.advanceBy(30) })
        var planningCalls = 0
        val llm = object : AgentLlm {
            override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                error("多步 Runtime 应调用带执行历史的规划入口")
            }

            override suspend fun proposeNextAction(
                goal: String,
                tools: List<ToolDefinition>,
                completedTools: List<AgentToolExecution>,
            ): AgentPlanDecision {
                planningCalls += 1
                clock.advanceBy(20)
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
            ): String = error("总预算耗尽后不应进入模型总结")
        }
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = llm,
            options = AgentRuntimeOptions(
                runTimeoutMs = 100,
                modelStepTimeoutMs = 1_000,
                toolStepTimeoutMs = 1_000,
            ),
            monotonicClock = clock,
        )

        val failure = runCatching {
            runtime.run("conversation-time-budget", "message-time-budget", "累计模型和工具执行预算")
        }.exceptionOrNull()
        val snapshot = ledger.snapshot(checkNotNull(ledger.lastRunId))
        val toolDurations = snapshot.events
            .mapNotNull { it.metadata as? RunEventMetadata.ToolResult }
            .map { it.durationMs }

        assertTrue(failure is AgentTimeoutException)
        assertEquals("Agent Run 超时：100ms", failure?.message)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, snapshot.run.status)
        assertEquals(2, planningCalls)
        assertEquals(listOf("test.first", "test.second"), registry.executedToolNames)
        assertEquals(listOf(30L, 30L), toolDurations)
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.last { it.type == AgentStepTypes.LLM_PLAN }.status)
    }

    @Test
    fun fifthToolCallIsRejectedByRunBudget() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RecordingMultiStepToolRegistry()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = scriptedLlm { completedTools ->
                val step = completedTools.size + 1
                AgentPlanDecision.CallTool(call(if (step % 2 == 0) "test.second" else "test.first", "step-$step"))
            },
            options = AgentRuntimeOptions(maxToolCalls = 4),
        )

        val failure = runCatching {
            runtime.run("conversation-budget", "message-budget", "连续执行五个步骤")
        }.exceptionOrNull()
        val snapshot = ledger.snapshot(ledger.lastRunId!!)

        assertTrue(failure is AgentBudgetExceededException)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, snapshot.run.status)
        assertEquals(4, registry.executedToolNames.size)
        assertEquals(4, snapshot.events.count { it.type == "tool.result" })
    }

    @Test
    fun repeatedToolFingerprintAcrossPlanningRoundsIsRejected() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RecordingMultiStepToolRegistry(risk = ToolRisk.REQUIRES_APPROVAL)
        val repeated = call("test.first", "same")
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = scriptedLlm { AgentPlanDecision.CallTool(repeated) },
        )

        val failure = runCatching {
            runtime.run("conversation-loop", "message-loop", "重复相同工具")
        }.exceptionOrNull()
        val snapshot = ledger.snapshot(ledger.lastRunId!!)

        assertTrue(failure is AgentBudgetExceededException)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("重复工具调用"))
        assertEquals(listOf("test.first"), registry.executedToolNames)
    }

    @Test
    fun immediateRepeatedVerifiedReadOnlyToolUsesExistingResultAndCompletes() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RecordingMultiStepToolRegistry()
        val repeated = call("test.first", "same")
        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = scriptedLlm { AgentPlanDecision.CallTool(repeated) },
        ).run("conversation-read-repeat", "message-read-repeat", "读取一次测试结果")

        val snapshot = ledger.snapshot(summary.runId)

        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(listOf("test.first"), registry.executedToolNames)
        assertEquals(1, snapshot.events.count { it.type == "tool.result" })
        assertEquals(1, snapshot.events.count { it.type == "tool.verify" })
        assertTrue(snapshot.events.any { it.type == AgentEventTypes.LLM_REPEAT_COMPLETED })
    }

    @Test
    fun prematureCompleteBeforeAnyToolGetsOneCorrectedPlanningRetry() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RecordingMultiStepToolRegistry()
        val planningGoals = mutableListOf<String>()
        val llm = object : AgentLlm {
            override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                error("多步 Runtime 应调用带执行历史的规划入口")
            }

            override suspend fun proposeNextAction(
                goal: String,
                tools: List<ToolDefinition>,
                completedTools: List<AgentToolExecution>,
            ): AgentPlanDecision {
                planningGoals += goal
                return when {
                    completedTools.isNotEmpty() -> AgentPlanDecision.Complete
                    "当前 Run 尚未执行任何工具" in goal -> AgentPlanDecision.CallTool(call("test.first", "corrected"))
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
        ).run("conversation-premature-complete", "message-premature-complete", "读取一次测试结果")

        val snapshot = ledger.snapshot(summary.runId)

        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(listOf("test.first"), registry.executedToolNames)
        assertEquals(3, planningGoals.size)
        assertTrue("当前 Run 尚未执行任何工具" in planningGoals[1])
        assertTrue(snapshot.events.any { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
    }

    @Test
    fun snapshotCanRefreshOnlyAfterVerifiedDeviceAction() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RefreshableSnapshotToolRegistry()
        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = scriptedLlm { completedTools ->
                when (completedTools.size) {
                    0 -> AgentPlanDecision.CallTool(
                        ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE),
                    )
                    1 -> AgentPlanDecision.CallTool(
                        ToolCall(name = "device.back", arguments = emptyMap(), risk = ToolRisk.SAFE),
                    )
                    2 -> AgentPlanDecision.CallTool(
                        ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE),
                    )
                    else -> AgentPlanDecision.Complete
                }
            },
        ).run("conversation-refresh", "message-refresh", "返回后重新观察")

        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(listOf("device.snapshot", "device.back", "device.snapshot"), registry.executedToolNames)
    }

    @Test
    fun eachNonSafeToolStepRequiresIndependentApproval() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = RecordingMultiStepToolRegistry(risk = ToolRisk.REQUIRES_APPROVAL)
        val approvedTools = mutableListOf<String>()
        val approvalGate = object : ApprovalGate {
            override suspend fun requestApproval(
                runId: String,
                toolCall: ToolCall,
                definition: ToolDefinition,
            ): ApprovalDecision {
                approvedTools += toolCall.name
                return ApprovalDecision(approved = true, reason = "逐步确认")
            }
        }
        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = scriptedLlm { completedTools ->
                when (completedTools.size) {
                    0 -> AgentPlanDecision.CallTool(call("test.first", "first"))
                    1 -> AgentPlanDecision.CallTool(call("test.second", "second"))
                    else -> AgentPlanDecision.Complete
                }
            },
            approvalGate = approvalGate,
        ).run("conversation-approval", "message-approval", "执行两个写步骤")

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(listOf("test.first", "test.second"), approvedTools)
        assertEquals(2, snapshot.events.count { it.type == "approval.granted" })
        assertEquals(2, snapshot.steps.count { it.type == "approval" && it.status == AgentStepStatus.COMPLETED })
    }

    private fun scriptedLlm(
        decide: (List<AgentToolExecution>) -> AgentPlanDecision,
    ): AgentLlm {
        return object : AgentLlm {
            override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                error("多步测试必须走 proposeNextAction")
            }

            override suspend fun proposeNextAction(
                goal: String,
                tools: List<ToolDefinition>,
                completedTools: List<AgentToolExecution>,
            ): AgentPlanDecision = decide(completedTools)

            override suspend fun summarize(
                goal: String,
                toolCall: ToolCall,
                toolResult: ToolExecutionResult,
            ): String = """{"style":"compact","tone":"neutral"}"""
        }
    }

    private fun call(name: String, value: String) = ToolCall(
        name = name,
        arguments = mapOf("value" to value),
        risk = ToolRisk.SAFE,
    )
}

private class RecordingMultiStepToolRegistry(
    private val risk: ToolRisk = ToolRisk.SAFE,
    private val onExecute: () -> Unit = {},
) : ToolRegistry {
    private val tools = listOf(
        toolDefinition("test.first"),
        toolDefinition("test.second"),
    )
    val executedToolNames = mutableListOf<String>()

    override fun availableTools(): List<ToolDefinition> = tools

    override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        onExecute()
        executedToolNames += call.name
        return ToolExecutionResult(success = true, content = "${call.name}:${call.arguments.getValue("value")}")
    }

    private fun toolDefinition(name: String) = ToolDefinition(
        name = name,
        description = "多步 Runtime 测试工具 $name",
        risk = risk,
        inputSchema = listOf(
            ToolInputField(
                name = "value",
                description = "用于区分执行顺序的测试值",
                required = true,
            ),
        ),
    )
}

private class RefreshableSnapshotToolRegistry : ToolRegistry {
    private val tools = listOf("device.snapshot", "device.back").map { name ->
        ToolDefinition(name = name, description = name, risk = ToolRisk.SAFE)
    }
    val executedToolNames = mutableListOf<String>()

    override fun availableTools(): List<ToolDefinition> = tools

    override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        executedToolNames += call.name
        return ToolExecutionResult(success = true, content = "${call.name} 已验证")
    }
}

private class MutableMonotonicClock : MonotonicClock {
    private var currentMs: Long = 0

    override fun nowMs(): Long = currentMs

    fun advanceBy(durationMs: Long) {
        currentMs += durationMs
    }
}
