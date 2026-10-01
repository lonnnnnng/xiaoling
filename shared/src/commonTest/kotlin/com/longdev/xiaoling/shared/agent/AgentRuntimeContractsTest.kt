package com.longdev.xiaoling.shared.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRuntimeContractsTest {
    @Test
    fun contractKeepsPlatformIndependentToolAndWorkspaceData() {
        val call = SharedToolCall("call-1", "workspace.list", mapOf("path" to "."))
        val command = SharedWorkspaceCommand("printf", listOf("hello"))

        assertEquals(1, AgentRuntimeContract.VERSION)
        assertEquals("workspace.list", call.name)
        assertTrue(command.args.single() == "hello")
    }

    @Test
    fun runtimeRequestsApprovalExecutesToolAndSummarizes() = kotlinx.coroutines.test.runTest {
        val call = SharedToolCall("call-1", "echo", mapOf("text" to "hello"))
        val decisions = ArrayDeque<SharedAgentPlanDecision>().apply {
            add(SharedAgentPlanDecision.CallTool(call))
            add(SharedAgentPlanDecision.Complete)
        }
        val runtime = SharedAgentRuntime(
            llm = scriptedLlm(decisions, "已完成"),
            approvalGate = object : SharedApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: SharedToolCall,
                    definition: SharedToolDefinition,
                ): Boolean = runId == "run-1" && definition.requiresApproval
            },
            executor = SharedToolExecutor { SharedToolExecutionResult(true, "ok") },
        )

        val result = runtime.run(
            runId = "run-1",
            goal = "问候",
            tools = listOf(SharedToolDefinition("echo", "回显", requiresApproval = true, supportsBackground = false)),
        )

        assertEquals(SharedAgentRunStatus.COMPLETED, result.status)
        assertEquals(1, result.executions.size)
        assertEquals("已完成", result.summary)
    }

    @Test
    fun runtimeRejectsApprovalWithoutExecutingTool() = kotlinx.coroutines.test.runTest {
        val call = SharedToolCall("call-1", "danger", emptyMap())
        var executed = false
        val runtime = SharedAgentRuntime(
            llm = scriptedLlm(ArrayDeque(listOf(SharedAgentPlanDecision.CallTool(call))), ""),
            approvalGate = object : SharedApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: SharedToolCall,
                    definition: SharedToolDefinition,
                ): Boolean = false
            },
            executor = SharedToolExecutor {
                executed = true
                SharedToolExecutionResult(true, "unexpected")
            },
        )

        val result = runtime.run(
            runId = "run-2",
            goal = "危险动作",
            tools = listOf(SharedToolDefinition("danger", "危险", requiresApproval = true, supportsBackground = false)),
        )

        assertEquals(SharedAgentRunStatus.APPROVAL_REJECTED, result.status)
        assertFalse(executed)
    }

    @Test
    fun runtimeRejectsToolOutsideFrozenDirectory() = kotlinx.coroutines.test.runTest {
        val call = SharedToolCall("call-1", "unknown", emptyMap())
        val runtime = SharedAgentRuntime(
            llm = scriptedLlm(ArrayDeque(listOf(SharedAgentPlanDecision.CallTool(call))), ""),
            approvalGate = object : SharedApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: SharedToolCall,
                    definition: SharedToolDefinition,
                ): Boolean = true
            },
            executor = SharedToolExecutor { SharedToolExecutionResult(true, "unexpected") },
        )

        val result = runtime.run("run-3", "越界工具", emptyList())

        assertEquals(SharedAgentRunStatus.INVALID_TOOL, result.status)
        assertTrue(result.summary.contains("unknown"))
    }

    private fun scriptedLlm(
        decisions: ArrayDeque<SharedAgentPlanDecision>,
        summary: String,
    ): SharedAgentLlm = object : SharedAgentLlm {
        override suspend fun proposeNextAction(
            goal: String,
            tools: List<SharedToolDefinition>,
            completedCalls: List<SharedToolCall>,
        ): SharedAgentPlanDecision = decisions.removeFirst()

        override suspend fun summarize(
            goal: String,
            completedCalls: List<SharedToolCall>,
        ): String = summary
    }
}
