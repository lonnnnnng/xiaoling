package com.longdev.xiaoling.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MinimalAgentRuntimeTest {
    @Test
    fun runDemoCompletesWithAuditableSteps() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
        )

        val summary = runtime.runDemo(
            conversationId = "conversation-1",
            userMessageId = "message-1",
            goal = "整理今天的计划",
        )

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertEquals(listOf("llm.plan", "approval", "tool.execute", "tool.verify", "llm.summarize"), snapshot.steps.map { it.type })
        assertTrue(snapshot.steps.all { it.status == AgentStepStatus.COMPLETED })
        assertTrue(snapshot.events.any { it.type == "approval.granted" })
        assertTrue(summary.responseText.contains("执行后验证") || summary.responseText.contains("验证"))
    }

    @Test
    fun failedToolMarksRunFailed() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(
                    ToolDefinition(
                        name = "fake.echo",
                        description = "失败测试工具",
                        risk = ToolRisk.REQUIRES_APPROVAL,
                    ),
                )

                override fun definition(name: String): ToolDefinition? = availableTools().firstOrNull { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    return ToolExecutionResult(success = false, content = "模拟失败")
                }
            },
        )

        var runId: String? = null
        try {
            runtime.runDemo("conversation-1", "message-1", "失败场景")
        } catch (error: IllegalStateException) {
            runId = ledger.lastRunId
        }

        assertNotNull(runId)
        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("工具执行失败"))
        assertTrue(snapshot.steps.any { it.status == AgentStepStatus.FAILED })
    }

    @Test
    fun cancellationMarksRunAndActiveStepCancelled() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val planningStarted = CompletableDeferred<Unit>()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    planningStarted.complete(Unit)
                    awaitCancellation()
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    error("取消发生在模型规划阶段，不应进入总结")
                }
            },
        )

        val job = launch {
            runtime.runDemo(
                conversationId = "conversation-1",
                userMessageId = "message-1",
                goal = "取消场景",
            )
        }
        planningStarted.await()
        val runId = ledger.lastRunId!!

        job.cancelAndJoin()

        val snapshot = ledger.snapshot(runId)
        assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("取消"))
        assertEquals(AgentStepStatus.CANCELLED, snapshot.steps.single().status)
    }
}

private class FakeAgentLlm : AgentLlm {
    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
        val tool = tools.first()
        return ToolCall(
            name = tool.name,
            arguments = mapOf("goal" to goal),
            risk = tool.risk,
        )
    }

    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
        return """
            Agent 演示任务已完成

            - 目标：$goal
            - 工具：${toolCall.name}
            - 执行结果：${toolResult.content}
            - 验证：工具结果可读，任务进入 COMPLETED 终态
        """.trimIndent()
    }
}

private class InMemoryAgentRunLedger : AgentRunLedger {
    private val runs = linkedMapOf<String, AgentRunRecord>()
    private val steps = linkedMapOf<String, AgentStepRecord>()
    private val events = linkedMapOf<String, RunEventRecord>()
    var lastRunId: String? = null
        private set

    override suspend fun createRun(conversationId: String, userMessageId: String, goal: String): AgentRunRecord {
        val now = System.currentTimeMillis()
        val run = AgentRunRecord(
            id = "run-${UUID.randomUUID()}",
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = goal,
            status = AgentRunStatus.QUEUED,
            result = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        runs[run.id] = run
        lastRunId = run.id
        appendEvent(run.id, "run.created", "created")
        return run
    }

    override suspend fun updateRunStatus(runId: String, status: AgentRunStatus, result: String?, errorMessage: String?) {
        val current = runs.getValue(runId)
        val now = System.currentTimeMillis()
        runs[runId] = current.copy(
            status = status,
            result = result ?: current.result,
            errorMessage = errorMessage ?: current.errorMessage,
            updatedAt = now,
            completedAt = if (status in terminalStatuses) now else current.completedAt,
        )
        appendEvent(runId, "run.status", status.name)
    }

    override suspend fun appendStep(
        runId: String,
        type: String,
        title: String,
        detail: String,
        status: AgentStepStatus,
    ): AgentStepRecord {
        val now = System.currentTimeMillis()
        val step = AgentStepRecord(
            id = "step-${UUID.randomUUID()}",
            runId = runId,
            sequence = steps.values.count { it.runId == runId } + 1,
            type = type,
            status = status,
            title = title,
            detail = detail,
            createdAt = now,
            completedAt = null,
        )
        steps[step.id] = step
        appendEvent(runId, "step.created", title)
        return step
    }

    override suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String?) {
        val current = steps.getValue(stepId)
        steps[stepId] = current.copy(
            status = status,
            detail = detail ?: current.detail,
            completedAt = if (status == AgentStepStatus.COMPLETED || status == AgentStepStatus.FAILED || status == AgentStepStatus.CANCELLED) {
                System.currentTimeMillis()
            } else {
                current.completedAt
            },
        )
        appendEvent(current.runId, "step.status", status.name)
    }

    override suspend fun appendEvent(runId: String, type: String, message: String) {
        val event = RunEventRecord(
            id = "event-${UUID.randomUUID()}",
            runId = runId,
            type = type,
            message = message,
            createdAt = System.currentTimeMillis(),
        )
        events[event.id] = event
    }

    override suspend fun snapshot(runId: String): AgentRunSnapshot {
        return AgentRunSnapshot(
            run = runs.getValue(runId),
            steps = steps.values.filter { it.runId == runId }.sortedBy { it.sequence },
            events = events.values.filter { it.runId == runId }.sortedBy { it.createdAt },
        )
    }

    private val terminalStatuses = setOf(
        AgentRunStatus.COMPLETED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.BUDGET_EXHAUSTED,
    )
}
