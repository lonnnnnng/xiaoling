package com.longdev.xiaoling.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MinimalAgentRuntimeTest {
    @Test
    fun runCompletesWithAuditableSteps() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
        )

        val summary = runtime.run(
            conversationId = "conversation-1",
            userMessageId = "message-1",
            goal = "整理今天的计划",
        )

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertEquals(listOf("llm.plan", "tool.validate", "approval", "tool.execute", "tool.verify", "llm.summarize"), snapshot.steps.map { it.type })
        assertTrue(snapshot.steps.all { it.status == AgentStepStatus.COMPLETED })
        assertTrue(snapshot.events.any { it.type == "approval.granted" })
        assertTrue(snapshot.events.any {
            it.type == "tool.call.proposed" && (it.metadata as? RunEventMetadata.ToolCall)?.toolName == "fake.echo"
        })
        assertTrue(snapshot.events.any {
            it.type == "tool.result" && (it.metadata as? RunEventMetadata.ToolResult)?.success == true
        })
        assertTrue(summary.responseText.contains("执行后验证") || summary.responseText.contains("验证"))
        assertEquals("fake.echo", summary.verifiedContext.toolName)
        assertTrue(summary.verifiedContext.success)
        assertEquals(AgentVerificationStatus.READABLE_ONLY, summary.verifiedContext.verificationStatus)
    }

    @Test
    fun modelSummaryCannotAddFactsToVerifiedRuntimeContext() = runTest {
        val runtime = MinimalAgentRuntime(
            ledger = InMemoryAgentRunLedger(),
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    val tool = tools.single()
                    return ToolCall(
                        name = tool.name,
                        arguments = mapOf("goal" to goal),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    return "已额外执行 notes.create 并保存了一条长期记忆"
                }
            },
        )

        val summary = runtime.run("conversation-1", "message-1", "只执行回显工具")

        assertTrue(!summary.responseText.contains("notes.create"))
        assertTrue(summary.responseText.contains("fake.echo"))
        assertEquals("fake.echo", summary.verifiedContext.toolName)
        assertEquals("fake.echo 已执行：只执行回显工具", summary.verifiedContext.rawResult)
        assertTrue(!summary.verifiedContext.rawResult.contains("notes.create"))
        assertTrue(!summary.verifiedContext.rawResult.contains("长期记忆"))
    }

    @Test
    fun validPresentationSelectionChangesStyleWithoutChangingFacts() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    val tool = tools.single()
                    return ToolCall(
                        name = tool.name,
                        arguments = mapOf("goal" to goal),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    return """{"style":"compact","tone":"friendly"}"""
                }
            },
        )

        val summary = runtime.run("conversation-1", "message-1", "使用友好简洁样式")
        val snapshot = ledger.snapshot(summary.runId)

        assertTrue(summary.responseText.startsWith("任务已完成，结果如下"))
        assertTrue(summary.responseText.contains("- 工具：fake.echo"))
        assertTrue(summary.responseText.contains("- 结果：fake.echo 已执行：使用友好简洁样式"))
        assertTrue(!summary.responseText.contains("Run ID"))
        assertTrue(snapshot.events.none { it.type == "llm.summarize.fallback" })
    }

    @Test
    fun verifiedContextCodecRoundTripsStructuredEvidence() {
        val context = VerifiedAgentContext(
            runId = "run-1",
            toolName = "notes.create",
            arguments = mapOf("title" to "周报", "content" to "第一行\n第二行"),
            success = true,
            verificationStatus = AgentVerificationStatus.VERIFIED,
            rawResult = "已创建并验证笔记：周报",
        )

        val restored = VerifiedAgentContextCodec.decode(VerifiedAgentContextCodec.encode(context))

        assertEquals(context, restored)
    }

    @Test
    fun structuredRunEventMetadataPreservesSpecialCharactersWithoutEncodingJsonInMessage() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
        )

        val summary = runtime.run(
            conversationId = "conversation-1",
            userMessageId = "message-1",
            goal = "包含引号\"和换行\n的任务",
        )

        val snapshot = ledger.snapshot(summary.runId)
        val proposed = snapshot.events.single { it.type == "tool.call.proposed" }
        val result = snapshot.events.single { it.type == "tool.result" }
        val proposedMetadata = proposed.metadata as RunEventMetadata.ToolCall
        val resultMetadata = result.metadata as RunEventMetadata.ToolResult
        assertEquals("fake.echo", proposedMetadata.toolName)
        assertEquals("包含引号\"和换行\n的任务", proposedMetadata.arguments["goal"])
        assertEquals(true, resultMetadata.success)
        assertTrue(proposed.message.startsWith("模型提出工具调用"))
        assertTrue(!proposed.message.trimStart().startsWith("{"))
    }

    @Test
    fun openAiToolCallParserDoesNotAutoFillMissingRequiredArguments() {
        val tool = FakeToolRegistry().availableTools().single()

        val call = AgentToolCallParser.parse(
            raw = """{"tool":"fake.echo","arguments":{}}""",
            tools = listOf(tool),
        )

        assertEquals("fake.echo", call.name)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, call.risk)
        assertTrue(call.arguments.isEmpty())
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
            runtime.run("conversation-1", "message-1", "失败场景")
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
    fun safeToolExecutesWithoutInteractiveApproval() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    val tool = tools.single()
                    return ToolCall(
                        name = tool.name,
                        arguments = emptyMap(),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    return toolResult.content
                }
            },
            toolRegistry = object : ToolRegistry {
                private val tool = ToolDefinition(
                    name = "app.current_time",
                    description = "读取当前时间",
                    risk = ToolRisk.SAFE,
                )

                override fun availableTools(): List<ToolDefinition> = listOf(tool)

                override fun definition(name: String): ToolDefinition? = tool.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    return ToolExecutionResult(success = true, content = "当前时间：2026-07-17 08:30:45")
                }
            },
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    error("SAFE 工具不应请求交互审批")
                }
            },
        )

        val summary = runtime.run("conversation-1", "message-1", "查看应用信息")

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertTrue(snapshot.steps.none { it.type == "approval" })
        assertTrue(snapshot.events.any { it.type == "approval.skipped" && it.message.contains("SAFE") })
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
            runtime.run(
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

    @Test
    fun rejectedApprovalMarksRunFailedAndKeepsDeniedEvent() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    return ApprovalDecision(
                        approved = false,
                        reason = "用户拒绝执行",
                    )
                }
            },
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "拒绝审批")
        } catch (error: IllegalStateException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("工具未获批准"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == "approval" }.status)
        assertTrue(snapshot.events.any {
            it.type == "approval.denied" &&
                (it.metadata as? RunEventMetadata.ApprovalDecision)?.reason == "用户拒绝执行"
        })
    }

    @Test
    fun approvalWaitDoesNotConsumeExecutionTimeoutBudget() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    delay(2_000)
                    return ApprovalDecision(
                        approved = true,
                        reason = "用户阅读审批详情后批准",
                    )
                }
            },
            options = AgentRuntimeOptions(
                runTimeoutMs = 500,
                modelStepTimeoutMs = 5_000,
                toolStepTimeoutMs = 5_000,
            ),
        )

        val summary = runtime.run("conversation-1", "message-1", "审批等待不计入执行预算")

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertEquals(AgentStepStatus.COMPLETED, snapshot.steps.single { it.type == "approval" }.status)
        assertTrue(snapshot.events.any {
            it.type == "approval.granted" &&
                (it.metadata as? RunEventMetadata.ApprovalDecision)?.reason == "用户阅读审批详情后批准"
        })
    }

    @Test
    fun missingRequiredToolArgumentFailsAtValidationStep() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    val tool = tools.first()
                    return ToolCall(
                        name = tool.name,
                        arguments = emptyMap(),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    error("参数校验失败时不应进入总结")
                }
            },
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "参数缺失")
        } catch (error: IllegalArgumentException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("缺少必填参数"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == "tool.validate" }.status)
    }

    @Test
    fun maxToolCallBudgetZeroMarksRunBudgetExhausted() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
            options = AgentRuntimeOptions(maxToolCalls = 0),
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "预算耗尽")
        } catch (error: AgentBudgetExceededException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("工具调用次数"))
        assertTrue(snapshot.events.any { it.type == "run.budget_exhausted" })
    }

    @Test
    fun modelPlanningTimeoutMarksRunBudgetExhausted() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    delay(2_000)
                    val tool = tools.first()
                    return ToolCall(
                        name = tool.name,
                        arguments = mapOf("goal" to goal),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    error("模型规划超时后不应进入总结")
                }
            },
            options = AgentRuntimeOptions(modelStepTimeoutMs = 1_000),
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "规划超时")
        } catch (error: AgentTimeoutException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("超时"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single().status)
    }

    @Test
    fun summaryTimeoutAfterVerifiedToolUsesFallbackResponse() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    val tool = tools.first()
                    return ToolCall(
                        name = tool.name,
                        arguments = mapOf("goal" to goal),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    delay(2_000)
                    return "不应等到模型总结"
                }
            },
            options = AgentRuntimeOptions(
                runTimeoutMs = 5_000,
                modelStepTimeoutMs = 1_000,
            ),
        )

        val summary = runtime.run("conversation-1", "message-1", "总结超时兜底")

        val snapshot = ledger.snapshot(summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertTrue(summary.responseText.contains("Agent 任务已完成"))
        assertEquals(AgentStepStatus.COMPLETED, snapshot.steps.single { it.type == "llm.summarize" }.status)
        assertTrue(snapshot.events.any { it.type == "llm.summarize.fallback" && it.message.contains("模型总结") })
    }

    @Test
    fun blankSummaryAfterVerifiedToolUsesFallbackResponse() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    val tool = tools.first()
                    return ToolCall(
                        name = tool.name,
                        arguments = mapOf("goal" to goal),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    return ""
                }
            },
        )

        val summary = runtime.run("conversation-1", "message-1", "空总结兜底")

        val snapshot = ledger.snapshot(summary.runId)
        val summaryStep = snapshot.steps.single { it.type == "llm.summarize" }
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertTrue(summary.responseText.contains("Agent 任务已完成"))
        assertEquals(AgentStepStatus.COMPLETED, summaryStep.status)
        assertTrue(summaryStep.detail.contains("模型总结为空"))
        assertTrue(snapshot.events.any { it.type == "llm.summarize.fallback" && it.message.contains("模型总结为空") })
    }

    @Test
    fun wholeRunTimeoutKeepsRunTimeoutReasonWhenItInterruptsModelStep() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    delay(2_000)
                    val tool = tools.first()
                    return ToolCall(
                        name = tool.name,
                        arguments = mapOf("goal" to goal),
                        risk = tool.risk,
                    )
                }

                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
                    error("整次 Run 超时后不应进入总结")
                }
            },
            options = AgentRuntimeOptions(
                runTimeoutMs = 500,
                modelStepTimeoutMs = 5_000,
            ),
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "整次超时")
        } catch (error: AgentTimeoutException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, snapshot.run.status)
        assertEquals("Agent Run 超时：500ms", snapshot.run.errorMessage)
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single().status)
    }

    @Test
    fun toolExecutionTimeoutMarksRunBudgetExhausted() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
            toolRegistry = object : ToolRegistry {
                private val tool = ToolDefinition(
                    name = "fake.echo",
                    description = "慢工具",
                    risk = ToolRisk.REQUIRES_APPROVAL,
                    inputSchema = listOf(ToolInputField("goal", "用户目标", required = true)),
                )

                override fun availableTools(): List<ToolDefinition> = listOf(tool)

                override fun definition(name: String): ToolDefinition? = availableTools().firstOrNull { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    delay(2_000)
                    return ToolExecutionResult(success = true, content = "不应返回")
                }
            },
            options = AgentRuntimeOptions(toolStepTimeoutMs = 1_000),
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "工具超时")
        } catch (error: AgentTimeoutException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("工具执行 fake.echo 超时"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == "tool.execute" }.status)
        assertTrue(snapshot.events.any { it.type == "run.timeout" })
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

    override suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata?) {
        val event = RunEventRecord(
            id = "event-${UUID.randomUUID()}",
            runId = runId,
            type = type,
            message = message,
            createdAt = System.currentTimeMillis(),
            metadata = metadata,
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
