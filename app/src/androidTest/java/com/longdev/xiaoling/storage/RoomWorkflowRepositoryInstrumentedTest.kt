package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentStepTypes
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedAgentContextCodec
import com.longdev.xiaoling.agent.VerifiedToolExecution
import com.longdev.xiaoling.automation.ScheduledWorkflowReentryCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowProcessExecutionRegistry
import com.longdev.xiaoling.automation.StartupRecoveryCandidateIds
import com.longdev.xiaoling.automation.StartupRecoveryCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopFallbackCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopOutcome
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.MessageOrigin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomWorkflowRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomWorkflowRepository(context, database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun manualRunKeepsSingleIdempotentAgentStepAndCompletes() = runBlocking {
        val workflow = repository.createWorkflow("每日回顾", "回顾最近会话")
        val created = repository.createManualRun(workflow.id, "conversation-1")

        assertEquals(WorkflowRunStatus.QUEUED, created.run.status)
        assertEquals(WorkflowStepStatus.PENDING, created.steps.single().status)
        assertNull(created.run.agentRunId)
        val duplicateError = runCatching {
            repository.createManualRun(workflow.id, "conversation-1")
        }.exceptionOrNull()
        assertTrue(duplicateError is IllegalArgumentException)

        repository.markAgentRunStarted(created.run.id, "agent-run-1")
        repository.markAgentRunStarted(created.run.id, "agent-run-1")
        repository.completeRun(created.run.id, WorkflowRunStatus.COMPLETED, result = "回顾完成")

        val completed = repository.recentRunDetails().single()
        assertEquals(WorkflowRunStatus.COMPLETED, completed.run.status)
        assertEquals("agent-run-1", completed.run.agentRunId)
        assertEquals(1, completed.steps.size)
        assertEquals(WorkflowStepStatus.COMPLETED, completed.steps.single().status)
        assertEquals("回顾完成", completed.steps.single().result)
    }

    @Test
    fun scheduledAgentResultPersistsTextAndToolPartsTogether() = runBlocking {
        database.conversationDao().insertConversations(
            listOf(
                ConversationEntity(
                    id = "conversation-parts",
                    title = "后台 Agent",
                    summary = "",
                    summaryUntilMessageId = null,
                    summaryUpdatedAt = null,
                    summaryModel = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        val context = VerifiedAgentContext(
            runId = "run-parts",
            toolName = "app.current_time",
            arguments = emptyMap(),
            success = true,
            verificationStatus = AgentVerificationStatus.READABLE_ONLY,
            rawResult = "当前时间：12:04",
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "app.current_time",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "当前时间：12:04",
                ),
            ),
        )

        repository.appendScheduledConversationResult(
            conversationId = "conversation-parts",
            text = "Agent 任务已完成",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = VerifiedAgentContextCodec.encode(context),
        )

        val persisted = database.conversationDao().getAllMessagePartsWithoutBinaryData()
        assertEquals(listOf("TEXT", "TOOL"), persisted.map { it.type })
        assertEquals(listOf(0, 1), persisted.map { it.sequence })
        assertEquals("app.current_time", persisted.last().toolName)
        assertEquals("当前时间：12:04", persisted.last().result)
    }

    @Test
    fun multiStepDefinitionMaterializesOrderedImmutableRunSnapshots() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "多步骤回顾",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("列出最近会话"),
                WorkflowStepDefinitionInput("生成最终回顾"),
            ),
        )

        assertEquals(listOf(1, 2, 3), workflow.steps.map { it.sequence })
        assertEquals(3, workflow.steps.map { it.idempotencyKey }.distinct().size)

        val run = repository.createManualRun(workflow.id, "conversation-multi")

        assertEquals(listOf(1, 2, 3), run.steps.map { it.sequence })
        assertEquals(workflow.steps.map { it.id }, run.steps.map { it.definitionStepId })
        assertEquals(workflow.steps.map { it.idempotencyKey }, run.steps.map { it.idempotencyKey })
        assertEquals(
            workflow.steps.map { it.goal },
            run.steps.map { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot).goal },
        )
    }

    @Test
    fun editingDefinitionOnlyChangesFutureRunsAndRejectsActiveRun() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "编辑前",
            steps = listOf(
                WorkflowStepDefinitionInput("旧步骤一"),
                WorkflowStepDefinitionInput("旧步骤二"),
            ),
        )
        val historicalRun = repository.createManualRun(workflow.id, "conversation-edit")
        repository.completeRun(historicalRun.run.id, WorkflowRunStatus.CANCELLED, errorMessage = "测试结束旧 Run")

        val updated = repository.updateWorkflow(
            workflowId = workflow.id,
            name = "编辑后",
            steps = listOf(
                WorkflowStepDefinitionInput("新步骤一"),
                WorkflowStepDefinitionInput("新步骤二"),
                WorkflowStepDefinitionInput("新步骤三"),
            ),
        )
        val futureRun = repository.createManualRun(workflow.id, "conversation-edit")

        assertEquals("编辑后", updated.name)
        assertEquals(listOf("旧步骤一", "旧步骤二"), repository.runDetail(historicalRun.run.id)!!.steps.map { it.detail })
        assertEquals(listOf("新步骤一", "新步骤二", "新步骤三"), futureRun.steps.map { it.detail })

        val activeEditError = runCatching {
            repository.updateWorkflow(
                workflowId = workflow.id,
                name = "不应保存",
                steps = listOf(WorkflowStepDefinitionInput("活动 Run 期间编辑")),
            )
        }.exceptionOrNull()
        assertTrue(activeEditError is IllegalArgumentException)
    }

    @Test
    fun retryReusesCompletedStepsAndKeepsSourceRunUnchanged() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "可重试工作流",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("读取最近会话"),
                WorkflowStepDefinitionInput("生成回顾"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-retry")
        val first = source.steps[0]
        val second = source.steps[1]
        repository.markAgentRunStarted(source.run.id, first.id, "agent-run-retry-1")
        repository.completeWorkflowStep(
            source.run.id,
            first.id,
            WorkflowStepStatus.COMPLETED,
            result = "时间读取完成",
        )
        repository.markAgentRunStarted(source.run.id, second.id, "agent-run-retry-2")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "第二步失败")

        val retried = repository.retryRun(source.run.id, "conversation-retry")
        val storedSource = repository.runDetail(source.run.id)!!

        assertNotEquals(source.run.id, retried.run.id)
        assertEquals(source.run.id, retried.run.retryOfWorkflowRunId)
        assertEquals(
            listOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.PENDING, WorkflowStepStatus.PENDING),
            retried.steps.map { it.status },
        )
        assertEquals(first.id, retried.steps.first().reusedFromStepId)
        assertEquals("时间读取完成", retried.steps.first().outputSnapshot)
        assertEquals(WorkflowRunStatus.FAILED, storedSource.run.status)
        assertEquals(WorkflowStepStatus.COMPLETED, storedSource.steps[0].status)
        assertEquals(WorkflowStepStatus.FAILED, storedSource.steps[1].status)
    }

    @Test
    fun workflowOnlyPassesCurrentKnowledgeOutputToNextStepAndRetry() = runBlocking {
        val knowledgeStore = RoomKnowledgeDocumentStore(context, database)
        val document = knowledgeStore.importUtf8Document(
            displayName = "workflow-rules.md",
            mimeType = "text/markdown",
            bytes = "发布验收只能使用 Redmi 真机。".toByteArray(Charsets.UTF_8),
        )
        val hit = knowledgeStore.search("Redmi 真机", limit = 3).hits.single()
        val reference = KnowledgeReference(
            retrievalId = "retrieval-workflow",
            documentId = hit.documentId,
            documentName = hit.documentName,
            documentRevision = hit.documentRevision,
            chunkId = hit.chunkId,
            chunkSequence = hit.sequence,
            startOffset = hit.startOffset,
            endOffset = hit.endOffset,
        )
        val workflow = repository.createWorkflow(
            name = "知识前序复用",
            steps = listOf(
                WorkflowStepDefinitionInput("检索发布约束"),
                WorkflowStepDefinitionInput("根据约束生成下一步"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-workflow-knowledge")
        val first = source.steps[0]
        repository.markAgentRunStarted(source.run.id, first.id, "agent-workflow-knowledge-1")
        repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "发布验收只能使用 Redmi 真机。",
            knowledgeReferences = listOf(reference),
            requiresCurrentKnowledgeReferences = true,
        )
        val second = source.steps[1]
        val prepared = repository.prepareWorkflowStep(source.run.id, second.id)
        assertEquals(
            listOf("发布验收只能使用 Redmi 真机。"),
            WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs,
        )
        repository.markAgentRunStarted(source.run.id, second.id, "agent-workflow-knowledge-2")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "测试失败")

        val sourceOutput = repository.runDetail(source.run.id)!!.steps.first().outputSnapshot
        val retried = repository.retryRun(source.run.id, "conversation-workflow-knowledge")
        knowledgeStore.setEnabled(document.id, false)

        val retriedSecond = repository.prepareWorkflowStep(retried.run.id, retried.steps[1].id)
        assertTrue(WorkflowStepSnapshotCodec.decodeInput(retriedSecond.inputSnapshot).previousOutputs.isEmpty())
        assertEquals(sourceOutput, repository.runDetail(source.run.id)!!.steps.first().outputSnapshot)
    }

    @Test
    fun completionByRecoveredAgentRunAggregatesAllStepOutputs() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "恢复后聚合",
            steps = listOf(
                WorkflowStepDefinitionInput("步骤一"),
                WorkflowStepDefinitionInput("步骤二"),
            ),
        )
        val created = repository.createManualRun(workflow.id, "conversation-aggregate")
        repository.markAgentRunStarted(created.run.id, created.steps[0].id, "agent-run-aggregate-1")
        repository.completeByAgentRunId(
            agentRunId = "agent-run-aggregate-1",
            status = WorkflowRunStatus.COMPLETED,
            result = "结果一",
        )
        repository.markAgentRunStarted(created.run.id, created.steps[1].id, "agent-run-aggregate-2")

        val completed = repository.completeByAgentRunId(
            agentRunId = "agent-run-aggregate-2",
            status = WorkflowRunStatus.COMPLETED,
            result = "结果二",
        )!!

        assertEquals(WorkflowRunStatus.COMPLETED, completed.status)
        assertEquals("结果一\n\n结果二", completed.result)
    }

    @Test
    fun reconcileKeepsApprovalWaitButClosesCancelledAgentRun() = runBlocking {
        val workflow = repository.createWorkflow("需确认任务", "创建一条笔记")
        val created = repository.createManualRun(workflow.id, "conversation-2")
        repository.markAgentRunStarted(created.run.id, "agent-run-2")
        database.agentRunDao().upsertRun(agentRun("agent-run-2", AgentRunStatus.WAITING_APPROVAL))

        assertEquals(0, repository.reconcileInterruptedRuns())
        assertEquals(WorkflowRunStatus.RUNNING, repository.recentRunDetails().single().run.status)

        database.agentRunDao().upsertRun(agentRun("agent-run-2", AgentRunStatus.CANCELLED))
        assertEquals(1, repository.reconcileInterruptedRuns())
        assertEquals(WorkflowRunStatus.CANCELLED, repository.recentRunDetails().single().run.status)
    }

    @Test
    fun reconcileKeepsCommittedToolRecoveryCandidateRunning() = runBlocking {
        val workflow = repository.createWorkflow("恢复笔记验证", "创建并验证笔记")
        val created = repository.createManualRun(workflow.id, "conversation-tool-recovery")
        val agentRunId = "agent-run-tool-recovery"
        repository.markAgentRunStarted(created.run.id, agentRunId)
        database.agentRunDao().upsertRun(agentRun(agentRunId, AgentRunStatus.VERIFYING))

        assertEquals(0, repository.reconcileInterruptedRuns(setOf(agentRunId)))

        val preserved = repository.runDetail(created.run.id)!!
        assertEquals(WorkflowRunStatus.RUNNING, preserved.run.status)
        assertEquals(WorkflowStepStatus.RUNNING, preserved.steps.single().status)
        assertEquals(agentRunId, preserved.run.agentRunId)
    }

    @Test
    fun reconcileKeepsCompletedPrefixAndClosesInterruptedMultiStepRun() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "中断后可重试",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("生成回顾"),
            ),
        )
        val created = repository.createManualRun(workflow.id, "conversation-reconcile")
        val firstStep = created.steps.first()
        repository.markAgentRunStarted(created.run.id, firstStep.id, "agent-run-completed-prefix")
        database.agentRunDao().upsertRun(
            agentRun(
                id = "agent-run-completed-prefix",
                status = AgentRunStatus.COMPLETED,
                result = "当前时间为 09:30",
            ),
        )

        // long: 模拟进程恰好在步骤结果事务提交后被回收；Workflow Run 和下一步骤仍保持活动中，等待启动对账。
        repository.completeWorkflowStep(
            workflowRunId = created.run.id,
            workflowStepId = firstStep.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "当前时间为 09:30",
        )
        val persistedBeforeRestart = repository.runDetail(created.run.id)!!
        assertEquals(WorkflowRunStatus.RUNNING, persistedBeforeRestart.run.status)
        assertEquals(WorkflowStepStatus.COMPLETED, persistedBeforeRestart.steps[0].status)
        assertEquals(WorkflowStepStatus.PENDING, persistedBeforeRestart.steps[1].status)

        assertEquals(1, repository.reconcileInterruptedRuns())

        val interrupted = repository.runDetail(created.run.id)!!
        assertEquals(WorkflowRunStatus.FAILED, interrupted.run.status)
        assertEquals(WorkflowStepStatus.COMPLETED, interrupted.steps[0].status)
        assertEquals("当前时间为 09:30", interrupted.steps[0].outputSnapshot)
        assertEquals(WorkflowStepStatus.CANCELLED, interrupted.steps[1].status)

        val retried = repository.retryRun(created.run.id, "conversation-reconcile")
        assertEquals(WorkflowStepStatus.SKIPPED, retried.steps[0].status)
        assertEquals(firstStep.id, retried.steps[0].reusedFromStepId)
        assertEquals(WorkflowStepStatus.PENDING, retried.steps[1].status)
    }

    @Test
    fun oneTimeTaskCreatesClaimLinksRunAndSettlesBlocked() = runBlocking {
        val workflow = repository.createWorkflow("定时笔记", "创建一条笔记")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-1")

        val claim = repository.claimScheduledRun(task.id)!!
        repository.markAgentRunStarted(claim.run.run.id, "agent-run-scheduled-1")
        repository.completeRun(claim.run.run.id, WorkflowRunStatus.BLOCKED, errorMessage = "需要用户确认")
        repository.finishScheduledTask(task.id, ScheduledTaskStatus.BLOCKED, "需要用户确认")

        val storedTask = repository.listScheduledTasks().single()
        val storedRun = repository.recentRunDetails().single()
        assertEquals(ScheduledTaskStatus.BLOCKED, storedTask.status)
        assertEquals(task.plannedAt, storedRun.run.plannedAt)
        assertEquals(task.id, storedRun.run.scheduledTaskId)
        assertEquals(storedRun.run.id, storedTask.workflowRunId)
        assertTrue(storedTask.actualStartedAt != null)
        assertEquals("agent-run-scheduled-1", storedRun.run.agentRunId)
        assertEquals(WorkflowRunStatus.BLOCKED, storedRun.run.status)
        assertEquals(WorkflowStepStatus.BLOCKED, storedRun.steps.single().status)
    }

    @Test
    fun workerReentryClosesOnlyLinkedAgentAndScheduledTaskWithoutCreatingNewRun() = runBlocking {
        val workflow = repository.createWorkflow("重入对账", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-reentry")
        val claim = repository.claimScheduledRun(task.id)!!
        val agentRepository = RoomAgentRunRepository(context, database)
        val linkedAgent = agentRepository.createRun(
            conversationId = claim.run.run.conversationId,
            userMessageId = claim.userMessageId,
            goal = "读取当前时间",
        )
        agentRepository.updateRunStatus(linkedAgent.id, AgentRunStatus.EXECUTING)
        agentRepository.appendStep(
            runId = linkedAgent.id,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行读取",
            detail = "系统回收前仍在执行",
            status = AgentStepStatus.RUNNING,
        )
        repository.markAgentRunStarted(claim.run.run.id, claim.run.steps.single().id, linkedAgent.id)

        val unrelatedAgent = agentRepository.createRun(
            conversationId = "conversation-unrelated",
            userMessageId = "message-unrelated",
            goal = "前台任务不应被 Worker 重入关闭",
        )
        agentRepository.updateRunStatus(unrelatedAgent.id, AgentRunStatus.THINKING)
        val runCountBefore = agentRepository.recentRunDetails(limit = 20).size
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowReentryCoordinator(
            loadTask = repository::getScheduledTask,
            loadWorkflowRun = repository::runDetail,
            closeAgentRun = { runId ->
                events += "close-agent:$runId"
                agentRepository.closeInterruptedRuns(
                    runIds = setOf(runId),
                    preserveResumableCandidates = false,
                ) > 0
            },
            reconcileWorkflowRun = { workflowRunId ->
                events += "reconcile-workflow:$workflowRunId"
                repository.reconcileInterruptedRuns(workflowRunIds = setOf(workflowRunId)) > 0
            },
            reconcileScheduledTask = { taskId ->
                events += "reconcile-task:$taskId"
                repository.reconcileInterruptedScheduledTasks(taskIds = setOf(taskId)) > 0
            },
        )

        assertTrue(coordinator.reconcile(task.id))

        assertEquals(
            listOf(
                "close-agent:${linkedAgent.id}",
                "reconcile-workflow:${claim.run.run.id}",
                "reconcile-task:${task.id}",
            ),
            events,
        )
        assertEquals(AgentRunStatus.CANCELLED, agentRepository.runDetail(linkedAgent.id)!!.snapshot.run.status)
        assertEquals(AgentRunStatus.THINKING, agentRepository.runDetail(unrelatedAgent.id)!!.snapshot.run.status)
        assertEquals(WorkflowRunStatus.CANCELLED, repository.runDetail(claim.run.run.id)!!.run.status)
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(task.id)!!.status)
        assertEquals(runCountBefore, agentRepository.recentRunDetails(limit = 20).size)
    }

    @Test
    fun userStopFallbackCancelsOnlyLinkedRunningChainWithoutCreatingNewRun() = runBlocking {
        val workflow = repository.createWorkflow("停止后台任务", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-user-stop")
        val claim = repository.claimScheduledRun(task.id)!!
        val agentRepository = RoomAgentRunRepository(context, database)
        val linkedAgent = agentRepository.createRun(
            conversationId = claim.run.run.conversationId,
            userMessageId = claim.userMessageId,
            goal = "读取当前时间",
        )
        agentRepository.updateRunStatus(linkedAgent.id, AgentRunStatus.THINKING)
        agentRepository.appendStep(
            runId = linkedAgent.id,
            type = AgentStepTypes.LLM_PLAN,
            title = "模型决策",
            detail = "等待用户停止",
            status = AgentStepStatus.RUNNING,
        )
        repository.markAgentRunStarted(claim.run.run.id, claim.run.steps.single().id, linkedAgent.id)
        val unrelatedAgent = agentRepository.createRun(
            conversationId = "conversation-user-stop-unrelated",
            userMessageId = "message-user-stop-unrelated",
            goal = "无关前台任务",
        )
        agentRepository.updateRunStatus(unrelatedAgent.id, AgentRunStatus.THINKING)
        val runCountBefore = agentRepository.recentRunDetails(limit = 20).size
        val systemCancellations = mutableListOf<String>()
        val fallback = ScheduledWorkflowStopFallbackCoordinator(
            loadTask = repository::getScheduledTask,
            loadWorkflowRun = repository::runDetail,
            cancelAgentRun = { runId -> agentRepository.cancelActiveRun(runId, "用户停止后台工作流") },
            cancelWorkflowRun = { workflowRunId, reason ->
                repository.completeRun(workflowRunId, WorkflowRunStatus.CANCELLED, errorMessage = reason)
            },
            cancelScheduledTask = { taskId, reason ->
                repository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
            },
        )
        val stopCoordinator = ScheduledWorkflowStopCoordinator(
            loadTask = repository::getScheduledTask,
            cancelPendingTask = repository::cancelScheduledTask,
            requestRunningStop = { taskId ->
                repository.requestScheduledTaskStop(taskId, "用户停止后台工作流")
            },
            cancelSystemWork = { taskId -> systemCancellations += taskId },
            waitForWorkerSettlement = {},
            reconcileUnsettledTask = fallback::reconcile,
            settlementChecks = 1,
        )

        val stopped = stopCoordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOPPED, stopped.outcome)
        assertEquals(listOf(task.id), systemCancellations)
        assertEquals(AgentRunStatus.CANCELLED, agentRepository.runDetail(linkedAgent.id)!!.snapshot.run.status)
        assertEquals(AgentRunStatus.THINKING, agentRepository.runDetail(unrelatedAgent.id)!!.snapshot.run.status)
        assertEquals(WorkflowRunStatus.CANCELLED, repository.runDetail(claim.run.run.id)!!.run.status)
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(task.id)!!.status)
        assertEquals(runCountBefore, agentRepository.recentRunDetails(limit = 20).size)
    }

    @Test
    fun userStopFallbackCancelsClaimedWorkflowBeforeAgentRunIsLinked() = runBlocking {
        val workflow = repository.createWorkflow("停止未关联任务", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-user-stop-no-agent")
        val claim = repository.claimScheduledRun(task.id)!!
        val systemCancellations = mutableListOf<String>()
        val fallback = ScheduledWorkflowStopFallbackCoordinator(
            loadTask = repository::getScheduledTask,
            loadWorkflowRun = repository::runDetail,
            cancelAgentRun = { error("不应取消尚未关联的 Agent Run") },
            cancelWorkflowRun = { workflowRunId, reason ->
                repository.completeRun(workflowRunId, WorkflowRunStatus.CANCELLED, errorMessage = reason)
            },
            cancelScheduledTask = { taskId, reason ->
                repository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
            },
        )
        val stopCoordinator = ScheduledWorkflowStopCoordinator(
            loadTask = repository::getScheduledTask,
            cancelPendingTask = repository::cancelScheduledTask,
            requestRunningStop = { taskId ->
                repository.requestScheduledTaskStop(taskId, "用户停止后台工作流")
            },
            cancelSystemWork = { taskId -> systemCancellations += taskId },
            waitForWorkerSettlement = {},
            reconcileUnsettledTask = fallback::reconcile,
            settlementChecks = 1,
        )

        val stopped = stopCoordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOPPED, stopped.outcome)
        assertEquals(listOf(task.id), systemCancellations)
        assertNull(repository.runDetail(claim.run.run.id)!!.run.agentRunId)
        assertEquals(WorkflowRunStatus.CANCELLED, repository.runDetail(claim.run.run.id)!!.run.status)
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(task.id)!!.status)
    }

    @Test
    fun startupRecoveryClosesOldChainButKeepsCurrentProcessWorkerChain() = runBlocking {
        val agentRepository = RoomAgentRunRepository(context, database)
        val oldWorkflow = repository.createWorkflow("旧进程任务", "读取旧状态")
        val oldTask = repository.createOneTimeScheduledTask(oldWorkflow.id, delayMinutes = 1)
        repository.attachWorkRequest(oldTask.id, "work-request-old-process")
        val oldClaim = repository.claimScheduledRun(oldTask.id)!!
        val oldAgent = agentRepository.createRun(
            conversationId = oldClaim.run.run.conversationId,
            userMessageId = oldClaim.userMessageId,
            goal = "读取旧状态",
        )
        agentRepository.updateRunStatus(oldAgent.id, AgentRunStatus.THINKING)
        repository.markAgentRunStarted(oldClaim.run.run.id, oldClaim.run.steps.single().id, oldAgent.id)

        val currentWorkflow = repository.createWorkflow("当前进程任务", "读取当前状态")
        val currentTask = repository.createOneTimeScheduledTask(currentWorkflow.id, delayMinutes = 1)
        repository.attachWorkRequest(currentTask.id, "work-request-current-process")
        val currentClaim = repository.claimScheduledRun(currentTask.id)!!
        val currentAgent = agentRepository.createRun(
            conversationId = currentClaim.run.run.conversationId,
            userMessageId = currentClaim.userMessageId,
            goal = "读取当前状态",
        )
        agentRepository.updateRunStatus(currentAgent.id, AgentRunStatus.THINKING)
        repository.markAgentRunStarted(currentClaim.run.run.id, currentClaim.run.steps.single().id, currentAgent.id)
        val runCountBeforeRecovery = agentRepository.recentRunDetails(limit = 20).size
        val registry = ScheduledWorkflowProcessExecutionRegistry()

        registry.withScheduledTask(currentTask.id) {
            val candidates = StartupRecoveryCoordinator(
                processExecutionRegistry = registry,
                loadAgentRunIds = agentRepository::activeRunIds,
                loadWorkflowCandidates = repository::startupRecoveryCandidates,
            ).capture()
            assertEquals(
                StartupRecoveryCandidateIds(
                    agentRunIds = setOf(oldAgent.id),
                    workflowRunIds = setOf(oldClaim.run.run.id),
                    scheduledTaskIds = setOf(oldTask.id),
                ),
                candidates,
            )

            agentRepository.closeInterruptedRuns(
                runIds = candidates.agentRunIds,
                preserveResumableCandidates = false,
            )
            repository.reconcileInterruptedRuns(workflowRunIds = candidates.workflowRunIds)
            repository.reconcileInterruptedScheduledTasks(taskIds = candidates.scheduledTaskIds)

            assertEquals(AgentRunStatus.CANCELLED, agentRepository.runDetail(oldAgent.id)!!.snapshot.run.status)
            assertEquals(WorkflowRunStatus.CANCELLED, repository.runDetail(oldClaim.run.run.id)!!.run.status)
            assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(oldTask.id)!!.status)
            assertEquals(AgentRunStatus.THINKING, agentRepository.runDetail(currentAgent.id)!!.snapshot.run.status)
            assertEquals(WorkflowRunStatus.RUNNING, repository.runDetail(currentClaim.run.run.id)!!.run.status)
            assertEquals(ScheduledTaskStatus.RUNNING, repository.getScheduledTask(currentTask.id)!!.status)
            assertEquals(runCountBeforeRecovery, agentRepository.recentRunDetails(limit = 20).size)

            agentRepository.updateRunStatus(currentAgent.id, AgentRunStatus.COMPLETED, result = "当前状态可用")
            repository.completeByAgentRunId(
                agentRunId = currentAgent.id,
                status = WorkflowRunStatus.COMPLETED,
                result = "当前状态可用",
            )
            repository.finishScheduledTask(currentTask.id, ScheduledTaskStatus.COMPLETED)

            assertEquals(AgentRunStatus.COMPLETED, agentRepository.runDetail(currentAgent.id)!!.snapshot.run.status)
            assertEquals(WorkflowRunStatus.COMPLETED, repository.runDetail(currentClaim.run.run.id)!!.run.status)
            assertEquals(ScheduledTaskStatus.COMPLETED, repository.getScheduledTask(currentTask.id)!!.status)
            assertEquals(runCountBeforeRecovery, agentRepository.recentRunDetails(limit = 20).size)
        }
    }

    @Test
    fun persistedStopRequestOverridesCurrentProcessOwnershipAndReconcilesOnStartup() = runBlocking {
        val workflow = repository.createWorkflow("持久化停止请求", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-persisted-stop")
        val claim = repository.claimScheduledRun(task.id)!!
        val agentRepository = RoomAgentRunRepository(context, database)
        val agent = agentRepository.createRun(
            conversationId = claim.run.run.conversationId,
            userMessageId = claim.userMessageId,
            goal = "读取当前时间",
        )
        agentRepository.updateRunStatus(agent.id, AgentRunStatus.THINKING)
        repository.markAgentRunStarted(claim.run.run.id, claim.run.steps.single().id, agent.id)
        val runCountBeforeRecovery = agentRepository.recentRunDetails(limit = 20).size
        val requested = repository.requestScheduledTaskStop(task.id, "用户请求停止后台工作流")!!
        assertEquals(ScheduledTaskStatus.STOP_REQUESTED, requested.status)

        val registry = ScheduledWorkflowProcessExecutionRegistry()
        registry.withScheduledTask(task.id) {
            val candidates = StartupRecoveryCoordinator(
                processExecutionRegistry = registry,
                loadAgentRunIds = agentRepository::activeRunIds,
                loadWorkflowCandidates = repository::startupRecoveryCandidates,
            ).capture()

            // long: STOP_REQUESTED 已撤销当前 Worker 的继续执行资格；即使进程注册表仍有旧所有权，启动恢复也必须处理同一条持久化链。
            assertEquals(setOf(agent.id), candidates.agentRunIds)
            assertEquals(setOf(claim.run.run.id), candidates.workflowRunIds)
            assertEquals(setOf(task.id), candidates.scheduledTaskIds)

            agentRepository.closeInterruptedRuns(
                runIds = candidates.agentRunIds,
                preserveResumableCandidates = false,
            )
            repository.reconcileInterruptedRuns(workflowRunIds = candidates.workflowRunIds)
            repository.reconcileInterruptedScheduledTasks(taskIds = candidates.scheduledTaskIds)
        }

        assertEquals(AgentRunStatus.CANCELLED, agentRepository.runDetail(agent.id)!!.snapshot.run.status)
        assertEquals(WorkflowRunStatus.CANCELLED, repository.runDetail(claim.run.run.id)!!.run.status)
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(task.id)!!.status)
        assertEquals(runCountBeforeRecovery, agentRepository.recentRunDetails(limit = 20).size)
    }

    @Test
    fun lateWorkerCompletionCannotOverwritePersistedStopRequest() = runBlocking {
        val workflow = repository.createWorkflow("迟到结算保护", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-late-stop")
        val claim = repository.claimScheduledRun(task.id)!!

        val requested = repository.requestScheduledTaskStop(task.id, "用户请求停止后台工作流")!!
        assertEquals(ScheduledTaskStatus.STOP_REQUESTED, requested.status)

        val settled = repository.settleScheduledWorkflowRun(
            taskId = task.id,
            workflowRunId = claim.run.run.id,
            workflowStatus = WorkflowRunStatus.COMPLETED,
            taskStatus = ScheduledTaskStatus.COMPLETED,
            result = "迟到成功结果",
        )!!

        assertEquals(ScheduledTaskStatus.CANCELLED, settled.status)
        assertEquals("用户请求停止后台工作流", settled.errorMessage)
        assertEquals(settled, repository.getScheduledTask(task.id))
        val workflowRun = repository.runDetail(claim.run.run.id)!!.run
        assertEquals(WorkflowRunStatus.CANCELLED, workflowRun.status)
        assertNull(workflowRun.result)
        assertEquals("用户请求停止后台工作流", workflowRun.errorMessage)
    }

    @Test
    fun stopRequestedRecurringTaskDoesNotMaterializeNextOccurrenceBeforeReconciliation() = runBlocking {
        val workflow = repository.createWorkflow("停止周期实例", "读取当前时间")
        val plan = repository.createOrReplaceWorkflowSchedule(
            workflowId = workflow.id,
            type = WorkflowScheduleType.DAILY,
            hour = 9,
            minute = 30,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
        )
        repository.attachWorkRequest(plan.task.id, "work-request-stop-recurring")
        repository.claimScheduledRun(plan.task.id)!!
        repository.requestScheduledTaskStop(plan.task.id, "用户请求停止后台工作流")

        // long: STOP_REQUESTED 仍是中间态；旧 Worker 的 finally 和启动规则对账都不能提前创建下一实例，避免当前链尚未关闭就并行执行下一周期。
        assertNull(repository.materializeNextOccurrence(plan.task.id))
        assertTrue(repository.reconcileWorkflowSchedules().isEmpty())
        assertEquals(plan.task.id, repository.listWorkflowSchedules().single().nextTaskId)

        repository.finishScheduledTask(plan.task.id, ScheduledTaskStatus.CANCELLED, "用户请求停止后台工作流")
        val next = repository.materializeNextOccurrence(plan.task.id)!!
        assertTrue(next.plannedAt > plan.task.plannedAt)
        assertEquals(next.id, repository.listWorkflowSchedules().single().nextTaskId)
    }

    @Test
    fun recurringScheduleReplacesPendingTaskAndMaterializesOnlyOneNextOccurrence() = runBlocking {
        val workflow = repository.createWorkflow("周期回顾", "读取当前时间")
        val daily = repository.createOrReplaceWorkflowSchedule(
            workflow.id,
            WorkflowScheduleType.DAILY,
            hour = 9,
            minute = 30,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
        )
        val weekly = repository.createOrReplaceWorkflowSchedule(
            workflow.id,
            WorkflowScheduleType.WEEKLY,
            hour = 10,
            minute = 0,
            dayOfWeek = 1,
            zoneId = "Asia/Shanghai",
        )

        assertEquals(daily.schedule.id, weekly.schedule.id)
        assertEquals(daily.task.id, weekly.replacedTaskId)
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(daily.task.id)?.status)
        assertEquals(WorkflowScheduleType.WEEKLY, repository.listWorkflowSchedules().single().type)

        repository.finishScheduledTask(weekly.task.id, ScheduledTaskStatus.COMPLETED)
        val next = repository.materializeNextOccurrence(weekly.task.id)!!
        assertEquals(weekly.schedule.id, next.scheduleId)
        assertTrue(next.plannedAt > weekly.task.plannedAt)
        assertNull(repository.materializeNextOccurrence(weekly.task.id))
        assertEquals(next.id, repository.listWorkflowSchedules().single().nextTaskId)
    }

    @Test
    fun interruptedRecurringTaskSettlesFromWorkflowRunBeforeCreatingFutureOccurrence() = runBlocking {
        val workflow = repository.createWorkflow("中断周期", "读取当前时间")
        val plan = repository.createOrReplaceWorkflowSchedule(
            workflow.id,
            WorkflowScheduleType.DAILY,
            hour = 9,
            minute = 0,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
        )
        val claim = repository.claimScheduledRun(plan.task.id)!!
        repository.completeRun(claim.run.run.id, WorkflowRunStatus.FAILED, errorMessage = "应用重启后取消旧执行")

        assertEquals(1, repository.reconcileInterruptedScheduledTasks())
        assertEquals(ScheduledTaskStatus.FAILED, repository.getScheduledTask(plan.task.id)?.status)
        val recovered = repository.reconcileWorkflowSchedules().single()
        assertEquals(plan.schedule.id, recovered.scheduleId)
        assertTrue(recovered.plannedAt > plan.task.plannedAt)
    }

    private fun agentRun(
        id: String,
        status: AgentRunStatus,
        result: String? = null,
    ) = AgentRunEntity(
        id = id,
        retryOfRunId = null,
        conversationId = "conversation-2",
        userMessageId = "message-2",
        goal = "创建一条笔记",
        status = status.name,
        result = result,
        errorMessage = if (status == AgentRunStatus.CANCELLED) "应用重启后取消" else null,
        createdAt = 1L,
        updatedAt = 2L,
        completedAt = if (status == AgentRunStatus.CANCELLED) 2L else null,
    )
}
