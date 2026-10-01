package com.longdev.xiaoling.shared.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRuntimeContractsTest {
    @Test
    fun contractKeepsPlatformIndependentToolAndWorkspaceData() {
        val call = SharedToolCall("call-1", "workspace.list", mapOf("path" to "."))
        val command = SharedWorkspaceCommand("printf", listOf("hello"))

        assertEquals(2, AgentRuntimeContract.VERSION)
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

    @Test
    fun runtimeStopsOnFailedExecution() = kotlinx.coroutines.test.runTest {
        val call = SharedToolCall("call-1", "failing", emptyMap())
        val runtime = SharedAgentRuntime(
            llm = scriptedLlm(ArrayDeque(listOf(SharedAgentPlanDecision.CallTool(call))), ""),
            approvalGate = object : SharedApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: SharedToolCall,
                    definition: SharedToolDefinition,
                ): Boolean = true
            },
            executor = SharedToolExecutor { SharedToolExecutionResult(false, "failed") },
        )

        val result = runtime.run(
            runId = "run-4",
            goal = "失败动作",
            tools = listOf(SharedToolDefinition("failing", "失败", requiresApproval = false, supportsBackground = false)),
        )

        assertEquals(SharedAgentRunStatus.EXECUTION_FAILED, result.status)
        assertEquals(1, result.executions.size)
    }

    @Test
    fun runtimeFailsClosedAtStepLimit() = kotlinx.coroutines.test.runTest {
        val call = SharedToolCall("call-1", "safe", emptyMap())
        val runtime = SharedAgentRuntime(
            llm = scriptedLlm(
                ArrayDeque(
                    listOf(
                        SharedAgentPlanDecision.CallTool(call),
                        SharedAgentPlanDecision.CallTool(call),
                    ),
                ),
                "",
            ),
            approvalGate = object : SharedApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: SharedToolCall,
                    definition: SharedToolDefinition,
                ): Boolean = true
            },
            executor = SharedToolExecutor { SharedToolExecutionResult(true, "ok") },
            maxSteps = 2,
        )

        val result = runtime.run(
            runId = "run-5",
            goal = "循环动作",
            tools = listOf(SharedToolDefinition("safe", "安全", requiresApproval = false, supportsBackground = false)),
        )

        assertEquals(SharedAgentRunStatus.STEP_LIMIT_EXCEEDED, result.status)
        assertEquals(2, result.executions.size)
    }

    @Test
    fun sessionTransitionsAndRestoresWithMonotonicEventSequence() {
        val session = SharedAgentRunSession.create(SharedAgentRunIdentity("run-1"))
        session.transition(SharedAgentRunState.WAITING_APPROVAL, "等待用户确认")
        session.transition(SharedAgentRunState.RUNNING, "用户已确认")
        session.appendEvent("tool.completed", "只读工具完成")
        session.transition(SharedAgentRunState.COMPLETED, "已生成总结")

        val snapshot = session.snapshot()
        val restored = SharedAgentRunSession.restore(snapshot)

        assertEquals(SharedAgentRunState.COMPLETED, restored.state)
        assertEquals(snapshot, restored.snapshot())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), snapshot.events.map { it.sequence })
    }

    @Test
    fun sessionRejectsLateEventsAndInvalidTransitionsAfterTerminalState() {
        val session = SharedAgentRunSession.create(SharedAgentRunIdentity("run-2"))
        session.transition(SharedAgentRunState.RUNNING, "开始执行")
        session.transition(SharedAgentRunState.FAILED, "工具失败")

        assertFalse(session.requestCancel("迟到取消"))
        assertFailsWith<IllegalArgumentException> { session.appendEvent("late", "迟到事件") }
        assertFailsWith<IllegalArgumentException> {
            session.transition(SharedAgentRunState.RUNNING, "恢复执行")
        }
    }

    @Test
    fun sessionCancellationRequiresExplicitTerminalSettlement() {
        val session = SharedAgentRunSession.create(
            SharedAgentRunIdentity(runId = "child-1", rootRunId = "root-1", parentRunId = "parent-1"),
        )

        assertTrue(session.requestCancel("用户主动停止"))
        assertTrue(session.cancelRequested)
        assertEquals(SharedAgentRunState.CANCEL_REQUESTED, session.state)
        session.transition(SharedAgentRunState.CANCELLED, "执行器已停止")

        assertEquals(SharedAgentRunState.CANCELLED, session.state)
        assertTrue(session.cancelRequested)
        assertEquals(session.snapshot(), SharedAgentRunSession.restore(session.snapshot()).snapshot())
        assertFalse(session.requestCancel("重复停止"))
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
