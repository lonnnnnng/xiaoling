package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentEventTypes
import com.longdev.xiaoling.agent.AgentLlm
import com.longdev.xiaoling.agent.AgentNotCommittedReplayQualificationAssessment
import com.longdev.xiaoling.agent.AgentNotCommittedReplayQualificationPolicy
import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfileSnapshot
import com.longdev.xiaoling.agent.AgentProcessTerminationSimulation
import com.longdev.xiaoling.agent.AgentRuntimeFaultInjector
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentStepTypes
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.AgentToolExecutionContext
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.ApprovalDecision
import com.longdev.xiaoling.agent.ApprovalGate
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.FakeToolRegistry
import com.longdev.xiaoling.agent.MinimalAgentRuntime
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunResumePolicy
import com.longdev.xiaoling.agent.AgentRunRecoveryEvidenceSource
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.SystemAgentClock
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolDefinitionRecoveryContract
import com.longdev.xiaoling.agent.ToolExecutionReceipt
import com.longdev.xiaoling.agent.ToolExecutionReceiptStatus
import com.longdev.xiaoling.agent.ToolExecutionResult
import com.longdev.xiaoling.agent.ToolNotCommittedReplayPolicy
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.ToolRegistry
import com.longdev.xiaoling.agent.ToolVerificationStatus
import com.longdev.xiaoling.agent.XiaoLingToolRegistry
import com.longdev.xiaoling.agent.agentProfileSnapshotOrNull
import com.longdev.xiaoling.data.ApprovalRequestEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun terminalRunKeepsRecoveryOutcomeWhenOldExecutionCompletesLate() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-terminal-race",
            userMessageId = "message-terminal-race",
            goal = "验证启动恢复与旧执行返回的竞态",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.THINKING)
        repository.updateRunStatus(
            runId = run.id,
            status = AgentRunStatus.CANCELLED,
            errorMessage = "应用重启后终止上次未完成 Agent 任务",
        )

        // long: 启动恢复已经冻结旧执行栈后，迟到的模型响应只属于历史协程，不能覆盖用户看到的恢复处置终态。
        repository.updateRunStatus(
            runId = run.id,
            status = AgentRunStatus.COMPLETED,
            result = "迟到的旧执行结果",
        )

        val snapshot = repository.snapshot(run.id)
        assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
        assertNull(snapshot.run.result)
        assertEquals("应用重启后终止上次未完成 Agent 任务", snapshot.run.errorMessage)
        assertEquals(
            listOf("THINKING", "CANCELLED"),
            snapshot.events.filter { it.type == "run.status" }.map { it.message },
        )
    }

    @Test
    fun failedCalendarUpdateRunStaysTerminalAcrossRestartAndCannotReplay() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-calendar-update-failed-restart",
            userMessageId = "message-calendar-update-failed-restart",
            goal = "外部漂移后的日程修改失败不得重放",
        )
        val call = ToolCall(
            id = "tool-call-calendar-update-failed-restart",
            name = "calendar.update_event",
            arguments = mapOf(
                "event_id" to "calendar-188",
                "expected_fingerprint" to "calendar-event-v1-stale",
                "scope" to "event",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val executionStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_EXECUTE,
            "执行工具",
            "Provider 外部漂移后失败",
            AgentStepStatus.FAILED,
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行失败：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "事件版本已变化，未执行修改",
                durationMs = 12L,
                success = false,
                verified = false,
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                executionReceipt = null,
            ),
        )
        repository.updateRunStatus(
            run.id,
            AgentRunStatus.FAILED,
            errorMessage = "Provider 条件 UPDATE 因外部漂移拒绝",
        )

        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )
        val detail = checkNotNull(restartedRepository.runDetail(run.id))
        val assessment = AgentRunResumePolicy.assess(
            detail,
            definitionLookup = { name ->
                ToolDefinition(
                    name = name,
                    description = "受控系统日程修改",
                    risk = ToolRisk.REQUIRES_APPROVAL,
                    replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                    notCommittedReplayPolicy = ToolNotCommittedReplayPolicy.DENY,
                ).takeIf { it.name == call.name }
            },
        )
        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
            checkNotNull(assessment.restartDisposition).code,
        )
        val eventCount = detail.snapshot.events.size
        assertEquals(0, restartedRepository.closeInterruptedRuns(runIds = setOf(run.id)))
        val after = checkNotNull(restartedRepository.runDetail(run.id))
        assertEquals(AgentRunStatus.FAILED, after.snapshot.run.status)
        assertEquals(executionStep.id, after.snapshot.steps.single().id)
        assertEquals(eventCount, after.snapshot.events.size)
        assertEquals(1, after.toolLedger.results.count { it.toolCallId == call.id })
        assertEquals(0, after.snapshot.events.count { it.type == "run.recovered" })
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
    fun malformedKnowledgeReferenceJsonDoesNotBlockRunDetailLoad() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-malformed-knowledge",
            userMessageId = "message-malformed-knowledge",
            goal = "验证损坏知识引用容错",
        )
        val call = ToolCall(
            id = "tool-call-malformed-knowledge",
            name = "knowledge.search",
            arguments = mapOf("query" to "引用"),
            risk = ToolRisk.SAFE,
        )
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出知识检索",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "知识检索已校验",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "知识检索完成",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "旧结果",
                durationMs = 2L,
                success = true,
                verified = null,
                toolCallId = call.id,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE agent_tool_results SET knowledgeReferencesJson = ? WHERE toolCallId = ?",
            arrayOf("not-json", call.id),
        )

        val detail = checkNotNull(repository.runDetail(run.id))

        assertTrue(detail.toolLedger.results.single().knowledgeReferences.isEmpty())
        assertEquals("旧结果", detail.toolLedger.results.single().content)
    }

    @Test
    fun toolLedgerDualWritesCallResultAndVerificationAcrossRepositoryRecreation() = runBlocking {
        val knowledgeReference = KnowledgeReference(
            retrievalId = "knowledge-retrieval-ledger",
            documentId = "document-ledger",
            documentName = "账本知识.md",
            documentRevision = 2,
            chunkId = "chunk-ledger-r2-1",
            chunkSequence = 1,
            startOffset = 80,
            endOffset = 160,
        )
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
                knowledgeReferences = listOf(knowledgeReference),
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
        assertEquals(listOf(knowledgeReference), result.knowledgeReferences)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, result.replaySafety)
        assertEquals("note-ledger-1", result.executionReceipt?.operationId)
        assertTrue(result.verifiedEventId != null)
    }

    @Test
    fun recentRunDetailsLoadsEachRunToolLedger() = runBlocking {
        val firstRun = repository.createRun(
            conversationId = "conversation-ledger-list-1",
            userMessageId = "message-ledger-list-1",
            goal = "读取第一条账本",
        )
        val secondRun = repository.createRun(
            conversationId = "conversation-ledger-list-2",
            userMessageId = "message-ledger-list-2",
            goal = "读取第二条账本",
        )
        val firstCall = RunEventMetadata.ToolCall(
            id = "tool-call-list-1",
            toolName = "app.current_time",
            risk = ToolRisk.SAFE,
            arguments = emptyMap(),
        )
        val secondCall = RunEventMetadata.ToolCall(
            id = "tool-call-list-2",
            toolName = "memory.search",
            risk = ToolRisk.SAFE,
            arguments = mapOf("query" to "账本"),
        )
        repository.appendEvent(firstRun.id, "tool.call.proposed", "第一条调用", firstCall)
        repository.appendEvent(secondRun.id, "tool.call.proposed", "第二条调用", secondCall)
        repository.appendEvent(
            firstRun.id,
            "tool.result",
            "第一条结果",
            RunEventMetadata.ToolResult(
                toolName = firstCall.toolName,
                content = "当前时间",
                durationMs = 3L,
                success = true,
                verified = true,
                toolCallId = firstCall.id,
            ),
        )

        val detailsByRunId = repository.recentRunDetails(limit = 10).associateBy { it.snapshot.run.id }

        assertEquals(listOf(firstCall.id), detailsByRunId.getValue(firstRun.id).toolLedger.calls.map { it.id })
        assertEquals(1, detailsByRunId.getValue(firstRun.id).toolLedger.results.size)
        assertEquals(listOf(secondCall.id), detailsByRunId.getValue(secondRun.id).toolLedger.calls.map { it.id })
        assertEquals(0, detailsByRunId.getValue(secondRun.id).toolLedger.results.size)
    }

    @Test
    fun approvalRequestsLoadsOnlyRequestedAgentRuns() = runBlocking {
        val firstRun = repository.createRun(
            conversationId = "conversation-approval-list-1",
            userMessageId = "message-approval-list-1",
            goal = "读取第一条设备动作审批",
        )
        val secondRun = repository.createRun(
            conversationId = "conversation-approval-list-2",
            userMessageId = "message-approval-list-2",
            goal = "读取第二条设备动作审批",
        )
        val definition = ToolDefinition(
            name = "device.tap_ref",
            description = "点击节点引用",
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val firstApproval = repository.createApprovalRequest(
            conversationId = firstRun.conversationId,
            runId = firstRun.id,
            toolCall = ToolCall(
                id = "tool-call-approval-list-1",
                name = definition.name,
                arguments = mapOf("ref" to "ref-first"),
                risk = definition.risk,
            ),
            definition = definition,
        )
        repository.createApprovalRequest(
            conversationId = secondRun.conversationId,
            runId = secondRun.id,
            toolCall = ToolCall(
                id = "tool-call-approval-list-2",
                name = definition.name,
                arguments = mapOf("ref" to "ref-second"),
                risk = definition.risk,
            ),
            definition = definition,
        )
        repository.decideApprovalRequest(
            firstApproval.id,
            ApprovalRequestStatus.DENIED,
            "用户已在设备动作审批浮层拒绝",
        )

        val approvalsByRunId = repository.approvalRequests(listOf(firstRun.id, firstRun.id))

        assertEquals(setOf(firstRun.id), approvalsByRunId.keys)
        assertEquals(ApprovalRequestStatus.DENIED, approvalsByRunId.getValue(firstRun.id).single().status)
        assertEquals("ref-first", approvalsByRunId.getValue(firstRun.id).single().arguments["ref"])
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
    fun toolLedgerRollsBackResultWithoutMatchingToolCallEvidence() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-orphan-result",
            userMessageId = "message-orphan-result",
            goal = "拒绝孤立工具结果",
        )

        val failure = runCatching {
            repository.appendEvent(
                run.id,
                "tool.result",
                "工具执行成功：notes.create",
                RunEventMetadata.ToolResult(
                    toolName = "notes.create",
                    content = "孤立结果",
                    durationMs = 5L,
                    success = true,
                    verified = true,
                    toolCallId = "tool-call-missing",
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(repository.snapshot(run.id).events.any { it.type == "tool.result" })
        assertEquals(0, repository.toolLedger(run.id).results.size)
    }

    @Test
    fun toolLedgerRollsBackNewResultWithoutToolCallIdentity() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-null-result-id",
            userMessageId = "message-null-result-id",
            goal = "拒绝无身份工具结果",
        )

        val failure = runCatching {
            repository.appendEvent(
                run.id,
                "tool.result",
                "工具执行失败：memory.search",
                RunEventMetadata.ToolResult(
                    toolName = "memory.search",
                    content = "缺少调用身份",
                    durationMs = 4L,
                    success = false,
                    verified = false,
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(repository.snapshot(run.id).events.any { it.type == "tool.result" })
    }

    @Test
    fun toolLedgerRollsBackNewVerificationWithoutToolCallIdentity() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-null-verify-id",
            userMessageId = "message-null-verify-id",
            goal = "拒绝无身份工具验证",
        )

        val failure = runCatching {
            repository.appendEvent(
                run.id,
                "tool.verify",
                "工具验证通过：memory.search",
                RunEventMetadata.ToolVerification(
                    toolName = "memory.search",
                    status = com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED,
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(repository.snapshot(run.id).events.any { it.type == "tool.verify" })
    }

    @Test
    fun toolLedgerRollsBackVerificationThatReferencesAnotherRun() = runBlocking {
        val sourceRun = repository.createRun(
            conversationId = "conversation-source-run",
            userMessageId = "message-source-run",
            goal = "保存来源工具结果",
        )
        val call = RunEventMetadata.ToolCall(
            id = "tool-call-source-run",
            toolName = "notes.create",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("title" to "来源", "content" to "来源结果"),
        )
        repository.appendEvent(sourceRun.id, "tool.call.proposed", "模型提出工具调用：notes.create", call)
        repository.appendEvent(
            sourceRun.id,
            "tool.result",
            "工具执行成功：notes.create",
            RunEventMetadata.ToolResult(
                toolName = call.toolName,
                content = "已创建来源笔记",
                durationMs = 6L,
                success = true,
                verified = true,
                toolCallId = call.id,
            ),
        )
        val otherRun = repository.createRun(
            conversationId = "conversation-other-run",
            userMessageId = "message-other-run",
            goal = "不能验证其他 Run",
        )

        val failure = runCatching {
            repository.appendEvent(
                otherRun.id,
                "tool.verify",
                "工具验证通过：notes.create",
                RunEventMetadata.ToolVerification(
                    toolName = call.toolName,
                    status = com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED,
                    toolCallId = call.id,
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(repository.snapshot(otherRun.id).events.any { it.type == "tool.verify" })
        assertNull(repository.toolLedger(sourceRun.id).results.single().verificationStatus)
    }

    @Test
    fun toolLedgerRollsBackVerificationWithMismatchedToolName() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-wrong-tool",
            userMessageId = "message-wrong-tool",
            goal = "拒绝错误工具验证",
        )
        val call = RunEventMetadata.ToolCall(
            id = "tool-call-wrong-tool",
            toolName = "notes.create",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("title" to "工具", "content" to "名称一致"),
        )
        repository.appendEvent(run.id, "tool.call.proposed", "模型提出工具调用：notes.create", call)
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：notes.create",
            RunEventMetadata.ToolResult(
                toolName = call.toolName,
                content = "已创建笔记",
                durationMs = 7L,
                success = true,
                verified = true,
                toolCallId = call.id,
            ),
        )

        val failure = runCatching {
            repository.appendEvent(
                run.id,
                "tool.verify",
                "工具验证通过：memory.remember",
                RunEventMetadata.ToolVerification(
                    toolName = "memory.remember",
                    status = com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED,
                    toolCallId = call.id,
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(repository.snapshot(run.id).events.any { it.type == "tool.verify" })
        assertNull(repository.toolLedger(run.id).results.single().verificationStatus)
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
        val toolCallId = "tool-call-memory-audit"
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：memory.search",
            metadata = RunEventMetadata.ToolCall(
                id = toolCallId,
                toolName = "memory.search",
                risk = ToolRisk.SAFE,
                arguments = mapOf("query" to "长期记忆"),
            ),
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
                toolCallId = toolCallId,
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
            type = "tool.call.proposed",
            message = "模型提出工具调用：notes.create",
            metadata = RunEventMetadata.ToolCall(
                id = "tool-call-receipt-1",
                toolName = "notes.create",
                risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("title" to "回执", "content" to "验证往返"),
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
    fun typeTextApprovalPersistsFingerprintWithoutInputText() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-type-text-privacy",
            userMessageId = "message-type-text-privacy",
            goal = "输入普通文本",
        )
        val inputText = "Direct safe text"
        val call = ToolCall(
            id = "tool-call-type-text-privacy",
            name = "device.type_text",
            arguments = mapOf(
                "snapshot_id" to "snapshot-current",
                "ref" to "r1",
                "text" to inputText,
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val expectedArguments = mapOf(
            "snapshot_id" to "snapshot-current",
            "ref" to "r1",
            "text_sha256" to "a9479104e48af2c58b1c68bbadbb38d4143c934508229270f7e84b282f59ff89",
            "text_length" to inputText.length.toString(),
        )

        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = call,
            definition = ToolDefinition(
                name = call.name,
                description = "向普通文本框输入非敏感文本",
                risk = call.risk,
            ),
        )
        repository.decideApprovalRequest(
            requestId = request.id,
            status = ApprovalRequestStatus.APPROVED,
            reason = "用户确认输入",
        )

        val detail = checkNotNull(repository.runDetail(run.id))
        assertEquals(expectedArguments, request.arguments)
        assertEquals(expectedArguments, detail.approvals.single().arguments)
        detail.snapshot.events
            .filter { it.type == "approval.requested" || it.type == "approval.request_decided" }
            .map { checkNotNull(it.metadata as? RunEventMetadata.ApprovalRequest) }
            .forEach { metadata ->
                assertEquals(expectedArguments, metadata.arguments)
                assertFalse(metadata.toString().contains(inputText))
            }
    }

    @Test
    fun typeTextPendingApprovalClosesAfterProcessRecoveryWithoutInputText() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-type-text-recovery",
            userMessageId = "message-type-text-recovery",
            goal = "等待确认文本输入",
        )
        val inputText = "Direct safe text"
        val persistedArguments = mapOf(
            "snapshot_id" to "snapshot-current",
            "ref" to "r1",
            "text_sha256" to "a9479104e48af2c58b1c68bbadbb38d4143c934508229270f7e84b282f59ff89",
            "text_length" to inputText.length.toString(),
        )
        val call = ToolCall(
            id = "tool-call-type-text-recovery",
            name = "device.type_text",
            arguments = mapOf(
                "snapshot_id" to "snapshot-current",
                "ref" to "r1",
                "text" to inputText,
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val definition = ToolDefinition(
            name = call.name,
            description = "向普通文本框输入非敏感文本",
            risk = call.risk,
        )
        val callMetadata = RunEventMetadata.ToolCall(
            id = call.id,
            toolName = call.name,
            risk = call.risk,
            arguments = persistedArguments,
            recoveryContract = ToolDefinitionRecoveryContract.snapshot(definition),
        )
        repository.appendEvent(run.id, "tool.call.proposed", "模型提出文本输入", callMetadata)
        repository.appendEvent(run.id, "tool.call.validated", "文本输入参数已校验", callMetadata)
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        repository.appendStep(
            runId = run.id,
            type = "approval",
            title = "应用侧审批",
            detail = "等待用户确认文本输入",
            status = AgentStepStatus.RUNNING,
        )
        repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = call,
            definition = definition,
        )

        assertTrue(repository.recoverPendingApprovalRuns(setOf(run.id)).isEmpty())
        assertEquals(1, repository.closeInterruptedRuns(runIds = setOf(run.id)))

        val closed = checkNotNull(repository.runDetail(run.id))
        assertEquals(AgentRunStatus.CANCELLED, closed.snapshot.run.status)
        assertEquals(ApprovalRequestStatus.CANCELLED, closed.approvals.single().status)
        assertFalse(closed.toString().contains(inputText))
        val recovery = closed.snapshot.events.single { it.type == "run.recovered" }
            .metadata as RunEventMetadata.Recovery
        assertEquals(
            AgentRunRestartDispositionCode.EPHEMERAL_TOOL_INPUT_UNAVAILABLE,
            recovery.restartDisposition?.code,
        )
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
        assertEquals(0, repository.closeInterruptedRuns())

        val snapshot = repository.snapshot(run.id)
        val recovered = snapshot.events.single { it.type == "run.recovered" }
        val metadata = recovered.metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.THINKING, metadata.fromStatus)
        assertEquals(AgentRunStatus.CANCELLED, metadata.toStatus)
        assertEquals("应用重启后终止上次未完成 Agent 任务", metadata.reason)
        assertEquals(AgentTaskRetryEvidenceCode.NOT_COMMITTED, metadata.retryEvidenceCode)
        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, metadata.resumeKind)
        assertEquals(
            AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE,
            metadata.restartDisposition?.code,
        )
        assertTrue(metadata.restartDisposition?.reason.orEmpty().contains("等待用户审批"))
        assertTrue(metadata.restartDisposition?.evidenceBoundary.orEmpty().contains("旧模型协程"))
        assertTrue(metadata.restartDisposition?.suggestedAction.orEmpty().contains("关联新 Run"))
        assertFalse(recovered.message.trimStart().startsWith("{"))
        assertEquals(
            1,
            snapshot.events.count { it.type == "run.status" && it.message == AgentRunStatus.CANCELLED.name },
        )
    }

    @Test
    fun interruptedExecutingToolWithoutResultPersistsCommitUnknownDisposition() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-commit-unknown",
            userMessageId = "message-commit-unknown",
            goal = "冻结提交状态未知的执行边界",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        val executionStep = repository.appendStep(
            runId = run.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = "notes.create",
            status = AgentStepStatus.RUNNING,
        )
        val call = ToolCall(
            id = "tool-call-commit-unknown",
            name = "notes.create",
            arguments = mapOf("title" to "提交状态未知"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.validated",
            message = "工具调用已通过校验：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )

        assertEquals(1, restartedRepository.closeInterruptedRuns())

        val closed = checkNotNull(restartedRepository.runDetail(run.id))
        assertEquals(AgentRunStatus.CANCELLED, closed.snapshot.run.status)
        assertEquals(
            AgentStepStatus.CANCELLED,
            closed.snapshot.steps.single { it.id == executionStep.id }.status,
        )
        assertTrue(closed.toolLedger.results.isEmpty())
        val recovery = closed.snapshot.events.single { it.type == "run.recovered" }
            .metadata as RunEventMetadata.Recovery
        assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, recovery.retryEvidenceCode)
        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, recovery.resumeKind)
        val disposition = checkNotNull(recovery.restartDisposition)
        assertEquals(AgentRunRestartDispositionCode.COMMIT_UNKNOWN, disposition.code)
        assertTrue(disposition.reason.contains("提交状态未知"))
        assertTrue(disposition.evidenceBoundary.contains("持久化结果"))
    }

    @Test
    fun interruptedAfterFailedToolResultAndBudgetAtomicallySettlesOriginalRunAsFailed() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-failed-result-settlement",
            userMessageId = "message-failed-result-settlement",
            goal = "恢复失败工具结果的终态",
        )
        val call = ToolCall(
            id = "tool-call-failed-result-settlement",
            name = "notes.create",
            arguments = mapOf("title" to "失败终态", "content" to "不应重放"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-failed-result-settlement",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.UNKNOWN,
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "初始化执行预算",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 0L),
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val executionStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_EXECUTE,
            "执行工具",
            "正在执行 ${call.name}",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行失败：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "网络请求失败",
                durationMs = 12L,
                success = false,
                verified = false,
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                executionReceipt = receipt,
            ),
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "工具执行预算：${call.name}",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 12L),
        )

        val competingRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )
        val closeCounts = listOf(
            async(Dispatchers.Default) { repository.closeInterruptedRuns(runIds = setOf(run.id)) },
            async(Dispatchers.Default) { competingRepository.closeInterruptedRuns(runIds = setOf(run.id)) },
        ).awaitAll()
        assertEquals(listOf(0, 1), closeCounts.sorted())
        assertEquals(0, repository.closeInterruptedRuns(runIds = setOf(run.id)))

        val settled = checkNotNull(repository.runDetail(run.id))
        assertEquals(AgentRunStatus.FAILED, settled.snapshot.run.status)
        assertEquals("工具执行失败：网络请求失败", settled.snapshot.run.errorMessage)
        assertEquals(
            AgentStepStatus.FAILED,
            settled.snapshot.steps.single { it.id == executionStep.id }.status,
        )
        assertEquals(1, settled.snapshot.events.count { it.type == "tool.result" })
        assertEquals(0, settled.snapshot.events.count { it.type == "tool.verify" })
        assertEquals(1, settled.snapshot.events.count { it.type == "run.failed" })
        assertEquals(
            1,
            settled.snapshot.events.count {
                it.type == "run.status" && it.message == AgentRunStatus.FAILED.name
            },
        )
        val recovery = settled.snapshot.events.single { it.type == "run.recovered" }
            .metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.EXECUTING, recovery.fromStatus)
        assertEquals(AgentRunStatus.FAILED, recovery.toStatus)
        assertEquals(AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT, recovery.resumeKind)
        assertEquals(
            "${AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT.name}:${call.id}",
            recovery.recoveryBoundaryKey,
        )
        assertEquals(receipt, settled.toolLedger.results.single().executionReceipt)
        val firstEvidence = AgentTaskRetryPolicy.assessEvidence(settled)
        val secondEvidence = AgentTaskRetryPolicy.assessEvidence(checkNotNull(repository.runDetail(run.id)))
        assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, firstEvidence.code)
        assertEquals(firstEvidence, secondEvidence)
    }

    @Test
    fun interruptedAfterFailedToolVerificationAtomicallySettlesOriginalRunAsFailed() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-failed-verification-settlement",
            userMessageId = "message-failed-verification-settlement",
            goal = "恢复失败工具验证的终态",
        )
        val call = ToolCall(
            id = "tool-call-failed-verification-settlement",
            name = "notes.create",
            arguments = mapOf("title" to "验证失败终态", "content" to "不应重复验证"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "初始化执行预算",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 0L),
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val executionStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_EXECUTE,
            "执行工具",
            "正在执行 ${call.name}",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "笔记写入返回成功",
                durationMs = 12L,
                success = true,
                verified = false,
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "note-failed-verification-settlement",
                    idempotencyKey = call.id,
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            ),
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "工具执行预算：${call.name}",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 12L),
        )
        repository.updateStep(executionStep.id, AgentStepStatus.COMPLETED, "笔记写入返回成功")
        repository.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
        val verificationStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_VERIFY,
            "执行后验证",
            "检查 Executor 回读结果",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.verify",
            "工具验证失败：${call.name}",
            RunEventMetadata.ToolVerification(
                toolName = call.name,
                status = ToolVerificationStatus.FAILED,
                toolCallId = call.id,
                reason = "Executor 回读结果不一致",
            ),
        )

        val competingRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )
        val closeCounts = listOf(
            async(Dispatchers.Default) { repository.closeInterruptedRuns(runIds = setOf(run.id)) },
            async(Dispatchers.Default) { competingRepository.closeInterruptedRuns(runIds = setOf(run.id)) },
        ).awaitAll()
        assertEquals(listOf(0, 1), closeCounts.sorted())
        assertEquals(0, repository.closeInterruptedRuns(runIds = setOf(run.id)))

        val settled = checkNotNull(repository.runDetail(run.id))
        assertEquals(AgentRunStatus.FAILED, settled.snapshot.run.status)
        assertEquals("工具验证失败：Executor 回读结果不一致", settled.snapshot.run.errorMessage)
        assertEquals(
            AgentStepStatus.COMPLETED,
            settled.snapshot.steps.single { it.id == executionStep.id }.status,
        )
        assertEquals(
            AgentStepStatus.FAILED,
            settled.snapshot.steps.single { it.id == verificationStep.id }.status,
        )
        assertEquals(1, settled.snapshot.events.count { it.type == "tool.result" })
        assertEquals(1, settled.snapshot.events.count { it.type == "tool.verify" })
        assertEquals(1, settled.snapshot.events.count { it.type == "run.failed" })
        val recovery = settled.snapshot.events.single { it.type == "run.recovered" }
            .metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.VERIFYING, recovery.fromStatus)
        assertEquals(AgentRunStatus.FAILED, recovery.toStatus)
        assertEquals(
            AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
            recovery.resumeKind,
        )
        assertEquals(
            "${AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT.name}:${call.id}",
            recovery.recoveryBoundaryKey,
        )
    }

    @Test
    fun failedToolVerificationSettlementRollsBackWhenTerminalAuditWriteFails() = runBlocking {
        val candidate = createPersistedVerificationFailureSettlementCandidate("rollback")
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_failed_tool_verification_settlement
            BEFORE INSERT ON run_events
            WHEN NEW.type = 'run.failed'
            BEGIN
                SELECT RAISE(ABORT, 'injected run.failed failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            repository.closeInterruptedRuns(runIds = setOf(candidate.runId))
        }.exceptionOrNull()
        val rolledBack = checkNotNull(repository.runDetail(candidate.runId))

        assertTrue(failure != null)
        assertEquals(AgentRunStatus.VERIFYING, rolledBack.snapshot.run.status)
        assertEquals(
            AgentStepStatus.RUNNING,
            rolledBack.snapshot.steps.single { it.id == candidate.verificationStepId }.status,
        )
        assertEquals(0, rolledBack.snapshot.events.count { it.type == "run.recovered" })
        assertEquals(0, rolledBack.snapshot.events.count { it.type == "run.failed" })

        database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER abort_failed_tool_verification_settlement",
        )
        assertEquals(1, repository.closeInterruptedRuns(runIds = setOf(candidate.runId)))
        assertEquals(
            AgentRunStatus.FAILED,
            checkNotNull(repository.runDetail(candidate.runId)).snapshot.run.status,
        )
    }

    @Test
    fun runtimeVerificationFailureBoundaryPersistsFailureOnceBeforeRepositorySettles() = runBlocking {
        val definition = ToolDefinition(
            name = "test.verification_failure_once",
            description = "返回成功结果但失败回读验证，用于验证进程重建不会重复验证。",
            risk = ToolRisk.SAFE,
            replaySafety = ToolReplaySafety.RESTART_REQUIRED,
            verificationPolicy = com.longdev.xiaoling.agent.ToolVerificationPolicy.EXECUTOR_VERIFIED,
        )
        val call = ToolCall(
            id = "tool-call-runtime-verification-failure-once",
            name = definition.name,
            arguments = emptyMap(),
            risk = definition.risk,
        )
        var executeCount = 0
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = listOf(definition)

            override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

            override suspend fun execute(call: ToolCall): ToolExecutionResult {
                executeCount += 1
                return ToolExecutionResult(
                    success = true,
                    content = "工具返回成功，但 Executor 回读不一致",
                    verified = false,
                )
            }
        }
        val failure = runCatching {
            MinimalAgentRuntime(
                ledger = repository,
                toolRegistry = registry,
                llm = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = call

                    override suspend fun summarize(
                        goal: String,
                        toolCall: ToolCall,
                        toolResult: ToolExecutionResult,
                    ): String = error("验证失败后不应进入总结")
                },
                faultInjector = object : AgentRuntimeFaultInjector {
                    override fun afterToolVerificationPersisted(
                        runId: String,
                        call: ToolCall,
                        result: ToolExecutionResult,
                    ) {
                        throw AgentProcessTerminationSimulation()
                    }
                },
            ).run(
                conversationId = "conversation-runtime-verification-failure-once",
                userMessageId = "message-runtime-verification-failure-once",
                goal = "验证失败事实落库后进程终止",
            )
        }.exceptionOrNull()

        assertTrue(failure is AgentProcessTerminationSimulation)
        assertEquals(1, executeCount)
        val activeRunId = database.agentRunDao()
            .getRunsByStatuses(listOf(AgentRunStatus.VERIFYING.name))
            .single()
            .id
        val interrupted = checkNotNull(repository.runDetail(activeRunId))
        assertEquals(
            AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
            AgentRunResumePolicy.assess(interrupted).kind,
        )

        assertEquals(1, repository.closeInterruptedRuns(runIds = setOf(activeRunId)))

        val settled = checkNotNull(repository.runDetail(activeRunId))
        assertEquals(1, executeCount)
        assertEquals(AgentRunStatus.FAILED, settled.snapshot.run.status)
        assertEquals(1, settled.snapshot.events.count { it.type == "tool.result" })
        val verification = settled.snapshot.events.single { it.type == "tool.verify" }
            .metadata as RunEventMetadata.ToolVerification
        assertEquals(ToolVerificationStatus.FAILED, verification.status)
        assertTrue(verification.reason.orEmpty().contains("未通过 Executor 回读验证"))
    }

    @Test
    fun runtimeFailureBoundaryExecutesToolOnceBeforeRepositorySettlesFailure() = runBlocking {
        val definition = ToolDefinition(
            name = "test.failure_once",
            description = "返回稳定失败结果，用于验证进程重建不会重放 Executor。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.RESTART_REQUIRED,
        )
        val call = ToolCall(
            id = "tool-call-runtime-failure-once",
            name = definition.name,
            arguments = emptyMap(),
            risk = definition.risk,
        )
        var executeCount = 0
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = listOf(definition)

            override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }

            override suspend fun execute(call: ToolCall): ToolExecutionResult {
                executeCount += 1
                return ToolExecutionResult(
                    success = false,
                    verified = false,
                    content = "稳定失败",
                    executionReceipt = ToolExecutionReceipt(
                        toolCallId = call.id,
                        operationId = "runtime-failure-once",
                        idempotencyKey = call.id,
                        status = ToolExecutionReceiptStatus.UNKNOWN,
                    ),
                )
            }
        }
        val runtime = MinimalAgentRuntime(
            ledger = repository,
            toolRegistry = registry,
            llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = call

                override suspend fun summarize(
                    goal: String,
                    toolCall: ToolCall,
                    toolResult: ToolExecutionResult,
                ): String = error("失败 Result 后不应进入总结")
            },
            faultInjector = object : AgentRuntimeFaultInjector {
                override fun afterToolResultPersisted(
                    runId: String,
                    call: ToolCall,
                    result: ToolExecutionResult,
                ) {
                    throw AgentProcessTerminationSimulation()
                }
            },
        )

        val failure = runCatching {
            runtime.run(
                conversationId = "conversation-runtime-failure-once",
                userMessageId = "message-runtime-failure-once",
                goal = "验证失败工具不重放",
            )
        }.exceptionOrNull()
        val activeRunId = database.agentRunDao()
            .getRunsByStatuses(listOf(AgentRunStatus.EXECUTING.name))
            .single()
            .id
        val interrupted = checkNotNull(repository.runDetail(activeRunId))

        assertTrue(failure is AgentProcessTerminationSimulation)
        assertEquals(1, executeCount)
        assertEquals(
            AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT,
            AgentRunResumePolicy.assess(interrupted).kind,
        )

        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )
        assertEquals(1, restartedRepository.closeInterruptedRuns(runIds = setOf(activeRunId)))
        assertEquals(0, restartedRepository.closeInterruptedRuns(runIds = setOf(activeRunId)))

        val settled = checkNotNull(restartedRepository.runDetail(activeRunId))
        assertEquals(1, executeCount)
        assertEquals(AgentRunStatus.FAILED, settled.snapshot.run.status)
        assertEquals(1, settled.snapshot.events.count { it.type == "tool.result" })
        assertEquals(0, settled.snapshot.events.count { it.type == "tool.verify" })
    }

    @Test
    fun failedToolSettlementRollsBackWhenTerminalAuditWriteFails() = runBlocking {
        val candidate = createPersistedFailureSettlementCandidate("rollback")
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_failed_tool_settlement
            BEFORE INSERT ON run_events
            WHEN NEW.type = 'run.failed'
            BEGIN
                SELECT RAISE(ABORT, 'injected run.failed failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            repository.closeInterruptedRuns(runIds = setOf(candidate.runId))
        }.exceptionOrNull()
        val rolledBack = checkNotNull(repository.runDetail(candidate.runId))

        assertTrue(failure != null)
        assertEquals(AgentRunStatus.EXECUTING, rolledBack.snapshot.run.status)
        assertEquals(
            AgentStepStatus.RUNNING,
            rolledBack.snapshot.steps.single { it.id == candidate.executionStepId }.status,
        )
        assertEquals(0, rolledBack.snapshot.events.count { it.type == "run.recovered" })
        assertEquals(0, rolledBack.snapshot.events.count { it.type == "run.failed" })

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_failed_tool_settlement")
        assertEquals(1, repository.closeInterruptedRuns(runIds = setOf(candidate.runId)))
        assertEquals(
            AgentRunStatus.FAILED,
            checkNotNull(repository.runDetail(candidate.runId)).snapshot.run.status,
        )
    }

    @Test
    fun interruptedBeforeExecutionStepDoesNotPersistCommitUnknownDisposition() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-before-execution-step",
            userMessageId = "message-before-execution-step",
            goal = "冻结执行步骤落库前的边界",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        val call = ToolCall(
            id = "tool-call-before-execution-step",
            name = "notes.create",
            arguments = mapOf("title" to "尚未进入执行步骤"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.validated",
            message = "工具调用已通过校验：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val restartedRepository = RoomAgentRunRepository(
            ApplicationProvider.getApplicationContext<Context>(),
            database,
        )

        assertEquals(1, restartedRepository.closeInterruptedRuns())

        val closed = checkNotNull(restartedRepository.runDetail(run.id))
        assertEquals(AgentRunStatus.CANCELLED, closed.snapshot.run.status)
        assertTrue(closed.toolLedger.results.isEmpty())
        val recovery = closed.snapshot.events.single { it.type == "run.recovered" }
            .metadata as RunEventMetadata.Recovery
        assertEquals(AgentTaskRetryEvidenceCode.NOT_COMMITTED, recovery.retryEvidenceCode)
        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, recovery.resumeKind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            checkNotNull(recovery.restartDisposition).code,
        )
    }

    @Test
    fun notCommittedReplayQualificationSurvivesDiskRoomReopen() = runBlocking {
        withDiskReopenedNotCommittedReplayRun("xiaoling-not-committed-qualification-test.db") {
                restartedRepository,
                restartedRegistry,
                runId,
                _,
            ->
            val assessment = AgentRunResumePolicy.assess(
                detail = checkNotNull(restartedRepository.runDetail(runId)),
                definitionLookup = restartedRegistry::definition,
                committedVerificationSupport = restartedRegistry::supportsCommittedEffectVerification,
            )

            assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
            assertFalse(assessment.canResumeInPlace)
            assertEquals(
                AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
                checkNotNull(assessment.restartDisposition).code,
            )
        }
    }

    @Test
    fun closeInterruptedRunPersistsReplayQualificationWithoutChangingOldRunBoundary() = runBlocking {
        withDiskReopenedNotCommittedReplayRun("xiaoling-not-committed-recovery-test.db") {
                restartedRepository,
                restartedRegistry,
                runId,
                callId,
            ->
            assertEquals(
                1,
                restartedRepository.closeInterruptedRuns(
                    definitionLookup = restartedRegistry::definition,
                    committedVerificationSupport = restartedRegistry::supportsCommittedEffectVerification,
                    runIds = setOf(runId),
                ),
            )

            val closed = checkNotNull(restartedRepository.runDetail(runId))
            assertEquals(AgentRunStatus.CANCELLED, closed.snapshot.run.status)
            assertTrue(closed.toolLedger.results.isEmpty())
            assertEquals(callId, closed.toolLedger.calls.single().id)
            val recovery = closed.snapshot.events.single { it.type == "run.recovered" }
                .metadata as RunEventMetadata.Recovery
            assertEquals(AgentTaskRetryEvidenceCode.NOT_COMMITTED, recovery.retryEvidenceCode)
            assertEquals(AgentRunResumeKind.RESTART_REQUIRED, recovery.resumeKind)
            assertEquals(
                AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
                checkNotNull(recovery.restartDisposition).code,
            )
        }
    }

    @Test
    fun controlledReplayCreatesFreshApprovedLedgerAndLeavesClosedSourceRunUnchanged() = runBlocking {
        withDiskReopenedNotCommittedReplayRun("xiaoling-not-committed-controlled-replay-test.db") {
                restartedRepository,
                restartedRegistry,
                runId,
                sourceCallId,
            ->
            assertEquals(
                1,
                restartedRepository.closeInterruptedRuns(
                    definitionLookup = restartedRegistry::definition,
                    committedVerificationSupport = restartedRegistry::supportsCommittedEffectVerification,
                    runIds = setOf(runId),
                ),
            )
            val sourceBeforeReplay = checkNotNull(restartedRepository.runDetail(runId))
            val sourceProfile = checkNotNull(sourceBeforeReplay.agentProfileSnapshotOrNull())
            val qualification = when (
                val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
                    detail = sourceBeforeReplay,
                    agentProfile = sourceProfile,
                    definitionLookup = restartedRegistry::definition,
                )
            ) {
                is AgentNotCommittedReplayQualificationAssessment.Eligible -> assessment.qualification
                is AgentNotCommittedReplayQualificationAssessment.Ineligible -> error(assessment.reason)
            }
            var approvalCount = 0
            val summary = MinimalAgentRuntime(
                ledger = restartedRepository,
                toolRegistry = restartedRegistry,
                llm = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                        error("受控重放不应重新规划")
                    }

                    override suspend fun summarize(
                        goal: String,
                        toolCall: ToolCall,
                        toolResult: ToolExecutionResult,
                    ): String = """{"style":"compact","tone":"neutral"}"""
                },
                approvalGate = object : ApprovalGate {
                    override suspend fun requestApproval(
                        runId: String,
                        toolCall: ToolCall,
                        definition: ToolDefinition,
                    ): ApprovalDecision {
                        approvalCount += 1
                        val request = restartedRepository.createApprovalRequest(
                            conversationId = sourceBeforeReplay.snapshot.run.conversationId,
                            runId = runId,
                            toolCall = toolCall,
                            definition = definition,
                        )
                        checkNotNull(
                            restartedRepository.decideApprovalRequest(
                                requestId = request.id,
                                status = ApprovalRequestStatus.APPROVED,
                                reason = "用户批准关联新 Run 工具",
                            ),
                        )
                        return ApprovalDecision(true, "用户批准关联新 Run 工具")
                    }
                },
            ).runControlledReplay(
                conversationId = sourceBeforeReplay.snapshot.run.conversationId,
                userMessageId = "message-controlled-replay",
                goal = sourceBeforeReplay.snapshot.run.goal,
                sourceRunId = runId,
                qualification = qualification,
                agentProfile = sourceProfile,
            )

            val newDetail = checkNotNull(restartedRepository.runDetail(summary.runId))
            val newCall = newDetail.toolLedger.calls.single()
            assertEquals(1, approvalCount)
            assertEquals(AgentRunStatus.COMPLETED, newDetail.snapshot.run.status)
            assertEquals(runId, newDetail.snapshot.run.retryOfRunId)
            assertFalse(sourceCallId == newCall.id)
            assertEquals(qualification.toolCall.name, newCall.toolName)
            assertEquals(qualification.toolCall.arguments, newCall.arguments)
            assertEquals(newCall.id, newDetail.approvals.single().toolCallId)
            assertEquals(ApprovalRequestStatus.APPROVED, newDetail.approvals.single().status)
            assertEquals(1, newDetail.toolLedger.results.size)
            val replayLink = newDetail.snapshot.events
                .single { it.type == AgentEventTypes.CONTROLLED_REPLAY_LINKED }
                .metadata as RunEventMetadata.ControlledReplay
            assertEquals(runId, replayLink.sourceRunId)
            assertEquals(sourceCallId, replayLink.sourceToolCallId)
            assertEquals(newCall.id, replayLink.newToolCallId)
            assertEquals(
                qualification.recoveryContract.definitionFingerprint,
                replayLink.definitionFingerprint,
            )
            assertEquals(sourceBeforeReplay, restartedRepository.runDetail(runId))
        }
    }

    @Test
    fun userStopCancelsOnlyTargetActiveRunAndItsRunningStep() = runBlocking {
        val target = repository.createRun(
            conversationId = "conversation-user-stop-target",
            userMessageId = "message-user-stop-target",
            goal = "停止当前后台任务",
        )
        repository.updateRunStatus(target.id, AgentRunStatus.THINKING)
        val targetStep = repository.appendStep(
            runId = target.id,
            type = AgentStepTypes.LLM_PLAN,
            title = "模型决策",
            detail = "等待用户停止",
            status = AgentStepStatus.RUNNING,
        )
        val targetApproval = repository.createApprovalRequest(
            conversationId = target.conversationId,
            runId = target.id,
            toolCall = ToolCall(
                id = "tool-call-user-stop",
                name = "memory.remember",
                arguments = mapOf("content" to "不应被迟到审批覆盖"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
            definition = ToolDefinition(
                name = "memory.remember",
                description = "写入长期记忆",
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        val unrelated = repository.createRun(
            conversationId = "conversation-user-stop-unrelated",
            userMessageId = "message-user-stop-unrelated",
            goal = "无关前台任务",
        )
        repository.updateRunStatus(unrelated.id, AgentRunStatus.THINKING)

        assertTrue(repository.cancelActiveRun(target.id, "用户停止后台工作流"))
        assertFalse(repository.cancelActiveRun(target.id, "用户停止后台工作流"))

        // long: 模拟旧 Worker 在用户停止后才收到模型/审批结果；整条子账本必须冻结，不能只保护 Run 顶层状态。
        repository.updateStep(targetStep.id, AgentStepStatus.COMPLETED, "迟到模型响应")
        repository.appendEvent(target.id, "model.response", "迟到模型响应")
        val lateStep = runCatching {
            repository.appendStep(
                runId = target.id,
                type = AgentStepTypes.LLM_PLAN,
                title = "迟到步骤",
                detail = "不应写入",
                status = AgentStepStatus.RUNNING,
            )
        }
        assertTrue(lateStep.isFailure)
        assertNull(
            repository.decideApprovalRequest(
                targetApproval.id,
                ApprovalRequestStatus.APPROVED,
                "迟到审批",
            ),
        )

        val cancelled = repository.runDetail(target.id)!!
        assertEquals(AgentRunStatus.CANCELLED, cancelled.snapshot.run.status)
        assertEquals(AgentStepStatus.CANCELLED, cancelled.snapshot.steps.single { it.id == targetStep.id }.status)
        assertFalse(cancelled.snapshot.steps.any { it.title == "迟到步骤" })
        assertFalse(cancelled.snapshot.events.any { it.message == "迟到模型响应" })
        assertEquals(
            ApprovalRequestStatus.CANCELLED,
            cancelled.approvals.single { it.id == targetApproval.id }.status,
        )
        assertEquals(
            "用户停止后台工作流",
            (cancelled.snapshot.events.single { it.type == "run.cancelled" }.metadata as RunEventMetadata.Reason).reason,
        )
        assertEquals(AgentRunStatus.THINKING, repository.runDetail(unrelated.id)!!.snapshot.run.status)
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
            assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, recovery.retryEvidenceCode)
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
    fun linkedRetryPersistsRelationAndPreservesSourceAuditAcrossRepositoryRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "xiaoling-stage192-linked-retry-audit.db"
        context.deleteDatabase(databaseName)
        var openedDatabase: XiaoLingDatabase? = null
        var reopenedDatabase: XiaoLingDatabase? = null
        try {
            val firstOpenedDatabase = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
            openedDatabase = firstOpenedDatabase
            val firstRepository = RoomAgentRunRepository(context, firstOpenedDatabase)
            val sourceRun = firstRepository.createRun(
                conversationId = "conversation-stage192-linked-retry",
                userMessageId = "message-stage192-source",
                goal = "验证来源 Run 审计历史在确认后创建新 Run 时保持不变",
            )
            firstRepository.updateRunStatus(sourceRun.id, AgentRunStatus.EXECUTING)
            val definition = ToolDefinition(
                name = "notes.create",
                description = "创建本地笔记",
                risk = ToolRisk.REQUIRES_APPROVAL,
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            )
            val call = ToolCall(
                id = "tool-call-stage192-source",
                name = definition.name,
                arguments = mapOf(
                    "title" to "第192阶段来源审计",
                    "content" to "来源 Tool Result 必须继续留在旧 Run",
                ),
                risk = definition.risk,
            )
            val callMetadata = RunEventMetadata.ToolCall(
                id = call.id,
                toolName = call.name,
                risk = call.risk,
                arguments = call.arguments,
                recoveryContract = ToolDefinitionRecoveryContract.snapshot(definition),
            )
            firstRepository.appendEvent(sourceRun.id, "tool.call.proposed", "模型提出工具调用", callMetadata)
            firstRepository.appendEvent(sourceRun.id, "tool.call.validated", "工具调用已校验", callMetadata)
            val executionStep = firstRepository.appendStep(
                runId = sourceRun.id,
                type = AgentStepTypes.TOOL_EXECUTE,
                title = "写入本地笔记",
                detail = "来源 Run 已产生独立工具事实",
                status = AgentStepStatus.RUNNING,
            )
            val approval = firstRepository.createApprovalRequest(
                conversationId = sourceRun.conversationId,
                runId = sourceRun.id,
                toolCall = call,
                definition = definition,
            )
            checkNotNull(
                firstRepository.decideApprovalRequest(
                    requestId = approval.id,
                    status = ApprovalRequestStatus.APPROVED,
                    reason = "第192阶段来源 Run 已批准",
                ),
            )
            firstRepository.appendEvent(
                runId = sourceRun.id,
                type = "tool.result",
                message = "工具执行成功：${call.name}",
                metadata = RunEventMetadata.ToolResult(
                    toolName = call.name,
                    content = "来源 Tool Result 正文",
                    durationMs = 21L,
                    success = true,
                    verified = true,
                    toolCallId = call.id,
                    replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                    executionReceipt = ToolExecutionReceipt(
                        toolCallId = call.id,
                        operationId = "stage192-source-operation",
                        idempotencyKey = call.id,
                        status = ToolExecutionReceiptStatus.COMMITTED,
                    ),
                ),
            )
            firstRepository.updateStep(
                stepId = executionStep.id,
                status = AgentStepStatus.COMPLETED,
                detail = "写入事实已落库",
            )
            firstRepository.appendEvent(
                runId = sourceRun.id,
                type = "stage192.source.audit",
                message = "来源 Run 的审计历史保留",
                metadata = RunEventMetadata.Reason("确认后创建关联 Run 不复制旧事实"),
            )
            firstRepository.updateRunStatus(
                runId = sourceRun.id,
                status = AgentRunStatus.FAILED,
                errorMessage = "后续目标验证失败，来源 Run 保持终态",
            )

            val sourceBeforeLink = checkNotNull(firstRepository.runDetail(sourceRun.id))
            assertEquals(AgentRunStatus.FAILED, sourceBeforeLink.snapshot.run.status)
            assertEquals(1, sourceBeforeLink.snapshot.steps.size)
            assertEquals(1, sourceBeforeLink.toolLedger.results.size)
            assertEquals(1, sourceBeforeLink.approvals.size)

            // long: 先重建一次 Repository 再执行确认后的创建，确保关联关系不是只存在于旧进程对象，而是以 Room 事实为准。
            firstOpenedDatabase.close()
            openedDatabase = null
            val firstReopenedDatabase = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
            reopenedDatabase = firstReopenedDatabase
            val restartedRepository = RoomAgentRunRepository(context, firstReopenedDatabase)
            assertEquals(sourceBeforeLink, restartedRepository.runDetail(sourceRun.id))

            val linkedRun = restartedRepository.createRun(
                conversationId = sourceRun.conversationId,
                userMessageId = "message-stage192-linked",
                goal = sourceRun.goal,
                retryOfRunId = sourceRun.id,
            )
            assertFalse(linkedRun.id == sourceRun.id)
            assertEquals(sourceRun.id, linkedRun.retryOfRunId)
            assertEquals(sourceBeforeLink, restartedRepository.runDetail(sourceRun.id))

            val linkedDetail = checkNotNull(restartedRepository.runDetail(linkedRun.id))
            assertEquals(AgentRunStatus.QUEUED, linkedDetail.snapshot.run.status)
            assertEquals(sourceRun.id, linkedDetail.snapshot.run.retryOfRunId)
            assertTrue(linkedDetail.snapshot.steps.isEmpty())
            assertTrue(linkedDetail.toolLedger.calls.isEmpty())
            assertTrue(linkedDetail.toolLedger.results.isEmpty())
            assertTrue(linkedDetail.approvals.isEmpty())
            assertEquals(listOf("run.created"), linkedDetail.snapshot.events.map { it.type })

            // long: 再次关闭/重开后同时读取来源与新 Run，验证 retryOfRunId、终态和全部审计子账本都真正持久化。
            firstReopenedDatabase.close()
            reopenedDatabase = null
            val finalDatabase = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
                .also { reopenedDatabase = it }
            val finalRepository = RoomAgentRunRepository(context, finalDatabase)
            assertEquals(sourceBeforeLink, finalRepository.runDetail(sourceRun.id))
            val finalLinked = checkNotNull(finalRepository.runDetail(linkedRun.id))
            assertEquals(sourceRun.id, finalLinked.snapshot.run.retryOfRunId)
            assertEquals(AgentRunStatus.QUEUED, finalLinked.snapshot.run.status)
            assertTrue(finalLinked.snapshot.steps.isEmpty())
            assertTrue(finalLinked.toolLedger.calls.isEmpty())
            assertTrue(finalLinked.toolLedger.results.isEmpty())
            assertTrue(finalLinked.approvals.isEmpty())
            assertEquals(listOf("run.created"), finalLinked.snapshot.events.map { it.type })
        } finally {
            openedDatabase?.close()
            reopenedDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun retryPolicyRequiresConfirmationWhenCommittedWriteReportsVerificationFailure() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-retry-ledger",
            userMessageId = "message-retry-ledger",
            goal = "验证异常账本的重试确认",
        )
        val call = ToolCall(
            id = "tool-call-retry-ledger",
            name = "notes.create",
            arguments = mapOf("title" to "重试保护", "content" to "已提交副作用"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.validated",
            message = "工具调用已校验：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-retry-ledger",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        // long: 写入已经提交但回读验证失败时，业务结果会是失败；重试仍必须依据 COMMITTED 回执要求确认，避免新 ToolCall ID 再次写入。
        repository.appendEvent(
            runId = run.id,
            type = "tool.result",
            message = "笔记已写入但回读验证失败",
            metadata = RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "笔记已写入但回读验证失败",
                durationMs = 5L,
                success = false,
                verified = false,
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                executionReceipt = receipt,
            ),
        )
        repository.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = "模拟后续步骤失败")

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            AgentTaskRetryPolicy.evaluate(checkNotNull(repository.runDetail(run.id))),
        )
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
            knowledgeStore = RoomKnowledgeDocumentStore(context, database),
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
            type = "tool.call.proposed",
            message = "模型提出工具调用：${call.name}",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
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
        repository.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
        repository.appendEvent(
            runId = run.id,
            type = "run.recovered",
            message = "已恢复已提交工具结果，准备只读验证",
            metadata = RunEventMetadata.Recovery(
                fromStatus = AgentRunStatus.EXECUTING,
                toStatus = AgentRunStatus.VERIFYING,
                reason = "旧版本已经登记同一已提交工具边界",
            ),
        )
        val restartedRepository = RoomAgentRunRepository(context, database)
        val assessment = AgentRunResumePolicy.assess(
            checkNotNull(restartedRepository.runDetail(run.id)),
            registry::definition,
            registry::supportsCommittedEffectVerification,
        )
        assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, assessment.committedTool?.evidenceSource)

        val recovered = restartedRepository
            .recoverCommittedToolRuns(
                registry::definition,
                registry::supportsCommittedEffectVerification,
            )
            .single { it.snapshot.run.id == run.id }
        val recoveredAgain = restartedRepository
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
        assertEquals(1, finalDetail.snapshot.events.count { it.type == "run.recovered" })
        assertEquals(1, finalDetail.snapshot.events.count { it.type == "run.status" && it.message == AgentRunStatus.VERIFYING.name })
        assertEquals(AgentRunStatus.VERIFYING, recoveredAgain.snapshot.run.status)
        assertEquals(listOf(receipt.operationId), noteStore.list(10).map { it.id })
        assertTrue(summary.responseText.contains("进程恢复笔记"))
    }

    @Test
    fun firstCommittedRecoveryAtomicallyWritesStatusAndBoundaryMarkerOnce() = runBlocking {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val run = repository.createRun(
            conversationId = "conversation-committed-marker-cas",
            userMessageId = "message-committed-marker-cas",
            goal = "验证首次 committed 恢复事务",
        )
        val call = ToolCall(
            id = "tool-call-committed-marker-cas",
            name = definition.name,
            arguments = mapOf("title" to "事务恢复", "content" to "只读验证"),
            risk = definition.risk,
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_EXECUTE,
            "执行工具",
            "结果已提交，等待只读验证",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具结果已提交",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "笔记已提交",
                durationMs = 5L,
                success = true,
                verified = true,
                toolCallId = call.id,
                replaySafety = definition.replaySafety,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "note-committed-marker-cas",
                    idempotencyKey = call.id,
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            ),
        )

        val first = repository.recoverCommittedToolRuns({ definition }, { true }).single()
        val second = repository.recoverCommittedToolRuns({ definition }, { true }).single()
        val snapshot = repository.snapshot(run.id)

        assertEquals(AgentRunStatus.VERIFYING, first.snapshot.run.status)
        assertEquals(AgentRunStatus.VERIFYING, second.snapshot.run.status)
        assertEquals(1, snapshot.events.count { it.type == "run.recovered" })
        assertEquals(
            1,
            snapshot.events.count { it.type == "run.status" && it.message == AgentRunStatus.VERIFYING.name },
        )
        val recovery = snapshot.events.single { it.type == "run.recovered" }.metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.EXECUTING, recovery.fromStatus)
        assertEquals(AgentRunStatus.VERIFYING, recovery.toStatus)
        assertEquals(AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION, recovery.resumeKind)
        assertEquals(
            "${AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION.name}:${call.id}",
            recovery.recoveryBoundaryKey,
        )
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
                knowledgeStore = RoomKnowledgeDocumentStore(context, opened),
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
                type = "tool.call.proposed",
                message = "模型提出工具调用：${call.name}",
                metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
            )
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
                knowledgeStore = RoomKnowledgeDocumentStore(context, reopened),
            )
            val assessment = AgentRunResumePolicy.assess(
                checkNotNull(restartedRepository.runDetail(run.id)),
                restartedRegistry::definition,
                restartedRegistry::supportsCommittedEffectVerification,
            )
            check(assessment.kind == AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION) { assessment.reason }
            assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, assessment.committedTool?.evidenceSource)
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
    fun processRestartCompletesFullyVerifiedRunWithoutAppendingDuplicateToolFacts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "xiaoling-verified-run-completion-test.db"
        context.deleteDatabase(databaseName)
        var firstDatabase: XiaoLingDatabase? = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        var restartedDatabase: XiaoLingDatabase? = null
        try {
            val opened = checkNotNull(firstDatabase)
            val firstRepository = RoomAgentRunRepository(context, opened)
            val registry = FakeToolRegistry()
            val definition = checkNotNull(registry.definition("fake.echo"))
            val run = firstRepository.createRun(
                conversationId = "conversation-verified-run-completion",
                userMessageId = "message-verified-run-completion",
                goal = "验证完成后收敛原 Run",
            )
            val call = ToolCall(
                id = "tool-call-verified-room",
                name = definition.name,
                arguments = mapOf("goal" to run.goal),
                risk = definition.risk,
            )
            firstRepository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
            val callMetadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments)
            firstRepository.appendEvent(run.id, "tool.call.proposed", "模型提出工具调用", callMetadata)
            firstRepository.appendEvent(run.id, "tool.call.validated", "工具调用已校验", callMetadata)
            firstRepository.appendStep(
                run.id,
                AgentStepTypes.TOOL_EXECUTE,
                "执行工具",
                "工具执行已经完成",
                AgentStepStatus.COMPLETED,
            )
            firstRepository.appendEvent(
                run.id,
                "tool.result",
                "工具执行成功",
                RunEventMetadata.ToolResult(
                    toolName = call.name,
                    content = "fake.echo 已执行：${run.goal}",
                    durationMs = 4L,
                    success = true,
                    verified = true,
                    toolCallId = call.id,
                    replaySafety = definition.replaySafety,
                ),
            )
            firstRepository.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
            val verificationStep = firstRepository.appendStep(
                run.id,
                AgentStepTypes.TOOL_VERIFY,
                "执行后验证",
                "验证事件已经落库，进程在 Step 收尾前终止",
                AgentStepStatus.RUNNING,
            )
            firstRepository.appendEvent(
                run.id,
                "tool.verify",
                "工具验证通过",
                RunEventMetadata.ToolVerification(call.name, com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED, call.id),
            )

            // long: 关闭并重开磁盘 Room，模拟 tool.verify 已提交但进程尚未完成控制面 Step 的真实边界。
            opened.close()
            firstDatabase = null
            val reopened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
                .also { restartedDatabase = it }
            val restartedRepository = RoomAgentRunRepository(context, reopened)
            val recovered = restartedRepository.recoverVerifiedToolRuns().single { it.snapshot.run.id == run.id }
            val recoveredAgain = restartedRepository.recoverVerifiedToolRuns().single { it.snapshot.run.id == run.id }
            val closedCount = restartedRepository.closeInterruptedRuns()
            fun recoveredRuntime() = MinimalAgentRuntime(
                ledger = restartedRepository,
                toolRegistry = registry,
                llm = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = error("已验证恢复不应重新规划")
                    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String = error("已验证恢复不应调用模型总结")
                },
            )
            // long: 两个初始化调用者可以同时拿到同一持久化边界；Room 的总结 Step/Event 必须原子复用，不能因重复控制面收尾破坏旧 Run。
            val summaries = listOf(
                async(Dispatchers.Default) { recoveredRuntime().resumeVerifiedToolRun(recovered) },
                async(Dispatchers.Default) { recoveredRuntime().resumeVerifiedToolRun(recoveredAgain) },
            ).awaitAll()

            val finalDetail = checkNotNull(restartedRepository.runDetail(run.id))
            assertEquals(0, closedCount)
            assertEquals(listOf(run.id, run.id), summaries.map { it.runId })
            assertEquals(AgentRunStatus.COMPLETED, finalDetail.snapshot.run.status)
            assertEquals(1, finalDetail.snapshot.events.count { it.type == "tool.result" })
            assertEquals(1, finalDetail.snapshot.events.count { it.type == "tool.verify" })
            assertEquals(1, finalDetail.snapshot.events.count { it.type == "run.recovered" })
            assertEquals(1, finalDetail.snapshot.events.count { it.type == AgentEventTypes.RECOVERY_SUMMARY })
            assertEquals(1, finalDetail.snapshot.steps.count { it.type == AgentStepTypes.RECOVERY_SUMMARIZE })
            assertEquals(AgentRunStatus.VERIFYING, recoveredAgain.snapshot.run.status)
            assertEquals(AgentStepStatus.COMPLETED, finalDetail.snapshot.steps.single { it.id == verificationStep.id }.status)
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
        repository.appendEvent(
            run.id,
            "run.recovered",
            "已恢复待审批 Run，等待用户决定",
            RunEventMetadata.Recovery(
                fromStatus = AgentRunStatus.WAITING_APPROVAL,
                toStatus = AgentRunStatus.WAITING_APPROVAL,
                reason = "旧审批边界的历史恢复事件",
            ),
        )
        val pendingCall = ToolCall(
            id = "tool-call-pending-recovery",
            name = "memory.remember",
            arguments = mapOf("content" to "用户喜欢紧凑界面"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${pendingCall.name}",
            RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${pendingCall.name}",
            RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
        )
        repository.appendStep(
            run.id,
            "approval",
            "应用侧审批",
            "等待应用侧审批 ${pendingCall.name}",
            AgentStepStatus.RUNNING,
        )
        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = pendingCall,
            definition = ToolDefinition(
                name = "memory.remember",
                description = "写入长期记忆",
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        val resumable = repository.recoverPendingApprovalRuns()
        val resumableAgain = repository.recoverPendingApprovalRuns()
        val closedCount = repository.closeInterruptedRuns()
        val snapshot = repository.snapshot(run.id)

        assertEquals(listOf(run.id), resumable.map { it.snapshot.run.id })
        assertEquals(listOf(run.id), resumableAgain.map { it.snapshot.run.id })
        assertEquals(0, closedCount)
        assertEquals(AgentRunStatus.WAITING_APPROVAL, snapshot.run.status)
        assertEquals(ApprovalRequestStatus.PENDING, repository.pendingApprovalRequests(run.conversationId).single { it.id == request.id }.status)
        val recovered = snapshot.events.last { it.type == "run.recovered" }
        val metadata = recovered.metadata as RunEventMetadata.Recovery
        assertEquals(AgentRunStatus.WAITING_APPROVAL, metadata.fromStatus)
        assertEquals(AgentRunStatus.WAITING_APPROVAL, metadata.toStatus)
        assertEquals(null, metadata.retryEvidenceCode)
        assertEquals(AgentRunResumeKind.APPROVAL_WAIT, metadata.resumeKind)
        assertEquals("${AgentRunResumeKind.APPROVAL_WAIT.name}:${request.id}", metadata.recoveryBoundaryKey)
        // long: 当前审批边界必须写入自己的 marker；旧版没有边界键的更早恢复事件不能吞掉新审批，连续重复当前恢复也不能再追加第三条。
        assertEquals(2, snapshot.events.count { it.type == "run.recovered" })
    }

    @Test
    fun pendingApprovalRecoveryRejectsConflictAfterValidBoundaryMarker() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-pending-marker-drift",
            userMessageId = "message-pending-marker-drift",
            goal = "拒绝漂移的审批恢复证据",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        val pendingCall = ToolCall(
            id = "tool-call-pending-marker-drift",
            name = "memory.remember",
            arguments = mapOf("content" to "不应静默接受漂移 marker"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${pendingCall.name}",
            RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${pendingCall.name}",
            RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
        )
        repository.appendStep(
            run.id,
            "approval",
            "应用侧审批",
            "等待用户决定",
            AgentStepStatus.RUNNING,
        )
        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = pendingCall,
            definition = ToolDefinition(
                name = pendingCall.name,
                description = "写入长期记忆",
                risk = pendingCall.risk,
            ),
        )
        assertEquals(
            listOf(run.id),
            repository.recoverPendingApprovalRuns(setOf(run.id)).map { it.snapshot.run.id },
        )
        repository.appendEvent(
            run.id,
            "run.recovered",
            "已恢复待审批 Run，等待用户决定",
            RunEventMetadata.Recovery(
                fromStatus = AgentRunStatus.WAITING_APPROVAL,
                toStatus = AgentRunStatus.WAITING_APPROVAL,
                reason = "与当前策略不一致的恢复原因",
                resumeKind = AgentRunResumeKind.APPROVAL_WAIT,
                recoveryBoundaryKey = "${AgentRunResumeKind.APPROVAL_WAIT.name}:${request.id}",
            ),
        )

        val recovered = repository.recoverPendingApprovalRuns(setOf(run.id))
        val snapshot = repository.snapshot(run.id)

        assertTrue(recovered.isEmpty())
        assertEquals(AgentRunStatus.WAITING_APPROVAL, snapshot.run.status)
        assertEquals(2, snapshot.events.count { it.type == "run.recovered" })
    }

    @Test
    fun recoveredApprovalRejectionAtomicallyClosesApprovalStepAndRun() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-recovered-rejection",
            userMessageId = "message-recovered-rejection",
            goal = "拒绝恢复后的写入",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        val pendingCall = ToolCall(
            id = "tool-call-recovered-rejection",
            name = "memory.remember",
            arguments = mapOf("content" to "不应保存"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${pendingCall.name}",
            RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${pendingCall.name}",
            RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
        )
        val approvalStep = repository.appendStep(
            run.id,
            "approval",
            "应用侧审批",
            "等待应用侧审批 ${pendingCall.name}",
            AgentStepStatus.RUNNING,
        )
        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = pendingCall,
            definition = ToolDefinition(
                name = pendingCall.name,
                description = "写入长期记忆",
                risk = pendingCall.risk,
            ),
        )

        val rejected = repository.rejectRecoveredApproval(
            requestId = request.id,
            runId = run.id,
            reason = "用户拒绝恢复后的工具执行",
        )

        assertEquals(AgentRunStatus.FAILED, rejected?.snapshot?.run?.status)
        assertEquals(
            AgentStepStatus.FAILED,
            rejected?.snapshot?.steps?.single { it.id == approvalStep.id }?.status,
        )
        assertEquals(
            ApprovalRequestStatus.DENIED,
            rejected?.approvals?.single { it.id == request.id }?.status,
        )
        val eventTypes = checkNotNull(rejected).snapshot.events.map { it.type }
        assertTrue(eventTypes.indexOf("approval.request_decided") < eventTypes.indexOf("step.status"))
        assertTrue(eventTypes.indexOf("step.status") < eventTypes.lastIndexOf("run.status"))
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
        val toolCall = ToolCall(
            id = "tool-call-room-resume",
            name = definition.name,
            arguments = mapOf("goal" to run.goal),
            risk = definition.risk,
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：${toolCall.name}",
            metadata = RunEventMetadata.ToolCall(
                id = toolCall.id,
                toolName = toolCall.name,
                risk = toolCall.risk,
                arguments = toolCall.arguments,
            ),
        )
        repository.appendEvent(
            runId = run.id,
            type = "tool.call.validated",
            message = "工具调用已校验：${toolCall.name}",
            metadata = RunEventMetadata.ToolCall(
                id = toolCall.id,
                toolName = toolCall.name,
                risk = toolCall.risk,
                arguments = toolCall.arguments,
            ),
        )
        val request = repository.createApprovalRequest(
            conversationId = run.conversationId,
            runId = run.id,
            toolCall = toolCall,
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
    fun secondApprovalAndVerifiedPrefixSurviveDiskRoomReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "xiaoling-second-approval-recovery-test.db"
        context.deleteDatabase(databaseName)
        var firstDatabase: XiaoLingDatabase? = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        var restartedDatabase: XiaoLingDatabase? = null
        try {
            val opened = checkNotNull(firstDatabase)
            val firstRepository = RoomAgentRunRepository(context, opened)
            val definition = checkNotNull(FakeToolRegistry().definition("fake.echo"))
            val run = firstRepository.createRun(
                conversationId = "conversation-second-approval-disk",
                userMessageId = "message-second-approval-disk",
                goal = "恢复第二次工具审批",
            )
            val firstCall = ToolCall(
                id = "tool-call-first-disk",
                name = definition.name,
                arguments = mapOf("goal" to "第一步"),
                risk = definition.risk,
            )
            val secondCall = ToolCall(
                id = "tool-call-second-disk",
                name = definition.name,
                arguments = mapOf("goal" to "第二步"),
                risk = definition.risk,
            )
            val firstMetadata = RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments)
            firstRepository.appendEvent(run.id, "tool.call.proposed", "模型提出第一步", firstMetadata)
            firstRepository.appendEvent(run.id, "tool.call.validated", "第一步已校验", firstMetadata)
            firstRepository.appendStep(
                run.id,
                AgentStepTypes.TOOL_EXECUTE,
                "执行工具",
                "执行第一步",
                AgentStepStatus.COMPLETED,
            )
            firstRepository.appendEvent(
                run.id,
                "tool.result",
                "第一步执行成功",
                RunEventMetadata.ToolResult(
                    toolName = firstCall.name,
                    content = "第一步已完成",
                    durationMs = 5L,
                    success = true,
                    verified = true,
                    toolCallId = firstCall.id,
                ),
            )
            firstRepository.appendStep(
                run.id,
                AgentStepTypes.TOOL_VERIFY,
                "执行后验证",
                "第一步验证通过",
                AgentStepStatus.COMPLETED,
            )
            firstRepository.appendEvent(
                run.id,
                "tool.verify",
                "第一步验证通过",
                RunEventMetadata.ToolVerification(firstCall.name, com.longdev.xiaoling.agent.ToolVerificationStatus.PASSED, firstCall.id),
            )
            val secondMetadata = RunEventMetadata.ToolCall(secondCall.id, secondCall.name, secondCall.risk, secondCall.arguments)
            firstRepository.appendEvent(run.id, "tool.call.proposed", "模型提出第二步", secondMetadata)
            firstRepository.appendEvent(run.id, "tool.call.validated", "第二步已校验", secondMetadata)
            val approvalStep = firstRepository.appendStep(
                run.id,
                "approval",
                "应用侧审批",
                "等待审批第二步",
                AgentStepStatus.RUNNING,
            )
            val approval = firstRepository.createApprovalRequest(
                conversationId = run.conversationId,
                runId = run.id,
                toolCall = secondCall,
                definition = definition,
            )
            firstRepository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)

            // long: 真正关闭并重开磁盘 Room，证明恢复判断只依赖持久化步骤、审批、typed event 和独立工具账本，不依赖旧进程对象。
            opened.close()
            firstDatabase = null
            val reopened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
                .also { restartedDatabase = it }
            val restartedRepository = RoomAgentRunRepository(context, reopened)
            val recovered = restartedRepository.recoverPendingApprovalRuns().single { it.snapshot.run.id == run.id }
            val assessment = AgentRunResumePolicy.assess(recovered)
            val closedCount = restartedRepository.closeInterruptedRuns()

            assertEquals(AgentRunResumeKind.APPROVAL_WAIT, assessment.kind)
            assertEquals(run.id, recovered.snapshot.run.id)
            assertEquals(approval.id, assessment.approvalWait?.approvalRequestId)
            assertEquals(secondCall, assessment.approvalWait?.toolCall)
            assertEquals(firstCall, assessment.approvalWait?.verifiedPrefix?.single()?.toolCall)
            assertEquals(approvalStep.id, assessment.approvalWait?.approvalStepId)
            assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, assessment.approvalWait?.evidenceSource)
            assertEquals(0, closedCount)
        } finally {
            firstDatabase?.close()
            restartedDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun retryCreatesLinkedRunWithoutChangingSourceRun() = runBlocking {
        val sourceRun = repository.createRun(
            conversationId = "conversation-retry",
            userMessageId = "message-source",
            goal = "完成可重试任务",
        )
        val toolCallId = "tool-call-retry-source"
        repository.appendEvent(
            runId = sourceRun.id,
            type = "tool.call.proposed",
            message = "模型提出工具调用：fake.echo",
            metadata = RunEventMetadata.ToolCall(
                id = toolCallId,
                toolName = "fake.echo",
                risk = ToolRisk.SAFE,
                arguments = mapOf("goal" to sourceRun.goal),
            ),
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
                toolCallId = toolCallId,
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

    private data class PersistedFailureSettlementCandidate(
        val runId: String,
        val executionStepId: String,
    )

    private data class PersistedVerificationFailureSettlementCandidate(
        val runId: String,
        val verificationStepId: String,
    )

    private suspend fun createPersistedVerificationFailureSettlementCandidate(
        suffix: String,
    ): PersistedVerificationFailureSettlementCandidate {
        val run = repository.createRun(
            conversationId = "conversation-verification-failed-settlement-$suffix",
            userMessageId = "message-verification-failed-settlement-$suffix",
            goal = "验证失败验证收敛事务：$suffix",
        )
        val call = ToolCall(
            id = "tool-call-verification-failed-settlement-$suffix",
            name = "notes.create",
            arguments = mapOf("title" to suffix, "content" to "失败验证事务必须原子"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "初始化执行预算",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 0L),
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val executionStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_EXECUTE,
            "执行工具",
            "正在执行 ${call.name}",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行成功：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "工具执行成功，等待验证",
                durationMs = 8L,
                success = true,
                verified = false,
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "verification-failed-operation-$suffix",
                    idempotencyKey = call.id,
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            ),
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "工具执行预算：${call.name}",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 8L),
        )
        repository.updateStep(executionStep.id, AgentStepStatus.COMPLETED, "工具执行成功")
        repository.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
        val verificationStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_VERIFY,
            "执行后验证",
            "验证失败事实即将落库",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.verify",
            "工具验证失败：${call.name}",
            RunEventMetadata.ToolVerification(
                toolName = call.name,
                status = ToolVerificationStatus.FAILED,
                toolCallId = call.id,
                reason = "稳定验证失败：$suffix",
            ),
        )
        return PersistedVerificationFailureSettlementCandidate(
            runId = run.id,
            verificationStepId = verificationStep.id,
        )
    }

    private suspend fun createPersistedFailureSettlementCandidate(
        suffix: String,
    ): PersistedFailureSettlementCandidate {
        val run = repository.createRun(
            conversationId = "conversation-failed-settlement-$suffix",
            userMessageId = "message-failed-settlement-$suffix",
            goal = "验证失败收敛事务：$suffix",
        )
        val call = ToolCall(
            id = "tool-call-failed-settlement-$suffix",
            name = "notes.create",
            arguments = mapOf("title" to suffix, "content" to "事务必须原子"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "初始化执行预算",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 0L),
        )
        repository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        repository.appendEvent(
            run.id,
            "tool.call.proposed",
            "模型提出工具调用：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        repository.appendEvent(
            run.id,
            "tool.call.validated",
            "工具调用已校验：${call.name}",
            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
        )
        val executionStep = repository.appendStep(
            run.id,
            AgentStepTypes.TOOL_EXECUTE,
            "执行工具",
            "正在执行 ${call.name}",
            AgentStepStatus.RUNNING,
        )
        repository.appendEvent(
            run.id,
            "tool.result",
            "工具执行失败：${call.name}",
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = "稳定失败：$suffix",
                durationMs = 8L,
                success = false,
                verified = false,
                toolCallId = call.id,
                replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "failed-operation-$suffix",
                    idempotencyKey = call.id,
                    status = ToolExecutionReceiptStatus.UNKNOWN,
                ),
            ),
        )
        repository.appendEvent(
            run.id,
            AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            "工具执行预算：${call.name}",
            RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 8L),
        )
        return PersistedFailureSettlementCandidate(
            runId = run.id,
            executionStepId = executionStep.id,
        )
    }

    private suspend fun withDiskReopenedNotCommittedReplayRun(
        databaseName: String,
        verify: suspend (
            repository: RoomAgentRunRepository,
            registry: XiaoLingToolRegistry,
            runId: String,
            callId: String,
        ) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(databaseName)
        var firstDatabase: XiaoLingDatabase? = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        var restartedDatabase: XiaoLingDatabase? = null
        try {
            val opened = checkNotNull(firstDatabase)
            val firstRepository = RoomAgentRunRepository(context, opened)
            val firstRegistry = XiaoLingToolRegistry(
                clock = SystemAgentClock(),
                conversationStore = RoomAgentConversationStore(context, opened),
                noteStore = RoomAgentNoteStore(context, opened),
                memoryStore = RoomAgentMemoryStore(context, opened),
                knowledgeStore = RoomKnowledgeDocumentStore(context, opened),
            )
            val definition = checkNotNull(firstRegistry.definition("notes.create"))
            val recoveryContract = ToolDefinitionRecoveryContract.snapshot(definition)
            val run = firstRepository.createRun(
                conversationId = "conversation-not-committed-replay",
                userMessageId = "message-not-committed-replay",
                goal = "创建尚未执行的受控重放资格样本",
            )
            val call = ToolCall(
                id = "tool-call-not-committed-replay",
                name = definition.name,
                arguments = mapOf("title" to "恢复资格", "content" to "工具尚未执行"),
                risk = definition.risk,
            )
            val profile = AgentProfileSnapshot(
                id = "agent-notes-recovery",
                name = "笔记恢复 Agent",
                avatar = "记",
                providerId = "provider-not-committed-replay",
                model = "gpt-test",
                apiMode = com.longdev.xiaoling.model.ApiMode.RESPONSES,
                systemPrompt = "",
                contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                allowedToolNames = listOf(definition.name),
                allowedSkillIds = emptyList(),
                memoryEnabled = false,
            )
            firstRepository.appendEvent(
                run.id,
                com.longdev.xiaoling.agent.AgentEventTypes.PROFILE_SELECTED,
                "冻结 Agent Profile",
                RunEventMetadata.AgentProfileSelection(profile),
            )
            val callMetadata = RunEventMetadata.ToolCall(
                id = call.id,
                toolName = call.name,
                risk = call.risk,
                arguments = call.arguments,
                recoveryContract = recoveryContract,
            )
            firstRepository.appendEvent(run.id, "tool.call.proposed", "模型提出工具调用", callMetadata)
            firstRepository.appendEvent(run.id, "tool.call.validated", "工具调用通过校验", callMetadata)
            val approvalStep = firstRepository.appendStep(
                run.id,
                "approval",
                "应用侧审批",
                "等待批准同一 ToolCall",
                AgentStepStatus.RUNNING,
            )
            val approval = firstRepository.createApprovalRequest(
                conversationId = run.conversationId,
                runId = run.id,
                toolCall = call,
                definition = definition,
            )
            checkNotNull(
                firstRepository.decideApprovalRequest(
                    approval.id,
                    ApprovalRequestStatus.APPROVED,
                    "用户批准",
                ),
            )
            firstRepository.updateStep(approvalStep.id, AgentStepStatus.COMPLETED, "用户已批准")
            firstRepository.updateRunStatus(run.id, AgentRunStatus.EXECUTING)

            // long: 资格必须在数据库完全关闭后仍可重建，证明它只依赖持久化 Profile、Tool Ledger、审批和定义指纹，不依赖旧进程对象。
            opened.close()
            firstDatabase = null
            val reopened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, databaseName)
                .addMigrations(*XiaoLingDatabase.migrations())
                .allowMainThreadQueries()
                .build()
                .also { restartedDatabase = it }
            val restartedRepository = RoomAgentRunRepository(context, reopened)
            val restartedRegistry = XiaoLingToolRegistry(
                clock = SystemAgentClock(),
                conversationStore = RoomAgentConversationStore(context, reopened),
                noteStore = RoomAgentNoteStore(context, reopened),
                memoryStore = RoomAgentMemoryStore(context, reopened),
                knowledgeStore = RoomKnowledgeDocumentStore(context, reopened),
            )
            verify(restartedRepository, restartedRegistry, run.id, call.id)
        } finally {
            firstDatabase?.close()
            restartedDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }
}
