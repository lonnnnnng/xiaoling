package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentLlm
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentStepTypes
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.AgentToolExecutionContext
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.ApprovalDecision
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.FakeToolRegistry
import com.longdev.xiaoling.agent.MinimalAgentRuntime
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunResumePolicy
import com.longdev.xiaoling.agent.SystemAgentClock
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolExecutionReceipt
import com.longdev.xiaoling.agent.ToolExecutionReceiptStatus
import com.longdev.xiaoling.agent.ToolExecutionResult
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.XiaoLingToolRegistry
import com.longdev.xiaoling.data.ApprovalRequestEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentRunRepositoryInstrumentedTest {
    private lateinit var database: XiaoLingDatabase
    private lateinit var repository: RoomAgentRunRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAgentRunRepository(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pendingApprovalRequestsDoesNotExpireLegacyTimestampOrWriteDecisionEvent() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-legacy",
            userMessageId = "message-1",
            goal = "等待用户确认",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        database.agentRunDao().upsertApprovalRequest(
            ApprovalRequestEntity(
                id = "approval-legacy-expired-field",
                runId = run.id,
                conversationId = "conversation-legacy",
                toolCallId = "tool-call-1",
                toolName = "memory.remember",
                toolDescription = "写入长期记忆",
                risk = "REQUIRES_APPROVAL",
                argumentsJson = """{"note":"user likes compact ui"}""",
                status = ApprovalRequestStatus.PENDING.name,
                decisionReason = null,
                createdAt = 1L,
                expiresAt = 1L,
                decidedAt = null,
            ),
        )
        val eventsBeforeRead = database.agentRunDao().getEvents(run.id)

        val pending = repository.pendingApprovalRequests("conversation-legacy")
        val stored = database.agentRunDao().getApprovalRequest("approval-legacy-expired-field")
        val eventsAfterRead = database.agentRunDao().getEvents(run.id)

        assertEquals(listOf("approval-legacy-expired-field"), pending.map { it.id })
        assertEquals(ApprovalRequestStatus.PENDING.name, stored?.status)
        assertEquals(eventsBeforeRead.size, eventsAfterRead.size)
        assertFalse(eventsAfterRead.any { it.type == "approval.request_decided" })
    }

    @Test
    fun runEventMetadataRoundTripsThroughRepositorySnapshot() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-metadata",
            userMessageId = "message-metadata",
            goal = "保存结构化工具调用",
        )
        val metadata = RunEventMetadata.ToolCall(
            id = "tool-call-1",
            toolName = "fake.echo",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("goal" to "包含引号\"和换行\n的任务"),
        )

        repository.appendEvent(
            runId = run.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：fake.echo",
            metadata = metadata,
        )

        val event = repository.snapshot(run.id).events.single { it.type == "tool.call.proposed" }
        assertEquals(metadata, event.metadata)
        assertEquals("模型提出工具调用：fake.echo", event.message)
    }

    @Test
    fun toolLedgerDualWritesCallResultAndVerificationAcrossRepositoryRecreation() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-tool-ledger",
            userMessageId = "message-tool-ledger",
            goal = "保存独立工具账本",
        )
        val call = RunEventMetadata.ToolCall(
            id = "tool-call-ledger-1",
            toolName = "notes.create",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("title" to "账本", "content" to "独立结果"),
        )
        repository.appendEvent(run.id, "tool.call.proposed", "模型提出工具调用：notes.create", call)
        repository.appendEvent(run.id, "tool.call.validated", "工具调用已校验：notes.create", call)
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：notes.create",
            RunEventMetadata.ToolResult(
                toolName = "notes.create",
                content = "已创建笔记",
                durationMs = 23L,
                success = true,
                verified = true,
                memoryIdsUsed = listOf("memory-audit-1"),
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "note-ledger-1",
                    idempotencyKey = call.id,
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            ),
        )
        repository.appendEvent(
            run.id,
            "tool.verify",
            "工具验证通过：notes.create",
            RunEventMetadata.ToolVerification(
                toolName = "notes.create",
                status = com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED,
                toolCallId = call.id,
            ),
        )

        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext(),
            database,
        )
        val ledger = restartedRepository.toolLedger(run.id)

        assertEquals(listOf(call.id), ledger.calls.map { it.id })
        assertEquals(listOf("notes.create"), ledger.calls.map { it.toolName })
        assertEquals(call.arguments, ledger.calls.single().arguments)
        assertTrue(ledger.calls.single().validatedEventId != null)
        val result = ledger.results.single()
        assertEquals(call.id, result.toolCallId)
        assertEquals("已创建笔记", result.content)
        assertEquals(23L, result.durationMs)
        assertEquals(true, result.executorVerified)
        assertEquals(com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED, result.verificationStatus)
        assertEquals(listOf("memory-audit-1"), result.memoryIdsUsed)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, result.replaySafety)
        assertEquals("note-ledger-1", result.executionReceipt?.operationId)
        assertTrue(result.verifiedEventId != null)
    }

    @Test
    fun toolLedgerStoresFailedResultAsErrorWithoutInventingVerification() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-tool-error",
            userMessageId = "message-tool-error",
            goal = "记录失败工具结果",
        )
        val call = RunEventMetadata.ToolCall(
            id = "tool-call-error-1",
            toolName = "memory.search",
            risk = ToolRisk.SAFE,
            arguments = mapOf("query" to "不存在的记忆"),
        )
        repository.appendEvent(run.id, "tool.call.proposed", "模型提出工具调用：memory.search", call)
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行失败：memory.search",
            RunEventMetadata.ToolResult(
                toolName = call.toolName,
                content = "记忆索引不可用",
                durationMs = 9L,
                success = false,
                verified = false,
                toolCallId = call.id,
            ),
        )

        val result = repository.toolLedger(run.id).results.single()
        assertFalse(result.success)
        assertEquals("记忆索引不可用", result.errorMessage)
        assertEquals(false, result.executorVerified)
        assertNull(result.verificationStatus)
        assertNull(result.verifiedEventId)
        assertNull(result.verifiedAt)
    }

    @Test
    fun toolLedgerRollsBackRunEventWhenToolCallIdentityDrifts() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-tool-drift",
            userMessageId = "message-tool-drift",
            goal = "拒绝工具调用漂移",
        )
        val proposed = RunEventMetadata.ToolCall(
            id = "tool-call-drift-1",
            toolName = "notes.create",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("title" to "原始标题", "content" to "原始正文"),
        )
        repository.appendEvent(run.id, "tool.call.proposed", "模型提出工具调用：notes.create", proposed)

        val failure = runCatching {
            repository.appendEvent(
                run.id,
                "tool.call.validated",
                "工具调用已校验：notes.create",
                proposed.copy(arguments = proposed.arguments + ("title" to "漂移标题")),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(1, repository.snapshot(run.id).events.count { it.type.startsWith("tool.call.") })
        val stored = repository.toolLedger(run.id).calls.single()
        assertEquals("原始标题", stored.arguments["title"])
        assertNull(stored.validatedEventId)
    }

    @Test
    fun recoveryFailureGuidanceRoundTripsThroughRepositorySnapshot() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-recovery-guidance",
            userMessageId = "message-recovery-guidance",
            goal = "展示恢复失败建议",
        )
        val metadata = RunEventMetadata.RecoveryFailure(
            toolName = "memory.remember",
            code = "MEMORY_DISABLED",
            reason = "原长期记忆已禁用",
            suggestedAction = "请先启用该记忆，再创建新 Run 重试。",
        )

        repository.appendEvent(
            runId = run.id,
            type = com.longdev.xiaoling.agent.AgentEventTypes.RECOVERY_FAILED,
            message = "恢复验证失败",
            metadata = metadata,
        )

        val restored = repository.snapshot(run.id).events
            .single { it.type == com.longdev.xiaoling.agent.AgentEventTypes.RECOVERY_FAILED }
        assertEquals(metadata, restored.metadata)
        assertEquals("恢复验证失败", restored.message)
    }

    @Test
    fun toolResultMemoryIdsRoundTripThroughRepositorySnapshot() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-memory-audit",
            userMessageId = "message-memory-audit",
            goal = "记录实际使用的记忆",
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.result",
            message = "工具执行成功：memory.search",
            metadata = RunEventMetadata.ToolResult(
                toolName = "memory.search",
                content = "长期记忆：...",
                durationMs = 18L,
                success = true,
                verified = null,
                memoryIdsUsed = listOf("memory-1", "memory-2"),
            ),
        )

        val metadata = repository.snapshot(run.id).events
            .single { it.type == "tool.result" }
            .metadata as RunEventMetadata.ToolResult
        assertEquals(listOf("memory-1", "memory-2"), metadata.memoryIdsUsed)
    }

    @Test
    fun toolExecutionReceiptRoundTripsThroughRepositorySnapshot() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-execution-receipt",
            userMessageId = "message-execution-receipt",
            goal = "保存工具执行回执",
        )
        val metadata = RunEventMetadata.ToolResult(
            toolName = "notes.create",
            content = "已创建笔记",
            durationMs = 18L,
            success = true,
            verified = true,
            toolCallId = "tool-call-receipt-1",
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = "tool-call-receipt-1",
                operationId = "note-1",
                idempotencyKey = null,
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )

        repository.appendEvent(
            runId = run.id,
            type = "tool.result",
            message = "工具执行成功：notes.create",
            metadata = metadata,
        )

        val restored = repository.snapshot(run.id).events
            .single { it.type == "tool.result" }
            .metadata as RunEventMetadata.ToolResult
        assertEquals(metadata, restored)
    }

    @Test
    fun approvalEventsUseReadableMessagesAndTypedMetadata() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-approval-metadata",
            userMessageId = "message-approval-metadata",
            goal = "保存审批审计字段",
        )
        val call = ToolCall(
            id = "tool-call-approval",
            name = "memory.remember",
            arguments = mapOf("content" to "用户喜欢紧凑界面"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val request = repository.createApprovalRequest(
            conversationId = "conversation-approval-metadata",
            runId = run.id,
            toolCall = call,
            definition = ToolDefinition(
                name = call.name,
                description = "写入长期记忆",
                risk = call.risk,
            ),
        )

        repository.decideApprovalRequest(request.id, ApprovalRequestStatus.APPROVED, "用户确认保存")

        val events = repository.snapshot(run.id).events
        val requested = events.single { it.type == "approval.requested" }
        val decided = events.single { it.type == "approval.request_decided" }
        val requestedMetadata = requested.metadata as RunEventMetadata.ApprovalRequest
        val decidedMetadata = decided.metadata as RunEventMetadata.ApprovalRequest
        assertEquals(request.id, requestedMetadata.id)
        assertEquals("memory.remember", requestedMetadata.toolName)
        assertEquals("用户喜欢紧凑界面", requestedMetadata.arguments["content"])
        assertEquals(ApprovalRequestStatus.PENDING, requestedMetadata.status)
        assertFalse(requested.message.trimStart().startsWith("{"))
        assertEquals(ApprovalRequestStatus.APPROVED, decidedMetadata.status)
        assertEquals("用户确认保存", decidedMetadata.reason)
        assertFalse(decided.message.trimStart().startsWith("{"))
    }

    @Test
    fun interruptedRunRecoveryUsesTypedStatusMetadata() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-recovery-metadata",
            userMessageId = "message-recovery-metadata",
            goal = "恢复中断任务",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.THINKING)

        assertEquals(1, repository.closeInterruptedRuns())

        val recovered = repository.snapshot(run.id).events.single { it.type == "run.recovered" }
        val metadata = recovered.metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.THINKING, metadata.fromStatus)
        assertEquals(AgentRunStatus.CANCELLED, metadata.toStatus)
        assertEquals("应用重启后终止上次未完成 Agent 任务", metadata.reason)
        assertFalse(recovered.message.trimStart().startsWith("{"))
    }

    @Test
    fun processRestartCancelsExecutingAndVerifyingStepsBeforeConfirmedRetry() = runBlocking {
        val interrupted = listOf(
            AgentRunStatus.EXECUTING to AgentStepTypes.TOOL_EXECUTE,
            AgentRunStatus.VERIFYING to AgentStepTypes.TOOL_VERIFY,
        ).mapIndexed { index, (status, stepType) ->
            val run = repository.createRun(
                conversationId = "conversation-process-$index",
                userMessageId = "message-process-$index",
                goal = "进程中断 ${status.name}",
            )
            repository.updateRunStatus(run.id, status)
            val step = repository.appendStep(
                runId = run.id,
                type = stepType,
                title = status.name,
                detail = "进程终止前仍在运行",
                status = AgentStepStatus.RUNNING,
            )
            Triple(run, step, status)
        }
        // long: 新 Repository 实例代表进程重建后的组件图；所有判断只读取 Room，不依赖旧 Runtime 的协程或内存状态。
        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )

        assertEquals(2, restartedRepository.closeInterruptedRuns())

        interrupted.forEach { (sourceRun, sourceStep, interruptedStatus) ->
            val closed = restartedRepository.runDetail(sourceRun.id)!!
            assertEquals(AgentRunStatus.CANCELLED, closed.snapshot.run.status)
            assertEquals(
                AgentStepStatus.CANCELLED,
                closed.snapshot.steps.single { it.id == sourceStep.id }.status,
            )
            val recovery = closed.snapshot.events.single { it.type == "run.recovered" }
                .metadata as RunEventMetadata.Recovery
            assertEquals(interruptedStatus, recovery.fromStatus)
            assertEquals(AgentRunStatus.CANCELLED, recovery.toStatus)
            assertEquals(
                AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
                AgentTaskRetryPolicy.evaluate(closed),
            )

            val sourceBeforeRetry = closed
            val retry = restartedRepository.createRun(
                conversationId = sourceRun.conversationId,
                userMessageId = "retry-${sourceRun.userMessageId}",
                goal = sourceRun.goal,
                retryOfRunId = sourceRun.id,
            )
            assertEquals(sourceRun.id, retry.retryOfRunId)
            assertEquals(sourceBeforeRetry, restartedRepository.runDetail(sourceRun.id))
        }
    }

    @Test
    fun processRestartRecoversCommittedNoteAtVerificationBoundaryWithoutReplayingWrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val noteStore = RoomAgentNoteStore(context, database)
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context, database),
            noteStore = noteStore,
            memoryStore = RoomAgentMemoryStore(context, database),
        )
        val definition = checkNotNull(registry.definition("notes.create"))
        val run = repository.createRun(
            conversationId = "conversation-note-verification-recovery",
            userMessageId = "message-note-verification-recovery",
            goal = "恢复已提交笔记的验证",
        )
        val call = ToolCall(
            id = "tool-call-note-verification-recovery",
            name = definition.name,
            arguments = mapOf("title" to "进程恢复笔记", "content" to "写入后只读验证"),
            risk = definition.risk,
        )
        val result = registry.execute(call)
        val receipt = checkNotNull(result.executionReceipt)
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.validated",
            message = "工具调用已校验：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = "工具结果落库后进程终止",
            status = AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.result",
            message = "工具执行成功：${call.name}",
            metadata = RunEventMetadata.ToolResult(
                toolName = call.name,
                content = result.content,
                durationMs = 10L,
                success = result.success,
                verified = result.verified,
                toolCallId = call.id,
                replaySafety = definition.replaySafety,
                executionReceipt = receipt,
            ),
        )
        val restartedRepository = RoomAgentRunRepository(context, database)

        val recovered = restartedRepository
            .recoverCommittedToolRuns(
                registry::definition,
                registry::supportsCommittedEffectVerification,
            )
            .single { it.snapshot.run.id == run.id }
        val closedCount = restartedRepository.closeInterruptedRuns(
            registry::definition,
            registry::supportsCommittedEffectVerification,
        )
        val summary = MinimalAgentRuntime(
            ledger = restartedRepository,
            toolRegistry = registry,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    error("验证阶段恢复不应重新规划")
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("验证阶段恢复使用本地总结")
            },
        ).resumeCommittedToolRun(recovered)

        val finalDetail = checkNotNull(restartedRepository.runDetail(run.id))
        assertEquals(0, closedCount)
        assertEquals(AgentRunStatus.COMPLETED, finalDetail.snapshot.run.status)
        assertEquals(1, finalDetail.snapshot.events.count { it.type == "tool.result" })
        assertEquals(1, finalDetail.snapshot.events.count { it.type == "tool.verify" })
        assertEquals(listOf(receipt.operationId), noteStore.list(10).map { it.id })
        assertTrue(summary.responseText.contains("进程恢复笔记"))
    }

    @Test
    fun processRestartRecoversCommittedMemoryAtVerificationBoundaryWithoutRememberingAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "xiaoling-memory-run-recovery-test.db"
        context.deleteDatabase(databaseName)
        var firstDatabase: XiaoLingDatabase? = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        var restartedDatabase: XiaoLingDatabase? = null
        try {
            val opened = checkNotNull(firstDatabase)
            val firstRepository = RoomAgentRunRepository(context, opened)
            val memoryStore = RoomAgentMemoryStore(context, opened)
            val firstRegistry = XiaoLingToolRegistry(
                clock = SystemAgentClock(),
                conversationStore = RoomAgentConversationStore(context, opened),
                noteStore = RoomAgentNoteStore(context, opened),
                memoryStore = memoryStore,
            )
            val definition = checkNotNull(firstRegistry.definition("memory.remember"))
            val run = firstRepository.createRun(
                conversationId = "conversation-memory-verification-recovery",
                userMessageId = "message-memory-verification-recovery",
                goal = "恢复已提交长期记忆的验证",
            )
            firstRegistry.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = run.conversationId,
                    userMessageId = run.userMessageId,
                    runId = run.id,
                    goal = run.goal,
                ),
            )
            val call = ToolCall(
                id = "tool-call-memory-verification-recovery",
                name = definition.name,
                arguments = mapOf(
                    "note" to "用户喜欢紧凑界面",
                    "type" to "Preference",
                    "tags" to "ui,preference",
                ),
                risk = definition.risk,
            )
            val result = firstRegistry.execute(call)
            val receipt = checkNotNull(result.executionReceipt)
            firstRepository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
            firstRepository.appendEvent(
                runId = run.id,
                type = "tool.call.validated",
                message = "工具调用已校验：${call.name}",
                metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
            )
            firstRepository.appendStep(
                runId = run.id,
                type = AgentStepTypes.TOOL_EXECUTE,
                title = "执行工具",
                detail = "长期记忆结果落库后进程终止",
                status = AgentStepStatus.RUNNING,
            )
            firstRepository.appendEvent(
                runId = run.id,
                type = "tool.result",
                message = "工具执行成功：${call.name}",
                metadata = RunEventMetadata.ToolResult(
                    toolName = call.name,
                    content = result.content,
                    durationMs = 10L,
                    success = result.success,
                    verified = result.verified,
                    toolCallId = call.id,
                    replaySafety = definition.replaySafety,
                    executionReceipt = receipt,
                ),
            )

            // long: 关闭并重开磁盘 Room，模拟操作系统终止进程后生产启动协调器只能依赖持久化 Run、ToolCall、回执和 memory operation 的真实边界。
            opened.close()
            firstDatabase = null
            val reopened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
                .also { restartedDatabase = it }
            val restartedRepository = RoomAgentRunRepository(context, reopened)
            val restartedMemoryStore = RoomAgentMemoryStore(context, reopened)
            val restartedRegistry = XiaoLingToolRegistry(
                clock = SystemAgentClock(),
                conversationStore = RoomAgentConversationStore(context, reopened),
                noteStore = RoomAgentNoteStore(context, reopened),
                memoryStore = restartedMemoryStore,
            )
            val assessment = AgentRunResumePolicy.assess(
                checkNotNull(restartedRepository.runDetail(run.id)),
                restartedRegistry::definition,
                restartedRegistry::supportsCommittedEffectVerification,
            )
            check(assessment.kind == AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION) { assessment.reason }
            val recovered = restartedRepository
                .recoverCommittedToolRuns(
                    restartedRegistry::definition,
                    restartedRegistry::supportsCommittedEffectVerification,
                )
                .single { it.snapshot.run.id == run.id }
            val summary = MinimalAgentRuntime(
                ledger = restartedRepository,
                toolRegistry = restartedRegistry,
                llm = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                        error("验证阶段恢复不应重新规划")
                    }

                    override suspend fun summarize(
                        goal: String,
                        toolCall: ToolCall,
                        toolResult: ToolExecutionResult,
                    ): String = error("验证阶段恢复使用本地总结")
                },
            ).resumeCommittedToolRun(recovered)

            val finalDetail = checkNotNull(restartedRepository.runDetail(run.id))
            assertEquals(AgentRunStatus.COMPLETED, finalDetail.snapshot.run.status)
            assertEquals(1, finalDetail.snapshot.events.count { it.type == "tool.result" })
            assertEquals(1, finalDetail.snapshot.events.count { it.type == "tool.verify" })
            assertEquals(
                listOf(receipt.operationId),
                restartedMemoryStore.list("", AgentMemoryFilter.ALL).map { it.id },
            )
            assertTrue(summary.responseText.contains("用户喜欢紧凑界面"))
        } finally {
            firstDatabase?.close()
            restartedDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun pendingApprovalRunSurvivesProcessRecoveryBoundary() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-pending-recovery",
            userMessageId = "message-pending-recovery",
            goal = "恢复待审批任务",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = ToolCall(
                id = "tool-call-pending-recovery",
                name = "memory.remember",
                arguments = mapOf("content" to "用户喜欢紧凑界面"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
            definition = ToolDefinition(
                name = "memory.remember",
                description = "写入长期记忆",
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        val resumable = repository.recoverPendingApprovalRuns()
        val closedCount = repository.closeInterruptedRuns()
        val snapshot = repository.snapshot(run.id)

        assertEquals(listOf(run.id), resumable.map { it.snapshot.run.id })
        assertEquals(0, closedCount)
        assertEquals(AgentRunStatus.WAITING_APPROVAL, snapshot.run.status)
        assertEquals(ApprovalRequestStatus.PENDING, repository.pendingApprovalRequests(run.conversationId).single { it.id == request.id }.status)
        val recovered = snapshot.events.last { it.type == "run.recovered" }
        val metadata = recovered.metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.WAITING_APPROVAL, metadata.fromStatus)
        assertEquals(AgentRunStatus.WAITING_APPROVAL, metadata.toStatus)
    }

    @Test
    fun recoveredPendingApprovalCompletesOriginalRoomRun() = runBlocking {
        val registry = FakeToolRegistry()
        val definition = registry.definition("fake.echo")!!
        val run = repository.createRun(
            conversationId = "conversation-room-resume",
            userMessageId = "message-room-resume",
            goal = "从 Room 恢复执行",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        val approvalStep = repository.appendStep(
            runId = run.id,
            type = "approval",
            title = "应用侧审批",
            detail = "等待应用侧审批 fake.echo",
            status = AgentStepStatus.RUNNING,
        )
        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = ToolCall(
                id = "tool-call-room-resume",
                name = definition.name,
                arguments = mapOf("goal" to run.goal),
                risk = definition.risk,
            ),
            definition = definition,
        )
        // long: 新建 Repository 实例模拟进程重建后的组件重建，但继续复用同一 Room 数据，验证恢复不依赖旧内存对象。
        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )
        val recovered = restartedRepository.recoverPendingApprovalRuns().single { it.snapshot.run.id == run.id }
        restartedRepository.decideApprovalRequest(request.id, ApprovalRequestStatus.APPROVED, "用户确认继续")

        val summary = MinimalAgentRuntime(
            ledger = restartedRepository,
            toolRegistry = registry,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    error("恢复原 Run 不应重新规划工具")
                }

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = """{"style":"compact","tone":"neutral"}"""
            },
        ).resumeApprovedRun(
            detail = recovered,
            approval = request,
            approvalDecision = ApprovalDecision(approved = true, reason = "用户确认继续"),
        )

        val detail = restartedRepository.runDetail(run.id)!!
        assertEquals(run.id, summary.runId)
        assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
        assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.id == request.id }.status)
        assertEquals(AgentStepStatus.COMPLETED, detail.snapshot.steps.single { it.id == approvalStep.id }.status)
        // long: 恢复入口不重新规划已批准工具；工具完成后仍需一次规划确认是否继续，这是多步骤 Agent 的正常后续决策。
        assertEquals(1, detail.snapshot.steps.count { it.type == "llm.plan" })
        assertTrue(detail.snapshot.events.any { it.type == "tool.result" })
        assertTrue(detail.snapshot.events.any { it.type == "tool.verify" })
    }

    @Test
    fun retryCreatesLinkedRunWithoutChangingSourceRun() = runBlocking {
        val sourceRun = repository.createRun(
            conversationId = "conversation-retry",
            userMessageId = "message-source",
            goal = "完成可重试任务",
        )
        repository.appendEvent(
            runId = sourceRun.id,
            type = "tool.result",
            message = "工具执行失败：fake.echo",
            metadata = RunEventMetadata.ToolResult(
                toolName = "fake.echo",
                content = "上游服务暂时不可用",
                durationMs = 321L,
                success = false,
                verified = false,
            ),
        )
        repository.updateRunStatus(
            runId = sourceRun.id,
            status = AgentRunStatus.FAILED,
            result = "保留旧结果",
            errorMessage = "网络超时",
        )
        val sourceBeforeRetry = repository.snapshot(sourceRun.id)

        val retryRun = repository.createRun(
            conversationId = sourceRun.conversationId,
            userMessageId = "message-retry",
            goal = sourceRun.goal,
            retryOfRunId = sourceRun.id,
        )

        assertEquals(sourceRun.id, repository.snapshot(retryRun.id).run.retryOfRunId)
        assertEquals(sourceBeforeRetry, repository.snapshot(sourceRun.id))
    }
}
