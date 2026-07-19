package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.UUID

class MinimalAgentRuntimeTest {
    @Test
    fun invalidPlanningJsonStillPersistsReturnedRequestTelemetry() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                        {
                          "choices":[{"message":{"content":"not-json"}}],
                          "usage":{"prompt_tokens":20,"completion_tokens":2,"total_tokens":22}
                        }
                    """.trimIndent(),
                ),
            )
            val ledger = InMemoryAgentRunLedger()
            val runtime = MinimalAgentRuntime(
                ledger = ledger,
                llm = OpenAiAgentLlm(
                    client = OpenAiCompatibleClient(),
                    config = ProviderRequestConfig(
                        baseUrl = server.url("/v1").toString(),
                        apiKey = "test-key",
                        model = "gpt-test",
                    ),
                    summarySystemPrompt = "只返回总结样式 JSON",
                ),
            )

            runCatching {
                runtime.run("conversation-invalid-plan", "message-invalid-plan", "回显失败遥测")
            }

            val snapshot = ledger.snapshot(ledger.lastRunId!!)
            assertTrue(
                "eventTypes=${snapshot.events.map { it.type }}",
                snapshot.events.any { it.type == AgentEventTypes.LLM_REQUEST_COMPLETED },
            )
            val telemetry = snapshot.events.first { it.type == AgentEventTypes.LLM_REQUEST_COMPLETED }
                .metadata as RunEventMetadata.LlmRequest
            assertEquals(AgentLlmPhase.PLAN, telemetry.phase)
            assertEquals(22L, telemetry.totalTokens)
            assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun successfulModelCallsPersistRequestTelemetryForPlanAndSummary() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val delegate = FakeAgentLlm()
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall =
                    delegate.proposeToolCall(goal, tools)

                override suspend fun proposeNextActionWithTelemetry(
                    goal: String,
                    tools: List<ToolDefinition>,
                    completedTools: List<AgentToolExecution>,
                ): AgentLlmCallResult<AgentPlanDecision> {
                    val decision = if (completedTools.isEmpty()) {
                        AgentPlanDecision.CallTool(delegate.proposeToolCall(goal, tools))
                    } else {
                        AgentPlanDecision.Complete
                    }
                    return AgentLlmCallResult(decision, telemetry(model = "gpt-plan"))
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = """{"style":"compact","tone":"neutral"}"""

                override suspend fun summarizeWithTelemetry(
                    goal: String,
                    completedTools: List<AgentToolExecution>,
                ): AgentLlmCallResult<String> = AgentLlmCallResult(
                    value = """{"style":"compact","tone":"neutral"}""",
                    telemetry = telemetry(model = "gpt-summary"),
                )

                private fun telemetry(model: String) = AgentLlmRequestTelemetry(
                    model = model,
                    latencyMs = 1_250L,
                    firstByteLatencyMs = 320L,
                    promptBytes = 4_096,
                    inputTokens = 120L,
                    outputTokens = 30L,
                    totalTokens = 150L,
                )
            },
        )

        val summary = runtime.run("conversation-telemetry", "message-telemetry", "回显遥测")
        val telemetry = ledger.snapshot(summary.runId).events.mapNotNull { event ->
            event.metadata as? RunEventMetadata.LlmRequest
        }

        assertEquals(listOf(AgentLlmPhase.PLAN, AgentLlmPhase.PLAN, AgentLlmPhase.SUMMARIZE), telemetry.map { it.phase })
        assertEquals(listOf("gpt-plan", "gpt-plan", "gpt-summary"), telemetry.map { it.model })
        assertEquals(450L, telemetry.sumOf { it.totalTokens ?: 0L })
    }

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
        assertEquals(
            listOf("llm.plan", "tool.validate", "approval", "tool.execute", "tool.verify", "llm.plan", "llm.summarize"),
            snapshot.steps.map { it.type },
        )
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
    fun selectedSkillsAreWrittenToRunAudit() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val skill = AgentSkillDefinition(
            id = "fake-echo-skill",
            name = "回显测试",
            description = "测试 Skill 审计",
            instructions = "使用回显工具。",
            toolNames = setOf("fake.echo"),
            keywords = setOf("回显"),
        )

        val summary = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
        ).run(
            conversationId = "conversation-skill-audit",
            userMessageId = "message-skill-audit",
            goal = "回显 Skill 审计",
            selectedSkills = listOf(skill),
        )

        val selected = ledger.snapshot(summary.runId).events.single { it.type == "skill.selected" }
        assertTrue(selected.message.contains(skill.name))
        assertEquals("${skill.id}@${skill.version}", (selected.metadata as RunEventMetadata.Reason).reason)
    }

    @Test
    fun selectedAgentProfileSnapshotIsWrittenBeforeExecutionAudit() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val profile = AgentProfileSnapshot(
            id = "agent-profile-audit",
            name = "审计 Agent",
            avatar = "审",
            providerId = "provider-audit",
            model = "gpt-audit",
            apiMode = com.longdev.xiaoling.model.ApiMode.RESPONSES,
            systemPrompt = "使用审计风格",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("fake.echo"),
            allowedSkillIds = emptyList(),
            memoryEnabled = true,
        )

        val summary = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
        ).run(
            conversationId = "conversation-profile-audit",
            userMessageId = "message-profile-audit",
            goal = "回显 Profile 审计",
            agentProfile = profile,
        )

        val events = ledger.snapshot(summary.runId).events
        val selectedIndex = events.indexOfFirst { it.type == AgentEventTypes.PROFILE_SELECTED }
        val toolIndex = events.indexOfFirst { it.type == "tool.call.proposed" }
        assertTrue(selectedIndex >= 0)
        assertTrue(selectedIndex < toolIndex)
        assertEquals(profile, (events[selectedIndex].metadata as RunEventMetadata.AgentProfileSelection).profile)
    }

    @Test
    fun approvedPendingRunResumesFirstToolThenContinuesPlanningOnSameRun() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val created = ledger.createRun(
            conversationId = "conversation-resume",
            userMessageId = "message-resume",
            goal = "恢复待审批回显任务",
        )
        ledger.updateRunStatus(created.id, AgentRunStatus.WAITING_APPROVAL)
        ledger.appendEvent(
            runId = created.id,
            type = "memory.recall.disabled",
            message = "本次 Run 已关闭长期记忆召回",
            metadata = RunEventMetadata.Reason("用户关闭本次 Run 的长期记忆召回"),
        )
        val approvalStep = ledger.appendStep(
            runId = created.id,
            type = "approval",
            title = "应用侧审批",
            detail = "等待应用侧审批 fake.echo",
            status = AgentStepStatus.RUNNING,
        )
        val detail = AgentRunDetailRecord(
            snapshot = ledger.snapshot(created.id),
            approvals = listOf(
                ApprovalRequestRecord(
                    id = "approval-resume",
                    runId = created.id,
                    conversationId = created.conversationId,
                    toolCallId = "tool-call-resume",
                    toolName = "fake.echo",
                    toolDescription = "回显任务",
                    risk = ToolRisk.REQUIRES_APPROVAL,
                    arguments = mapOf("goal" to created.goal),
                    status = ApprovalRequestStatus.PENDING,
                    decisionReason = null,
                    createdAt = 1L,
                    expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
                    decidedAt = null,
                ),
            ),
        )

        var restoredRunContext: AgentToolExecutionContext? = null
        val delegateRegistry = FakeToolRegistry()
        val contextAwareRegistry = object : ToolRegistry, AgentRunContextAwareToolRegistry {
            override fun bindRunContext(context: AgentToolExecutionContext) {
                restoredRunContext = context
            }

            override fun availableTools(): List<ToolDefinition> = delegateRegistry.availableTools()

            override fun definition(name: String): ToolDefinition? = delegateRegistry.definition(name)

            override suspend fun execute(call: ToolCall): ToolExecutionResult = delegateRegistry.execute(call)
        }
        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = contextAwareRegistry,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    error("恢复入口不能重新规划已经批准的第一个工具")
                }

                override suspend fun proposeNextAction(
                    goal: String,
                    tools: List<ToolDefinition>,
                    completedTools: List<AgentToolExecution>,
                ): AgentPlanDecision {
                    return when (completedTools.size) {
                        1 -> AgentPlanDecision.CallTool(
                            ToolCall(
                                name = "fake.echo",
                                arguments = mapOf("goal" to "恢复后的第二步"),
                                risk = ToolRisk.REQUIRES_APPROVAL,
                            ),
                        )
                        else -> AgentPlanDecision.Complete
                    }
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = """{"style":"compact","tone":"neutral"}"""
            },
        ).resumeApprovedRun(
            detail = detail,
            approval = detail.approvals.single(),
            approvalDecision = ApprovalDecision(approved = true, reason = "用户确认继续"),
        )

        val snapshot = ledger.snapshot(created.id)
        assertEquals(created.id, summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
        assertEquals(AgentStepStatus.COMPLETED, snapshot.steps.single { it.id == approvalStep.id }.status)
        assertEquals(2, snapshot.steps.count { it.type == "llm.plan" })
        assertEquals(2, snapshot.events.count { it.type == "approval.granted" })
        assertEquals(2, snapshot.events.count { it.type == "tool.result" })
        assertEquals(2, snapshot.events.count { it.type == "tool.verify" })
        assertEquals(false, restoredRunContext?.memoryRecallEnabled)
        assertEquals(listOf(created.goal, "恢复后的第二步"), summary.verifiedContext.toolExecutions.map {
            it.arguments.getValue("goal")
        })
        val firstPlanningSequence = snapshot.steps.first { it.type == "llm.plan" }.sequence
        val firstVerifySequence = snapshot.steps.first { it.type == AgentStepTypes.TOOL_VERIFY }.sequence
        assertTrue(firstPlanningSequence > firstVerifySequence)
    }

    @Test
    fun resumedToolFailureMarksOriginalRunFailedAndRequiresConfirmedRetry() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val created = ledger.createRun(
            conversationId = "conversation-resume-failure",
            userMessageId = "message-resume-failure",
            goal = "恢复后工具执行失败",
        )
        ledger.updateRunStatus(created.id, AgentRunStatus.WAITING_APPROVAL)
        ledger.appendStep(
            runId = created.id,
            type = "approval",
            title = "应用侧审批",
            detail = "等待应用侧审批 fake.echo",
            status = AgentStepStatus.RUNNING,
        )
        val approval = ApprovalRequestRecord(
            id = "approval-resume-failure",
            runId = created.id,
            conversationId = created.conversationId,
            toolCallId = "tool-call-resume-failure",
            toolName = "fake.echo",
            toolDescription = "回显任务",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("goal" to created.goal),
            status = ApprovalRequestStatus.PENDING,
            decisionReason = null,
            createdAt = 1L,
            expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
            decidedAt = null,
        )
        val detail = AgentRunDetailRecord(
            snapshot = ledger.snapshot(created.id),
            approvals = listOf(approval),
        )
        val failedRegistry = object : ToolRegistry {
            private val tool = FakeToolRegistry().availableTools().single()
            override fun availableTools(): List<ToolDefinition> = listOf(tool)
            override fun definition(name: String): ToolDefinition? = tool.takeIf { it.name == name }
            override suspend fun execute(call: ToolCall): ToolExecutionResult {
                return ToolExecutionResult(success = false, content = "恢复后的工具执行失败")
            }
        }

        val failure = runCatching {
            MinimalAgentRuntime(
                ledger = ledger,
                toolRegistry = failedRegistry,
                llm = FakeAgentLlm(),
            ).resumeApprovedRun(
                detail = detail,
                approval = approval,
                approvalDecision = ApprovalDecision(approved = true, reason = "用户确认继续"),
            )
        }.exceptionOrNull()

        val failedSnapshot = ledger.snapshot(created.id)
        val retryEligibility = AgentTaskRetryPolicy.evaluate(
            AgentRunDetailRecord(
                snapshot = failedSnapshot,
                approvals = listOf(approval.copy(status = ApprovalRequestStatus.APPROVED)),
            ),
        ) as AgentTaskRetryEligibility.Retryable
        assertEquals(created.id, ledger.lastRunId)
        assertNotNull(failure)
        assertEquals(AgentRunStatus.FAILED, failedSnapshot.run.status)
        assertTrue(failedSnapshot.steps.any { it.type == AgentStepTypes.TOOL_EXECUTE && it.status == AgentStepStatus.FAILED })
        assertTrue(failedSnapshot.events.any { it.type == "run.failed" })
        assertTrue(retryEligibility.requiresConfirmation)
    }

    @Test
    fun committedToolRecoveryOnlyRechecksOperationThenCompletesOriginalRun() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun(
            conversationId = "conversation-committed-recovery",
            userMessageId = "message-committed-recovery",
            goal = "恢复已写入笔记的验证",
        )
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField("title", "笔记标题", required = true),
                ToolInputField("content", "笔记正文", required = true),
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val call = ToolCall(
            id = "tool-call-committed-recovery",
            name = definition.name,
            arguments = mapOf("title" to "恢复笔记", "content" to "已经持久化"),
            risk = definition.risk,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-committed-recovery",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val persistedResult = ToolExecutionResult(
            success = true,
            verified = true,
            content = "已创建并验证笔记：恢复笔记",
            executionReceipt = receipt,
        )
        ledger.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        ledger.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val execution = ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = "进程中断前已写入结果",
            status = AgentStepStatus.RUNNING,
        )
        ledger.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = persistedResult.content,
                durationMs = 12L,
                success = true,
                verified = true,
                toolCallId = call.id,
                replaySafety = definition.replaySafety,
                executionReceipt = receipt,
            ),
        )
        var executeCount = 0
        var verificationCount = 0
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = listOf(definition)

            override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

            override suspend fun execute(call: ToolCall): ToolExecutionResult {
                executeCount += 1
                error("验证阶段恢复不得重放工具写入")
            }

            override suspend fun verifyCommittedEffect(
                call: ToolCall,
                receipt: ToolExecutionReceipt,
            ): ToolExecutionResult {
                verificationCount += 1
                assertEquals("note-committed-recovery", receipt.operationId)
                return persistedResult
            }

            override fun supportsCommittedEffectVerification(toolName: String): Boolean = toolName == definition.name
        }
        val detail = AgentRunDetailRecord(snapshot = ledger.snapshot(run.id), approvals = emptyList())

        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = FakeAgentLlm(),
        ).resumeCommittedToolRun(detail)

        val recovered = ledger.snapshot(run.id)
        assertEquals(run.id, summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, recovered.run.status)
        assertEquals(0, executeCount)
        assertEquals(1, verificationCount)
        assertEquals(AgentStepStatus.COMPLETED, recovered.steps.single { it.id == execution.id }.status)
        assertEquals(1, recovered.events.count { it.type == "tool.result" })
        assertEquals(1, recovered.events.count { it.type == "tool.verify" })
        assertEquals(0, recovered.steps.count { it.type == AgentStepTypes.LLM_PLAN })
        assertTrue(summary.responseText.contains("恢复笔记"))
    }

    @Test
    fun committedMemoryRecoveryFailurePersistsStableReasonAndSuggestedAction() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun(
            conversationId = "conversation-memory-recovery-failed",
            userMessageId = "message-memory-recovery-failed",
            goal = "恢复已提交长期记忆",
        )
        val definition = ToolDefinition(
            name = "memory.remember",
            description = "保存长期记忆",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(ToolInputField("note", "记忆内容", required = true)),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val call = ToolCall(
            id = "tool-call-memory-recovery-failed",
            name = definition.name,
            arguments = mapOf("note" to "用户喜欢紧凑界面"),
            risk = definition.risk,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "memory-recovery-failed",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        ledger.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        ledger.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = "结果落库后进程终止",
            status = AgentStepStatus.RUNNING,
        )
        ledger.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "已保存长期记忆",
                durationMs = 8L,
                success = true,
                verified = true,
                toolCallId = call.id,
                replaySafety = definition.replaySafety,
                executionReceipt = receipt,
            ),
        )
        val failure = ToolRecoveryFailure(
            code = "MEMORY_DISABLED",
            reason = "原长期记忆已禁用",
            suggestedAction = "请先启用该记忆，再创建新 Run 重试。",
        )
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = listOf(definition)
            override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }
            override suspend fun execute(call: ToolCall): ToolExecutionResult = error("恢复验证不得重放写入")
            override suspend fun verifyCommittedEffect(
                call: ToolCall,
                receipt: ToolExecutionReceipt,
            ): ToolExecutionResult = ToolExecutionResult(
                success = false,
                verified = false,
                content = "长期记忆恢复验证失败：MEMORY_DISABLED（原长期记忆已禁用）",
                executionReceipt = receipt,
                recoveryFailure = failure,
            )

            override fun supportsCommittedEffectVerification(toolName: String): Boolean = toolName == definition.name
        }

        val error = runCatching {
            MinimalAgentRuntime(
                ledger = ledger,
                toolRegistry = registry,
                llm = FakeAgentLlm(),
            ).resumeCommittedToolRun(
                AgentRunDetailRecord(snapshot = ledger.snapshot(run.id), approvals = emptyList()),
            )
        }.exceptionOrNull()

        val snapshot = ledger.snapshot(run.id)
        val event = snapshot.events.single { it.type == AgentEventTypes.RECOVERY_FAILED }
        val metadata = event.metadata as RunEventMetadata.RecoveryFailure
        assertTrue(error is IllegalStateException)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertEquals("memory.remember", metadata.toolName)
        assertEquals("MEMORY_DISABLED", metadata.code)
        assertEquals("原长期记忆已禁用", metadata.reason)
        assertEquals("请先启用该记忆，再创建新 Run 重试。", metadata.suggestedAction)
    }

    @Test
    fun committedToolRecoveryKeepsPreviouslyVerifiedToolFacts() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun(
            conversationId = "conversation-multi-tool-recovery",
            userMessageId = "message-multi-tool-recovery",
            goal = "先读取时间再创建笔记",
        )
        val previousCall = ToolCall(
            id = "tool-call-time-before-recovery",
            name = "app.current_time",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        ledger.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${previousCall.name}",
            RunEventMetadata.ToolCall(previousCall.id, previousCall.name, previousCall.risk, previousCall.arguments),
        )
        ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = previousCall.name,
            status = AgentStepStatus.COMPLETED,
        )
        ledger.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：${previousCall.name}",
            RunEventMetadata.ToolResult(
                toolName = previousCall.name,
                content = "当前时间：09:30",
                durationMs = 5L,
                success = true,
                verified = null,
                toolCallId = previousCall.id,
            ),
        )
        ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_VERIFY,
            title = "验证工具结果",
            detail = previousCall.name,
            status = AgentStepStatus.COMPLETED,
        )
        ledger.appendEvent(
            run.id,
            "tool.verify",
            "工具验证通过：${previousCall.name}",
            RunEventMetadata.ToolVerification(previousCall.name, ToolVerificationStatus.PASSED),
        )

        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val currentCall = ToolCall(
            id = "tool-call-note-after-time",
            name = definition.name,
            arguments = mapOf("title" to "时间记录", "content" to "当前时间 09:30"),
            risk = definition.risk,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = currentCall.id,
            operationId = "note-after-time",
            idempotencyKey = currentCall.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val recoveredResult = ToolExecutionResult(
            success = true,
            verified = true,
            content = "已创建并验证笔记：时间记录",
            executionReceipt = receipt,
        )
        ledger.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${currentCall.name}",
            RunEventMetadata.ToolCall(currentCall.id, currentCall.name, currentCall.risk, currentCall.arguments),
        )
        ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = currentCall.name,
            status = AgentStepStatus.RUNNING,
        )
        ledger.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：${currentCall.name}",
            RunEventMetadata.ToolResult(
                toolName = currentCall.name,
                content = recoveredResult.content,
                durationMs = 7L,
                success = true,
                verified = true,
                toolCallId = currentCall.id,
                replaySafety = definition.replaySafety,
                executionReceipt = receipt,
            ),
        )
        ledger.updateRunStatus(run.id, AgentRunStatus.EXECUTING)

        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = listOf(definition)
            override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }
            override suspend fun execute(call: ToolCall): ToolExecutionResult = error("不得重放写工具")
            override suspend fun verifyCommittedEffect(
                call: ToolCall,
                receipt: ToolExecutionReceipt,
            ): ToolExecutionResult = recoveredResult

            override fun supportsCommittedEffectVerification(toolName: String): Boolean = toolName == definition.name
        }

        val summary = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = FakeAgentLlm(),
        ).resumeCommittedToolRun(
            AgentRunDetailRecord(snapshot = ledger.snapshot(run.id), approvals = emptyList()),
        )

        assertTrue(summary.responseText.contains("当前时间：09:30"))
        assertTrue(summary.responseText.contains("时间记录"))
        assertEquals(
            listOf(previousCall.name, currentCall.name),
            summary.verifiedContext.toolExecutions.map { it.toolName },
        )
    }

    @Test
    fun processTerminationAfterCommittedToolResultLeavesRecoverableVerificationEvidence() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField("title", "笔记标题", required = true),
                ToolInputField("content", "笔记正文", required = true),
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = listOf(definition)

            override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

            override suspend fun execute(call: ToolCall): ToolExecutionResult = ToolExecutionResult(
                success = true,
                verified = true,
                content = "已创建并验证笔记：中断注入",
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "note-fault-injection",
                    idempotencyKey = call.id,
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            )

            override fun supportsCommittedEffectVerification(toolName: String): Boolean = toolName == definition.name
        }
        val call = ToolCall(
            id = "tool-call-fault-injection",
            name = definition.name,
            arguments = mapOf("title" to "中断注入", "content" to "结果落库后终止"),
            risk = definition.risk,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = registry,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = call

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("故障注入后不应进入总结")
            },
            faultInjector = object : AgentRuntimeFaultInjector {
                override fun afterToolResultPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) {
                    throw AgentProcessTerminationSimulation()
                }
            },
        )

        val failure = runCatching {
            runtime.run("conversation-fault-injection", "message-fault-injection", "注入进程中断")
        }.exceptionOrNull()

        val snapshot = ledger.snapshot(requireNotNull(ledger.lastRunId))
        val detail = AgentRunDetailRecord(snapshot = snapshot, approvals = emptyList())
        assertTrue(failure is AgentProcessTerminationSimulation)
        assertEquals(AgentRunStatus.EXECUTING, snapshot.run.status)
        assertEquals(AgentStepStatus.RUNNING, snapshot.steps.single { it.type == AgentStepTypes.TOOL_EXECUTE }.status)
        assertEquals(1, snapshot.events.count { it.type == "tool.result" })
        assertEquals(0, snapshot.events.count { it.type == "tool.verify" })
        assertEquals(
            AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION,
            AgentRunResumePolicy.assess(
                detail,
                registry::definition,
                registry::supportsCommittedEffectVerification,
            ).kind,
        )
    }

    @Test
    fun disablingMemoryRecallWritesAnAuditEventForThisRunOnly() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val summary = MinimalAgentRuntime(
            ledger = ledger,
            llm = FakeAgentLlm(),
        ).run(
            conversationId = "conversation-1",
            userMessageId = "message-1",
            goal = "本轮不使用记忆",
            memoryRecallEnabled = false,
        )

        val event = ledger.snapshot(summary.runId).events.single { it.type == "memory.recall.disabled" }
        assertEquals("用户关闭本次 Run 的长期记忆召回", (event.metadata as RunEventMetadata.Reason).reason)
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
            memoryIdsUsed = listOf("memory-1", "memory-2"),
        )

        val restored = VerifiedAgentContextCodec.decode(VerifiedAgentContextCodec.encode(context))

        assertEquals(context, restored)
    }

    @Test
    fun verifiedContextCodecRoundTripsMultiToolEvidence() {
        val context = VerifiedAgentContext(
            runId = "run-multi",
            toolName = "notes.create",
            arguments = mapOf("title" to "周报"),
            success = true,
            verificationStatus = AgentVerificationStatus.VERIFIED,
            rawResult = "已创建周报",
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "notes.search",
                    arguments = mapOf("query" to "本周"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "找到 2 条笔记",
                    memoryIdsUsed = listOf("memory-1"),
                ),
                VerifiedToolExecution(
                    toolName = "notes.create",
                    arguments = mapOf("title" to "周报"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = "已创建周报",
                ),
            ),
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
    fun openAiPlanDecisionParserAcceptsExplicitCompletion() {
        val decision = AgentToolCallParser.parseDecision(
            raw = """{"action":"complete"}""",
            tools = FakeToolRegistry().availableTools(),
        )

        assertEquals(AgentPlanDecision.Complete, decision)
    }

    @Test
    fun openAiPlanDecisionParserAcceptsToolNameRepeatedAsAction() {
        val tool = FakeToolRegistry().availableTools().single()

        val decision = AgentToolCallParser.parseDecision(
            raw = """{"action":"fake.echo","tool":"fake.echo","arguments":{"goal":"兼容模型"}}""",
            tools = listOf(tool),
        )

        val call = (decision as AgentPlanDecision.CallTool).toolCall
        assertEquals("fake.echo", call.name)
        assertEquals(mapOf("goal" to "兼容模型"), call.arguments)
    }

    @Test
    fun openAiPlanDecisionParserRejectsActionThatDoesNotMatchDeclaredTool() {
        val failure = runCatching {
            AgentToolCallParser.parseDecision(
                raw = """{"action":"notes.search","tool":"fake.echo","arguments":{"goal":"不一致"}}""",
                tools = FakeToolRegistry().availableTools(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("未知 Agent 规划动作"))
    }

    @Test
    fun openAiToolCallParserKeepsJsonPrimitivesValidForLogicalSchemaTypes() {
        val tool = ToolDefinition(
            name = "test.typed",
            description = "验证模型参数类型",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField("limit", "条数", required = true, type = ToolInputType.INTEGER),
                ToolInputField("enabled", "是否启用", required = true, type = ToolInputType.BOOLEAN),
            ),
        )

        val call = AgentToolCallParser.parse(
            raw = """{"tool":"test.typed","arguments":{"limit":5,"enabled":true}}""",
            tools = listOf(tool),
        )

        assertEquals(mapOf("limit" to "5", "enabled" to "true"), call.arguments)
        assertTrue(tool.validateArguments(call.arguments).isValid)
    }

    @Test
    fun openAiToolCallParserRejectsArgumentsWithWrongJsonShapeOrPrimitiveType() {
        val tool = ToolDefinition(
            name = "test.typed",
            description = "验证模型原始 JSON 类型",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField("limit", "条数", required = true, type = ToolInputType.INTEGER),
                ToolInputField("query", "关键词", required = true, type = ToolInputType.STRING),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            AgentToolCallParser.parse(
                raw = """{"tool":"test.typed","arguments":[]}""",
                tools = listOf(tool),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            AgentToolCallParser.parse(
                raw = """{"tool":"test.typed","arguments":{"limit":"5","query":"内容"}}""",
                tools = listOf(tool),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            AgentToolCallParser.parse(
                raw = """{"tool":"test.typed","arguments":{"limit":5,"query":{"nested":true}}}""",
                tools = listOf(tool),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            AgentToolCallParser.parse(
                raw = """{"tool":"test.typed","arguments":{"limit":18446744073709551621,"query":"内容"}}""",
                tools = listOf(tool),
            )
        }
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
                        inputSchema = listOf(
                            ToolInputField(
                                name = "goal",
                                description = "测试目标",
                                required = true,
                            ),
                        ),
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
    fun missingAndroidPermissionFailsBeforeApprovalAndExecution() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var executed = false
        val definition = ToolDefinition(
            name = "device.camera_snapshot",
            description = "读取相机画面",
            risk = ToolRisk.REQUIRES_APPROVAL,
            permissionPolicy = ToolPermissionPolicy(
                requiredAndroidPermissions = setOf("android.permission.CAMERA"),
                supportsBackground = false,
            ),
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = ToolRisk.SAFE)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("权限校验失败时不应进入总结")
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    executed = true
                    return ToolExecutionResult(success = true, content = "不应执行")
                }
            },
            permissionChecker = ToolPermissionChecker { requiredPermissions -> requiredPermissions },
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "拍摄照片")
        } catch (error: IllegalStateException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("缺少 Android 权限"))
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("android.permission.CAMERA"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == "tool.validate" }.status)
        assertTrue(!executed)
        assertTrue(snapshot.events.none { it.type.startsWith("approval.") })
    }

    @Test
    fun permissionRevokedDuringApprovalFailsBeforeToolExecution() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var permissionGranted = true
        var executed = false
        val cameraPermission = "android.permission.CAMERA"
        val definition = ToolDefinition(
            name = "device.camera_snapshot",
            description = "拍摄并返回当前画面",
            risk = ToolRisk.REQUIRES_APPROVAL,
            permissionPolicy = ToolPermissionPolicy(
                requiredAndroidPermissions = setOf(cameraPermission),
            ),
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("执行前权限撤销后不应进入总结")
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    executed = true
                    return ToolExecutionResult(success = true, content = "不应执行")
                }
            },
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    permissionGranted = false
                    return ApprovalDecision(approved = true, reason = "用户批准后系统权限被撤销")
                }
            },
            permissionChecker = ToolPermissionChecker { requiredPermissions ->
                if (permissionGranted) emptySet() else requiredPermissions
            },
        )

        runCatching {
            runtime.run("conversation-permission-approval", "message-permission-approval", "拍摄照片")
        }

        val snapshot = ledger.snapshot(ledger.lastRunId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("执行前"))
        assertTrue(snapshot.run.errorMessage.orEmpty().contains(cameraPermission))
        assertTrue(snapshot.events.any { it.type == "approval.granted" })
        assertTrue(snapshot.steps.none { it.type == AgentStepTypes.TOOL_EXECUTE })
        assertTrue(!executed)
    }

    @Test
    fun permissionRevokedDuringToolExecutionFailsVerificationAndRequiresConfirmedRetry() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var permissionGranted = true
        val cameraPermission = "android.permission.CAMERA"
        val definition = ToolDefinition(
            name = "device.camera_snapshot",
            description = "拍摄并返回当前画面",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(
                requiredAndroidPermissions = setOf(cameraPermission),
            ),
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("验证前权限撤销后不应进入总结")
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    permissionGranted = false
                    return ToolExecutionResult(success = true, content = "相机已返回一帧")
                }
            },
            permissionChecker = ToolPermissionChecker { requiredPermissions ->
                if (permissionGranted) emptySet() else requiredPermissions
            },
        )

        runCatching {
            runtime.run("conversation-permission-execute", "message-permission-execute", "拍摄照片")
        }

        val snapshot = ledger.snapshot(ledger.lastRunId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("验证前"))
        assertTrue(snapshot.run.errorMessage.orEmpty().contains(cameraPermission))
        assertEquals(AgentStepStatus.COMPLETED, snapshot.steps.single { it.type == AgentStepTypes.TOOL_EXECUTE }.status)
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == AgentStepTypes.TOOL_VERIFY }.status)
        assertTrue(snapshot.events.any {
            it.type == "tool.result" && (it.metadata as? RunEventMetadata.ToolResult)?.success == true
        })
        assertTrue(snapshot.events.none { it.type == "tool.verify" })
        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            AgentTaskRetryPolicy.evaluate(AgentRunDetailRecord(snapshot = snapshot, approvals = emptyList())),
        )
    }

    @Test
    fun receiptFromAnotherToolCallFailsBeforePersistingSuccessfulResult() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.SAFE,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return error("多步入口应调用 proposeNextAction")
                }

                override suspend fun proposeNextAction(
                    goal: String,
                    tools: List<ToolDefinition>,
                    completedTools: List<AgentToolExecution>,
                ): AgentPlanDecision {
                    return if (completedTools.isEmpty()) {
                        AgentPlanDecision.CallTool(
                            ToolCall(
                                id = "tool-call-current",
                                name = definition.name,
                                arguments = emptyMap(),
                                risk = definition.risk,
                            ),
                        )
                    } else {
                        AgentPlanDecision.Complete
                    }
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = "{\"style\":\"compact\",\"tone\":\"neutral\"}"
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    return ToolExecutionResult(
                        success = true,
                        content = "已创建笔记",
                        executionReceipt = ToolExecutionReceipt(
                            toolCallId = "tool-call-other",
                            operationId = "note-1",
                            idempotencyKey = null,
                            status = ToolExecutionReceiptStatus.COMMITTED,
                        ),
                    )
                }
            },
        )

        runCatching {
            runtime.run("conversation-receipt-mismatch", "message-receipt-mismatch", "创建笔记")
        }

        val snapshot = ledger.snapshot(ledger.lastRunId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("执行回执不属于当前工具调用"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == AgentStepTypes.TOOL_EXECUTE }.status)
        assertTrue(snapshot.events.none { it.type == "tool.result" })
    }

    @Test
    fun foregroundOnlyToolFailsClosedForBackgroundExecution() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var executed = false
        val definition = ToolDefinition(
            name = "app.local_read",
            description = "仅允许前台读取",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = false),
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("后台能力校验失败时不应进入总结")
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    executed = true
                    return ToolExecutionResult(success = true, content = "不应执行")
                }
            },
        )

        var runId: String? = null
        try {
            runtime.run(
                conversationId = "conversation-1",
                userMessageId = "message-1",
                goal = "后台读取",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
            )
        } catch (error: IllegalStateException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("不允许后台执行"))
        assertTrue(!executed)
    }

    @Test
    fun approvalToolBecomesBlockedInBackgroundWithoutCallingGateOrExecutor() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var approvalRequested = false
        var executed = false
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建本地笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("后台审批阻断后不应进入总结")
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    executed = true
                    return ToolExecutionResult(success = true, content = "不应执行")
                }
            },
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    approvalRequested = true
                    return ApprovalDecision(true, "不应请求")
                }
            },
        )

        var runId: String? = null
        try {
            runtime.run(
                conversationId = "conversation-background-blocked",
                userMessageId = "message-background-blocked",
                goal = "后台创建笔记",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
            )
        } catch (error: AgentBackgroundApprovalRequiredException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.BLOCKED, snapshot.run.status)
        assertEquals(AgentStepStatus.BLOCKED, snapshot.steps.single { it.type == "tool.validate" }.status)
        assertTrue(snapshot.events.any { it.type == "run.blocked" })
        assertTrue(snapshot.events.none { it.type.startsWith("approval.") })
        assertTrue(!approvalRequested)
        assertTrue(!executed)
    }

    @Test
    fun backgroundSafeToolRunsOnlyWhenExplicitlySupported() = runTest {
        val ledger = InMemoryAgentRunLedger()
        var executed = false
        val definition = ToolDefinition(
            name = "app.current_time",
            description = "读取当前时间",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = """{"style":"concise","tone":"neutral"}"""
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    executed = true
                    return ToolExecutionResult(success = true, content = "当前时间：12:00")
                }
            },
        )

        val summary = runtime.run(
            conversationId = "conversation-background-safe",
            userMessageId = "message-background-safe",
            goal = "读取当前时间",
            executionOrigin = AgentExecutionOrigin.BACKGROUND,
        )

        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertTrue(executed)
    }

    @Test
    fun executorVerifiedPolicyRejectsReadableButUnverifiedResult() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建并回读笔记",
            risk = ToolRisk.SAFE,
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("回读验证失败时不应进入总结")
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    return ToolExecutionResult(success = true, content = "写入已返回成功", verified = null)
                }
            },
        )

        var runId: String? = null
        try {
            runtime.run("conversation-1", "message-1", "创建笔记")
        } catch (error: IllegalArgumentException) {
            runId = ledger.lastRunId
        }

        val snapshot = ledger.snapshot(runId!!)
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertTrue(snapshot.run.errorMessage.orEmpty().contains("未通过 Executor 回读验证"))
        assertEquals(AgentStepStatus.FAILED, snapshot.steps.single { it.type == AgentStepTypes.TOOL_VERIFY }.status)
    }

    @Test
    fun finalResponseUsesActualApprovalAndVerificationPolicies() = runTest {
        val definition = ToolDefinition(
            name = "test.confirmed_read",
            description = "需要确认并回读验证的测试工具",
            risk = ToolRisk.SAFE,
            approvalPolicy = ToolApprovalPolicy.REQUIRE_CONFIRMATION,
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
        )
        val runtime = MinimalAgentRuntime(
            ledger = InMemoryAgentRunLedger(),
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    return ToolCall(name = definition.name, arguments = emptyMap(), risk = definition.risk)
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = """{"style":"detailed","tone":"neutral"}"""
            },
            toolRegistry = object : ToolRegistry {
                override fun availableTools(): List<ToolDefinition> = listOf(definition)

                override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

                override suspend fun execute(call: ToolCall): ToolExecutionResult {
                    return ToolExecutionResult(success = true, content = "读取成功", verified = true)
                }
            },
        )

        val summary = runtime.run("conversation-1", "message-1", "确认读取")

        assertTrue(summary.responseText.contains("审批：已通过应用侧审批"))
        assertTrue(summary.responseText.contains("验证：Executor 回读验证通过"))
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

internal class InMemoryAgentRunLedger : AgentRunLedger {
    private val runs = linkedMapOf<String, AgentRunRecord>()
    private val steps = linkedMapOf<String, AgentStepRecord>()
    private val events = linkedMapOf<String, RunEventRecord>()
    var lastRunId: String? = null
        private set

    override suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
    ): AgentRunRecord {
        val now = System.currentTimeMillis()
        val run = AgentRunRecord(
            id = "run-${UUID.randomUUID()}",
            retryOfRunId = retryOfRunId,
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
            completedAt = if (status in setOf(AgentStepStatus.COMPLETED, AgentStepStatus.BLOCKED, AgentStepStatus.FAILED, AgentStepStatus.CANCELLED)) {
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
        AgentRunStatus.BLOCKED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.BUDGET_EXHAUSTED,
    )
}
