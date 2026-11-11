package com.longdev.xiaoling.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalAgentCancellationTest {
    @Test
    fun persistedStopInterruptsOnlyTargetModelWait() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        fun runtime(started: CompletableDeferred<Unit>) = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    started.complete(Unit)
                    awaitCancellation()
                }
                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String =
                    error("停止中的模型不能继续总结")
            },
        )
        val jobA = launch { runtime(startedA).run("conversation-a", "message-a", "等待 A") }
        startedA.await()
        val runA = requireNotNull(ledger.lastRunId)
        assertTrue(AgentRunExecutionRegistry.isRunning(runA))
        val jobB = launch { runtime(startedB).run("conversation-b", "message-b", "等待 B") }
        startedB.await()
        val runB = requireNotNull(ledger.lastRunId)

        ledger.requestCancelForTest(runA)
        withTimeout(500) { jobA.join() }

        assertTrue(jobA.isCancelled)
        assertFalse(AgentRunExecutionRegistry.isRunning(runA))
        assertEquals(AgentRunStatus.CANCELLED, ledger.snapshot(runA).run.status)
        assertEquals(AgentStepStatus.CANCELLED, ledger.snapshot(runA).steps.single().status)
        assertTrue(jobB.isActive)
        assertEquals(AgentRunStatus.THINKING, ledger.snapshot(runB).run.status)
        jobB.cancelAndJoin()
    }

    @Test
    fun persistedStopInterruptsApprovalWait() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val waiting = CompletableDeferred<String>()
        val runtime = MinimalAgentRuntime(ledger, llm = approvalModel(), approvalGate = object : ApprovalGate {
            override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                waiting.complete(runId)
                awaitCancellation()
            }
        })
        val job = launch { runtime.run("conversation-approval", "message-approval", "等待确认") }
        val runId = waiting.await()
        ledger.requestCancelForTest(runId)
        withTimeout(500) { job.join() }
        val snapshot = ledger.snapshot(runId)
        assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
        assertEquals(AgentStepStatus.CANCELLED, snapshot.steps.single { it.type == "approval" }.status)
        assertFalse(snapshot.events.any { it.type == "tool.result" })
    }

    @Test
    fun approvalReturningAfterPersistedStopCannotExecuteTool() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var executions = 0
        val fake = FakeToolRegistry()
        val runtime = MinimalAgentRuntime(ledger, llm = approvalModel(),
            toolRegistry = object : ToolRegistry by fake {
                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    executions += 1
                    return fake.execute(call)
                }
            },
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                    // long: 不让出协程，复现审批回调与持久停止落在同一轮执行中的竞争。
                    ledger.requestCancelForTest(runId)
                    return ApprovalDecision(true, "迟到批准")
                }
            })
        val job = launch { runtime.run("conversation-race", "message-race", "停止竞争") }
        job.join()
        assertEquals(0, executions)
        assertEquals(AgentRunStatus.CANCELLED, ledger.snapshot(requireNotNull(ledger.lastRunId)).run.status)
    }

    private fun approvalModel() = object : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall =
            ToolCall(name = "fake.echo", arguments = mapOf("goal" to goal), risk = ToolRisk.REQUIRES_APPROVAL)

        override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String =
            error("取消的审批不能进入总结")
    }
}
