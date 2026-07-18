package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentLlm
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.ApprovalDecision
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.FakeToolRegistry
import com.longdev.xiaoling.agent.MinimalAgentRuntime
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolExecutionResult
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.data.ApprovalRequestEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val metadata = repository.snapshot(run.id).events.single().metadata as RunEventMetadata.ToolResult
        assertEquals(listOf("memory-1", "memory-2"), metadata.memoryIdsUsed)
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
        assertTrue(detail.snapshot.steps.none { it.type == "llm.plan" })
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
