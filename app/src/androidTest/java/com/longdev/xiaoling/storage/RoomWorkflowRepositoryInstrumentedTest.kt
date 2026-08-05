package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentStepTypes
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.PersonalTaskPlanContextPreparer
import com.longdev.xiaoling.agent.PersonalTaskPlanPolicy
import com.longdev.xiaoling.agent.PersonalTaskScheduleType
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
import com.longdev.xiaoling.automation.WorkflowGoalVerificationContract
import com.longdev.xiaoling.automation.WorkflowGoalVerificationSpec
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import com.longdev.xiaoling.automation.WorkflowDeviceActionEvidenceException
import com.longdev.xiaoling.automation.WorkflowDeviceActionInsufficientReason
import com.longdev.xiaoling.automation.WorkflowDeviceObservationDecisionStatus
import com.longdev.xiaoling.automation.WorkflowDeviceObservationEvidenceException
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.AgentToolCallEntity
import com.longdev.xiaoling.data.AgentToolResultEntity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.ui.workflow.WorkflowDeviceActionUiOutcome
import com.longdev.xiaoling.ui.workflow.WorkflowManagementProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(repository.isWorkflowAgentRun("agent-run-1"))
        val duplicateError = runCatching {
            repository.createManualRun(workflow.id, "conversation-1")
        }.exceptionOrNull()
        assertTrue(duplicateError is IllegalArgumentException)

        repository.markAgentRunStarted(created.run.id, "agent-run-1")
        repository.markAgentRunStarted(created.run.id, "agent-run-1")
        assertTrue(repository.isWorkflowAgentRun("agent-run-1"))
        repository.completeRun(created.run.id, WorkflowRunStatus.COMPLETED, result = "回顾完成")

        val completed = repository.recentRunDetails().single()
        assertEquals(WorkflowRunStatus.COMPLETED, completed.run.status)
        assertEquals("agent-run-1", completed.run.agentRunId)
        assertEquals(1, completed.steps.size)
        assertEquals(WorkflowStepStatus.COMPLETED, completed.steps.single().status)
        assertEquals("回顾完成", completed.steps.single().result)
        assertNull(completed.run.goalVerificationDecision)
    }

    @Test
    fun confirmedPersonalTaskCreatesDefinitionAndManualRunTogether() = runBlocking {
        val verificationContract = WorkflowGoalVerificationContract(
            sourceGoal = "打开系统设置并查看当前页面",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf("device.open_app", "device.snapshot"),
                expectedFinalPackageName = "com.android.settings",
            ),
        )
        val (workflow, run) = repository.createWorkflowAndManualRun(
            name = "记录当前时间",
            steps = listOf(
                WorkflowStepDefinitionInput("打开系统设置"),
                WorkflowStepDefinitionInput("查看当前页面"),
            ),
            conversationId = "conversation-personal-task",
            targetAppPackage = "com.android.settings",
            goalVerificationContract = verificationContract,
        )

        assertEquals(workflow.id, run.run.workflowId)
        assertEquals(WorkflowRunStatus.QUEUED, run.run.status)
        assertEquals("conversation-personal-task", run.run.conversationId)
        assertEquals("com.android.settings", workflow.targetAppPackage)
        assertEquals(verificationContract, workflow.goalVerificationContract)
        assertEquals(listOf("打开系统设置", "查看当前页面"), run.steps.map { it.detail })
        assertEquals(
            listOf("com.android.settings", "com.android.settings"),
            run.steps.map { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot).targetAppPackage },
        )
        assertEquals(
            listOf(verificationContract, verificationContract),
            run.steps.map { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot).goalVerificationContract },
        )
        assertNull(run.run.goalVerificationDecision)
        assertEquals(listOf(workflow.id), repository.listWorkflows().map { it.id })
        assertEquals(listOf(run.run.id), repository.recentRunDetails().map { it.run.id })
    }

    @Test
    fun confirmedPersonalRemindersCreateWorkflowAndScheduleWithoutManualRun() = runBlocking {
        val contract = WorkflowGoalVerificationContract(
            sourceGoal = "30 分钟后提醒我喝水",
            spec = WorkflowGoalVerificationSpec(requiredToolNames = listOf("app.current_time")),
        )
        val once = repository.createWorkflowAndOneTimeScheduledTask(
            name = "喝水提醒",
            steps = listOf(WorkflowStepDefinitionInput("提醒用户喝水")),
            delayMinutes = 30,
            goalVerificationContract = contract,
        )
        val weekly = repository.createWorkflowAndRecurringSchedule(
            name = "每周复盘提醒",
            steps = listOf(WorkflowStepDefinitionInput("提醒用户进行每周复盘")),
            type = WorkflowScheduleType.WEEKLY,
            hour = 20,
            minute = 5,
            dayOfWeek = 7,
            zoneId = "Asia/Shanghai",
            goalVerificationContract = WorkflowGoalVerificationContract(
                sourceGoal = "每周日 20:05 提醒我复盘",
                spec = WorkflowGoalVerificationSpec(requiredToolNames = listOf("app.current_time")),
            ),
        )

        assertEquals(once.first.id, once.second.workflowId)
        assertEquals(ScheduledTaskStatus.SCHEDULED, once.second.status)
        assertEquals(contract, once.first.goalVerificationContract)
        assertEquals(weekly.first.id, weekly.second.task.workflowId)
        assertEquals(WorkflowScheduleType.WEEKLY, weekly.second.schedule.type)
        assertEquals(7, weekly.second.schedule.dayOfWeek)
        assertEquals("Asia/Shanghai", weekly.second.schedule.zoneId)
        assertEquals(2, repository.listWorkflows().size)
        assertEquals(2, repository.listScheduledTasks().size)
        assertTrue(repository.recentRunDetails().isEmpty())
    }

    @Test
    fun memoryAndKnowledgeContextCreatesAndEnqueuesOneTimePersonalReminder() = runBlocking {
        val conversationId = "conversation-stage132-reminder"
        val memoryStore = RoomAgentMemoryStore(context, database)
        val knowledgeStore = RoomKnowledgeDocumentStore(context, database)
        memoryStore.remember(
            content = "用户希望 30 分钟后提醒我喝温水",
            tags = "喝水 健康",
            type = "Preference",
            source = AgentMemorySource(conversationId, null, "用户确认的喝水偏好"),
            confidence = 0.95,
            idempotencyKey = "stage132-reminder-memory",
        )
        knowledgeStore.importUtf8Document(
            displayName = "工作日健康提醒.md",
            mimeType = "text/markdown",
            bytes = "30 分钟后提醒我喝温水可以使用非精确定时提醒，系统可能延迟触发。".toByteArray(),
        )
        val contextPreparer = PersonalTaskPlanContextPreparer(
            searchMemories = { query, limit ->
                memoryStore.search(query, limit, enabledOnly = true).map { it.content }
            },
            searchKnowledge = { query, limit, sourceConversationId ->
                knowledgeStore.search(query, limit, sourceConversationId).hits
            },
        )
        val goal = "30 分钟后提醒我喝温水"
        val planContext = contextPreparer.prepare(
            goal = goal,
            conversationId = conversationId,
            memoryAllowed = true,
            knowledgeAllowed = true,
        )
        assertEquals(1, planContext.memoryFacts.size)
        assertEquals(1, planContext.knowledgeSnippets.size)
        val planningPrompt = PersonalTaskPlanPolicy.requestMessages(
            goal = goal,
            allowedToolNames = listOf("app.current_time", "memory.search", "knowledge.search"),
            context = planContext,
        ).last().content
        assertTrue(planningPrompt.contains("用户希望 30 分钟后提醒我喝温水"))
        assertTrue(planningPrompt.contains("工作日健康提醒.md"))

        val plan = PersonalTaskPlanPolicy.parse(
            raw = """
                {
                  "name":"喝水提醒",
                  "target_app_package":"",
                  "schedule":{"type":"ONCE","delay_minutes":30,"hour":0,"minute":0,"day_of_week":0},
                  "verification":{"required_tool_names":["app.current_time"],"expected_final_package":""},
                  "steps":[{"goal":"在提醒触发时告知用户喝温水"}]
                }
            """.trimIndent(),
            allowedToolNames = setOf("app.current_time", "memory.search", "knowledge.search"),
        )
        assertEquals(PersonalTaskScheduleType.ONCE, plan.schedule.type)
        val contract = WorkflowGoalVerificationContract(sourceGoal = goal, spec = plan.verification)
        val (workflow, task) = repository.createWorkflowAndOneTimeScheduledTask(
            name = plan.name,
            steps = plan.steps.map { WorkflowStepDefinitionInput(it.goal) },
            delayMinutes = plan.schedule.delayMinutes,
            targetAppPackage = plan.targetAppPackage,
            goalVerificationContract = contract,
        )
        val scheduler = WorkManagerScheduledTaskScheduler(context)
        try {
            val workRequestId = scheduler.enqueue(task)
            repository.attachWorkRequest(task.id, workRequestId)
            val storedTask = repository.getScheduledTask(task.id)
            assertEquals(ScheduledTaskStatus.SCHEDULED, storedTask?.status)
            assertEquals(workRequestId, storedTask?.workRequestId)
            assertEquals(contract, workflow.goalVerificationContract)
            assertTrue(repository.recentRunDetails().isEmpty())
        } finally {
            // long: 里程碑验收只证明真实 WorkManager 入队，不让 30 分钟后的测试提醒污染用户设备。
            scheduler.cancel(task.id)
        }
    }

    @Test
    fun completedPersonalTaskPersistsVerifiedGoalDecisionFromToolLedger() = runBlocking {
        val contract = WorkflowGoalVerificationContract(
            sourceGoal = "读取当前时间",
            spec = WorkflowGoalVerificationSpec(requiredToolNames = listOf("app.current_time")),
        )
        val (_, created) = repository.createWorkflowAndManualRun(
            name = "读取当前时间",
            steps = listOf(WorkflowStepDefinitionInput("读取当前时间")),
            conversationId = "conversation-goal-verification",
            goalVerificationContract = contract,
        )
        val step = created.steps.single()
        val agentRunId = "agent-run-goal-verification"
        repository.markAgentRunStarted(created.run.id, step.id, agentRunId)
        database.agentRunDao().insertToolResult(
            verifiedToolResult(agentRunId, "app.current_time"),
        )

        repository.completeWorkflowStep(
            workflowRunId = created.run.id,
            workflowStepId = step.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "当前时间：12:04",
        )
        val completed = repository.completeRun(created.run.id, WorkflowRunStatus.COMPLETED)

        assertEquals(WorkflowGoalVerificationStatus.VERIFIED, completed.goalVerificationDecision?.status)
        assertEquals(listOf("app.current_time"), completed.goalVerificationDecision?.matchedRequiredToolNames)
        assertTrue(completed.result.orEmpty().contains("任务目标已验证完成"))
        assertTrue(completed.result.orEmpty().contains("当前时间：12:04"))
    }

    @Test
    fun damagedStoredGoalContractBlocksNewRunInsteadOfBecomingLegacy() = runBlocking {
        val (workflow, initialRun) = repository.createWorkflowAndManualRun(
            name = "损坏完成标准",
            steps = listOf(WorkflowStepDefinitionInput("读取当前时间")),
            conversationId = "conversation-damaged-contract",
            goalVerificationContract = WorkflowGoalVerificationContract(
                sourceGoal = "读取当前时间",
                spec = WorkflowGoalVerificationSpec(requiredToolNames = listOf("app.current_time")),
            ),
        )
        repository.completeRun(initialRun.run.id, WorkflowRunStatus.CANCELLED, errorMessage = "准备损坏夹具")
        val stored = requireNotNull(database.workflowDao().getWorkflow(workflow.id))
        database.workflowDao().upsertWorkflow(stored.copy(goalVerificationContract = "{损坏"))

        val error = runCatching {
            repository.createManualRun(workflow.id, "conversation-damaged-contract")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("目标完成标准损坏"))
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
    fun repeatedRetryKeepsVerifiedToolProvenanceForGoalDecision() = runBlocking {
        val contract = WorkflowGoalVerificationContract(
            sourceGoal = "读取时间后生成说明",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf("app.current_time", "conversation.list"),
            ),
        )
        val (_, source) = repository.createWorkflowAndManualRun(
            name = "连续重试工具来源",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("生成时间说明"),
            ),
            conversationId = "conversation-repeated-retry",
            goalVerificationContract = contract,
        )
        val firstAgentRunId = "agent-run-repeated-retry-time"
        repository.markAgentRunStarted(source.run.id, source.steps[0].id, firstAgentRunId)
        database.agentRunDao().insertToolResult(verifiedToolResult(firstAgentRunId, "app.current_time"))
        repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = source.steps[0].id,
            status = WorkflowStepStatus.COMPLETED,
            result = "当前时间：12:04",
        )
        repository.markAgentRunStarted(source.run.id, source.steps[1].id, "agent-run-repeated-retry-failed")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "第一次失败")

        val firstRetry = repository.retryRun(source.run.id, "conversation-repeated-retry")
        repository.markAgentRunStarted(
            firstRetry.run.id,
            firstRetry.steps[1].id,
            "agent-run-repeated-retry-failed-again",
        )
        repository.completeRun(firstRetry.run.id, WorkflowRunStatus.FAILED, errorMessage = "第二次失败")

        val secondRetry = repository.retryRun(firstRetry.run.id, "conversation-repeated-retry")
        repository.markAgentRunStarted(
            secondRetry.run.id,
            secondRetry.steps[1].id,
            "agent-run-repeated-retry-completed",
        )
        database.agentRunDao().insertToolResult(
            verifiedToolResult("agent-run-repeated-retry-completed", "conversation.list"),
        )
        repository.completeWorkflowStep(
            workflowRunId = secondRetry.run.id,
            workflowStepId = secondRetry.steps[1].id,
            status = WorkflowStepStatus.COMPLETED,
            result = "时间说明已生成",
        )
        val completed = repository.completeRun(secondRetry.run.id, WorkflowRunStatus.COMPLETED)

        assertEquals(WorkflowGoalVerificationStatus.VERIFIED, completed.goalVerificationDecision?.status)
        assertEquals(
            listOf("app.current_time", "conversation.list"),
            completed.goalVerificationDecision?.matchedRequiredToolNames,
        )
        assertEquals(firstRetry.run.id, completed.retryOfWorkflowRunId)
        assertEquals(WorkflowRunStatus.FAILED, repository.runDetail(source.run.id)!!.run.status)
        assertEquals(WorkflowRunStatus.FAILED, repository.runDetail(firstRetry.run.id)!!.run.status)
    }

    @Test
    fun retryRevalidatesAllSuccessfulStepsWithoutReplayingThem() = runBlocking {
        val contract = WorkflowGoalVerificationContract(
            sourceGoal = "读取时间并列出会话",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf("app.current_time", "conversation.list"),
            ),
        )
        val (_, source) = repository.createWorkflowAndManualRun(
            name = "仅重试最终收敛",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("列出最近会话"),
            ),
            conversationId = "conversation-finalization-retry",
            goalVerificationContract = contract,
        )
        listOf(
            Triple(source.steps[0], "agent-run-finalization-time", "app.current_time"),
            Triple(source.steps[1], "agent-run-finalization-conversations", "conversation.list"),
        ).forEach { (step, agentRunId, toolName) ->
            repository.markAgentRunStarted(source.run.id, step.id, agentRunId)
            database.agentRunDao().insertToolResult(verifiedToolResult(agentRunId, toolName))
            repository.completeWorkflowStep(
                workflowRunId = source.run.id,
                workflowStepId = step.id,
                status = WorkflowStepStatus.COMPLETED,
                result = "完成 ${step.sequence}",
            )
        }
        val storedSource = requireNotNull(database.workflowDao().getRun(source.run.id))
        database.workflowDao().upsertRun(
            storedSource.copy(
                status = WorkflowRunStatus.FAILED.name,
                errorMessage = "模拟旧版本目标级收敛失败",
                completedAt = 10L,
            ),
        )

        val retry = repository.retryRun(source.run.id, "conversation-finalization-retry")
        assertEquals(listOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.SKIPPED), retry.steps.map { it.status })

        val completed = repository.completeRun(retry.run.id, WorkflowRunStatus.COMPLETED)

        assertEquals(WorkflowGoalVerificationStatus.VERIFIED, completed.goalVerificationDecision?.status)
        assertEquals(source.run.id, completed.retryOfWorkflowRunId)
        assertEquals(WorkflowRunStatus.FAILED, repository.runDetail(source.run.id)!!.run.status)
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
    fun workflowReplacesVerifiedDeviceSnapshotWithLocalDecisionForNextStepAndRetry() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "设备观察本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察当前页面"),
                WorkflowStepDefinitionInput("根据本地判定生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-decision"
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(
            deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null),
        )
        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "Agent 任务已完成\n${validDeviceSnapshot()}",
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        assertEquals(1, persistedOutput.deviceObservationDecisions.size)
        assertTrue(persistedOutput.text.contains("本地设备观察判定 1（workflow-device-observation-v1）"))
        assertFalse(persistedOutput.text.contains("snapshot-secret"))
        assertFalse(persistedOutput.text.contains("ref-secret"))
        assertFalse(completedFirst.result.orEmpty().contains("银行卡密码"))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        val previousOutput = WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single()
        assertEquals(persistedOutput.text, previousOutput)
        assertTrue(previousOutput.contains("本地设备观察判定 1（workflow-device-observation-v1）"))
        assertTrue(previousOutput.contains("com.example.notes"))
        assertFalse(previousOutput.contains("snapshot-secret"))
        assertFalse(previousOutput.contains("ref-secret"))
        assertFalse(previousOutput.contains("银行卡密码"))

        repository.markAgentRunStarted(source.run.id, source.steps[1].id, "agent-run-device-next")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "测试重试")
        val retried = repository.retryRun(source.run.id, "conversation-device-retry")
        val retriedPrepared = repository.prepareWorkflowStep(retried.run.id, retried.steps[1].id)
        assertEquals(
            previousOutput,
            WorkflowStepSnapshotCodec.decodeInput(retriedPrepared.inputSnapshot).previousOutputs.single(),
        )
    }

    @Test
    fun workflowReplacesVerifiedTapRefWithLocalActionDecisionForNextStepAndRetry() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "设备动作本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察并点击当前页面继续按钮"),
                WorkflowStepDefinitionInput("根据已验证动作生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-action-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-action-decision"
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().insertToolResult(deviceTapRefResult(agentRunId))

        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "模型转述 snapshot_id=snapshot-secret、ref=ref-secret、nodes=银行卡密码\n${validDeviceActionResult()}",
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        assertEquals(1, persistedOutput.deviceObservationDecisions.size)
        assertEquals(1, persistedOutput.deviceActionDecisions.size)
        assertEquals("tap_ref", persistedOutput.deviceActionDecisions.single().action)
        assertTrue(persistedOutput.text.contains("本地设备动作判定 1（workflow-device-action-decision-v1）"))
        assertTrue(persistedOutput.text.contains("已执行并验证 tap_ref"))
        assertFalse(persistedOutput.text.contains("snapshot-secret"))
        assertFalse(persistedOutput.text.contains("ref-secret"))
        assertFalse(persistedOutput.text.contains("银行卡密码"))

        val projectedAction = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = repository.recentRunDetails(),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.first().deviceActions.single()
        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, projectedAction.outcome)
        assertEquals("com.example.notes", projectedAction.beforePackageName)
        assertEquals("com.example.notes", projectedAction.afterPackageName)
        assertEquals(3, projectedAction.afterNodeCount)
        assertEquals(1, projectedAction.afterRedactedNodeCount)
        assertFalse(projectedAction.toString().contains("snapshot-secret"))
        assertFalse(projectedAction.toString().contains("ref-secret"))
        assertFalse(projectedAction.toString().contains("银行卡密码"))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        val previousOutput = WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single()
        assertEquals(persistedOutput.text, previousOutput)

        repository.markAgentRunStarted(source.run.id, source.steps[1].id, "agent-run-device-action-next")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "测试动作重试")
        val retried = repository.retryRun(source.run.id, "conversation-device-action-retry")
        val retriedPrepared = repository.prepareWorkflowStep(retried.run.id, retried.steps[1].id)
        assertEquals(
            previousOutput,
            WorkflowStepSnapshotCodec.decodeInput(retriedPrepared.inputSnapshot).previousOutputs.single(),
        )
    }

    @Test
    fun workflowPersistsVerifiedBackDecisionForNextStepAndUiWithoutApprovalEvidence() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "返回动作本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察系统设置并返回上一级页面"),
                WorkflowStepDefinitionInput("根据已验证返回动作生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-back-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-back-decision"
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().insertToolResult(deviceBackResult(agentRunId))

        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "模型转述不可信返回结果\n${validDeviceActionResult(action = "back")}",
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        val decision = persistedOutput.deviceActionDecisions.single()
        assertEquals("back", decision.action)
        assertTrue(persistedOutput.text.contains("已执行并验证 返回"))
        assertTrue(persistedOutput.text.contains("本次返回不产生可复用节点引用"))
        assertFalse(persistedOutput.text.contains("后续动作必须重新观察和审批"))

        val projectedAction = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = repository.recentRunDetails(),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.first().deviceActions.single()
        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, projectedAction.outcome)
        assertEquals("返回", projectedAction.actionLabel)
        assertTrue(projectedAction.followUpGuidance.contains("按各自风险规则执行"))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        assertEquals(
            persistedOutput.text,
            WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single(),
        )
    }

    @Test
    fun workflowPersistsVerifiedSwipeDecisionWithoutViewportIdentityOrApprovalEvidence() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "滚动动作本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察系统设置并向上滚动"),
                WorkflowStepDefinitionInput("根据已验证滚动动作生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-swipe-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-swipe-decision"
        val fingerprint = "a".repeat(64)
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().insertToolResult(deviceSwipeResult(agentRunId))

        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "模型转述 snapshot_id=snapshot-secret、ref=ref-secret、viewport=$fingerprint\n" +
                validDeviceActionResult(action = "swipe"),
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        val decision = persistedOutput.deviceActionDecisions.single()
        assertEquals("swipe", decision.action)
        assertTrue(persistedOutput.text.contains("已执行并验证 滚动"))
        assertTrue(persistedOutput.text.contains("本次滚动不产生可复用节点引用"))
        assertFalse(persistedOutput.text.contains("snapshot-secret"))
        assertFalse(persistedOutput.text.contains("ref-secret"))
        assertFalse(persistedOutput.text.contains(fingerprint))

        val projectedAction = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = repository.recentRunDetails(),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.first().deviceActions.single()
        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, projectedAction.outcome)
        assertEquals("滚动", projectedAction.actionLabel)
        assertFalse(projectedAction.followUpGuidance.contains("审批"))
        assertFalse(projectedAction.toString().contains(fingerprint))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        assertEquals(
            persistedOutput.text,
            WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single(),
        )
    }

    @Test
    fun workflowPersistsVerifiedHomeDecisionForNextStepAndUiWithoutApprovalEvidence() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "返回桌面动作本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察系统设置并返回 Android 桌面"),
                WorkflowStepDefinitionInput("根据已验证桌面导航生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-home-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-home-decision"
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().insertToolResult(deviceHomeResult(agentRunId))

        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "模型转述不可信桌面结果\n${validDeviceActionResult(action = "home", afterPackageName = "com.miui.home")}",
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        val decision = persistedOutput.deviceActionDecisions.single()
        assertEquals("home", decision.action)
        assertTrue(persistedOutput.text.contains("已执行并验证 返回桌面"))
        assertTrue(persistedOutput.text.contains("本次返回桌面不产生可复用节点引用"))
        assertFalse(persistedOutput.text.contains("后续动作必须重新观察和审批"))

        val projectedAction = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = repository.recentRunDetails(),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.first().deviceActions.single()
        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, projectedAction.outcome)
        assertEquals("返回桌面", projectedAction.actionLabel)
        assertTrue(projectedAction.followUpGuidance.contains("按各自风险规则执行"))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        assertEquals(
            persistedOutput.text,
            WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single(),
        )
    }

    @Test
    fun workflowPersistsVerifiedOpenAppDecisionForNextStepAndUi() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "打开应用本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察当前页面并打开系统计算器"),
                WorkflowStepDefinitionInput("根据已验证打开应用动作生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-open-app-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-open-app-decision"
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().upsertToolCall(deviceOpenAppCall(agentRunId))
        database.agentRunDao().insertToolResult(deviceOpenAppResult(agentRunId))

        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "模型转述不可信打开应用结果\n${validDeviceActionResult(action = "open_app", afterPackageName = "com.android.calculator2")}",
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        val decision = persistedOutput.deviceActionDecisions.single()
        assertEquals("open_app", decision.action)
        assertEquals("com.android.calculator2", decision.afterPackageName)
        assertTrue(persistedOutput.text.contains("已执行并验证 打开应用"))
        assertTrue(persistedOutput.text.contains("本次打开应用不产生可复用节点引用"))
        assertFalse(persistedOutput.text.contains("后续动作必须重新观察和审批"))

        val projectedAction = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = repository.recentRunDetails(),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.first().deviceActions.single()
        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, projectedAction.outcome)
        assertEquals("打开应用", projectedAction.actionLabel)
        assertEquals("com.example.notes", projectedAction.beforePackageName)
        assertEquals("com.android.calculator2", projectedAction.afterPackageName)
        assertTrue(projectedAction.followUpGuidance.contains("按各自风险规则执行"))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        assertEquals(
            persistedOutput.text,
            WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single(),
        )
    }

    @Test
    fun workflowReplacesVerifiedTypeTextWithPrivacySafeDecisionForNextStepRetryAndProjection() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "设备文本输入本地判定",
            steps = listOf(
                WorkflowStepDefinitionInput("观察当前页面并输入安全文本"),
                WorkflowStepDefinitionInput("根据已验证输入动作生成说明"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-device-type-text-decision")
        val first = source.steps.first()
        val agentRunId = "agent-run-device-type-text-decision"
        repository.markAgentRunStarted(source.run.id, first.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().insertToolResult(deviceTypeTextResult(agentRunId))

        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = source.run.id,
            workflowStepId = first.id,
            status = WorkflowStepStatus.COMPLETED,
            result = "模型转述 text=Workflow safe text、snapshot_id=snapshot-secret、ref=ref-secret、text_sha256=fingerprint-secret\n${validDeviceActionResult(action = "type_text")}",
        )

        val persistedOutput = WorkflowStepSnapshotCodec.decodeOutput(completedFirst.outputSnapshot)!!
        assertEquals(1, persistedOutput.deviceObservationDecisions.size)
        assertEquals(1, persistedOutput.deviceActionDecisions.size)
        assertEquals("type_text", persistedOutput.deviceActionDecisions.single().action)
        assertTrue(persistedOutput.text.contains("本地设备动作判定 1（workflow-device-action-decision-v1）"))
        assertTrue(persistedOutput.text.contains("已执行并验证 type_text"))
        assertTrue(persistedOutput.text.contains("输入内容未进入答案级证据"))
        assertFalse(persistedOutput.text.contains("Workflow safe text"))
        assertFalse(persistedOutput.text.contains("snapshot-secret"))
        assertFalse(persistedOutput.text.contains("ref-secret"))
        assertFalse(persistedOutput.text.contains("fingerprint-secret"))

        val projectedAction = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = repository.recentRunDetails(),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.single().runs.single().steps.first().deviceActions.single()
        assertEquals(WorkflowDeviceActionUiOutcome.VERIFIED, projectedAction.outcome)
        assertEquals("type_text", projectedAction.action)
        assertEquals("com.example.notes", projectedAction.beforePackageName)
        assertEquals("com.example.notes", projectedAction.afterPackageName)
        assertFalse(projectedAction.toString().contains("Workflow safe text"))
        assertFalse(projectedAction.toString().contains("snapshot-secret"))
        assertFalse(projectedAction.toString().contains("ref-secret"))
        assertFalse(projectedAction.toString().contains("fingerprint-secret"))

        val prepared = repository.prepareWorkflowStep(source.run.id, source.steps[1].id)
        val previousOutput = WorkflowStepSnapshotCodec.decodeInput(prepared.inputSnapshot).previousOutputs.single()
        assertEquals(persistedOutput.text, previousOutput)
        assertFalse(previousOutput.contains("Workflow safe text"))
        assertFalse(previousOutput.contains("snapshot-secret"))
        assertFalse(previousOutput.contains("ref-secret"))
        assertFalse(previousOutput.contains("fingerprint-secret"))

        repository.markAgentRunStarted(source.run.id, source.steps[1].id, "agent-run-device-type-text-next")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "测试文本输入重试")
        val retried = repository.retryRun(source.run.id, "conversation-device-type-text-retry")
        val retriedPrepared = repository.prepareWorkflowStep(retried.run.id, retried.steps[1].id)
        val retriedPreviousOutput = WorkflowStepSnapshotCodec.decodeInput(
            retriedPrepared.inputSnapshot,
        ).previousOutputs.single()
        assertEquals(previousOutput, retriedPreviousOutput)
        assertFalse(retriedPreviousOutput.contains("Workflow safe text"))
        assertFalse(retriedPreviousOutput.contains("snapshot-secret"))
        assertFalse(retriedPreviousOutput.contains("ref-secret"))
        assertFalse(retriedPreviousOutput.contains("fingerprint-secret"))
    }

    @Test
    fun workflowRejectsTapRefDecisionWhenExecutorVerificationIsMissing() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "设备动作 Executor 验证门禁",
            steps = listOf(WorkflowStepDefinitionInput("观察并点击当前页面继续按钮")),
        )
        val run = repository.createManualRun(workflow.id, "conversation-device-action-executor-gate")
        val step = run.steps.single()
        val agentRunId = "agent-run-device-action-executor-gate"
        repository.markAgentRunStarted(run.run.id, step.id, agentRunId)
        database.agentRunDao().insertToolResult(deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null))
        database.agentRunDao().insertToolResult(
            deviceTapRefResult(agentRunId = agentRunId, executorVerified = false),
        )

        val failure = runCatching {
            repository.completeWorkflowStep(
                workflowRunId = run.run.id,
                workflowStepId = step.id,
                status = WorkflowStepStatus.COMPLETED,
                result = validDeviceActionResult(),
            )
        }.exceptionOrNull()

        assertTrue(failure is WorkflowDeviceActionEvidenceException)
        assertEquals(
            WorkflowDeviceActionInsufficientReason.EXECUTOR_VERIFICATION_MISSING,
            (failure as WorkflowDeviceActionEvidenceException).reason,
        )
    }

    @Test
    fun workflowUsesPassedLedgerForReadableDeviceSnapshot() = runBlocking {
        val agentRunId = "agent-run-readable-device-snapshot"
        database.agentRunDao().insertToolResult(
            deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null),
        )

        val decision = repository.requireDeviceObservationDecisions(agentRunId).single()

        assertEquals(WorkflowDeviceObservationDecisionStatus.LIMITED, decision.status)
        assertEquals("com.example.notes", decision.packageName)
        assertEquals(2, decision.nodeCount)
        assertEquals(1, decision.redactedNodeCount)
    }

    @Test
    fun completeRunSanitizesSingleStepDeviceObservationAndRunResult() = runBlocking {
        val workflow = repository.createWorkflow("单步骤设备观察净化", "观察当前页面")
        val created = repository.createManualRun(workflow.id, "conversation-complete-run-device")
        val agentRunId = "agent-run-complete-run-device"
        repository.markAgentRunStarted(created.run.id, created.steps.single().id, agentRunId)
        database.agentRunDao().insertToolResult(
            deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null),
        )

        repository.completeRun(
            workflowRunId = created.run.id,
            status = WorkflowRunStatus.COMPLETED,
            result = "Agent 任务已完成\n${validDeviceSnapshot()}",
        )

        val completed = repository.runDetail(created.run.id)!!
        val step = completed.steps.single()
        val persistedText = WorkflowStepSnapshotCodec.outputText(step.outputSnapshot)!!
        assertEquals(persistedText, step.result)
        assertEquals(persistedText, completed.run.result)
        assertTrue(persistedText.contains("本地设备观察判定"))
        assertFalse(persistedText.contains("snapshot-secret"))
        assertFalse(persistedText.contains("ref-secret"))
        assertFalse(persistedText.contains("银行卡密码"))
    }

    @Test
    fun completeRunAggregatesMultiStepResultFromSanitizedStepOutputs() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "多步骤设备观察汇总净化",
            steps = listOf(
                WorkflowStepDefinitionInput("观察当前页面"),
                WorkflowStepDefinitionInput("生成后续说明"),
            ),
        )
        val created = repository.createManualRun(workflow.id, "conversation-complete-run-multi-step")
        val firstAgentRunId = "agent-run-complete-run-multi-step-device"
        repository.markAgentRunStarted(created.run.id, created.steps[0].id, firstAgentRunId)
        database.agentRunDao().insertToolResult(
            deviceSnapshotResult(agentRunId = firstAgentRunId, executorVerified = null),
        )
        val completedFirst = repository.completeWorkflowStep(
            workflowRunId = created.run.id,
            workflowStepId = created.steps[0].id,
            status = WorkflowStepStatus.COMPLETED,
            result = "Agent 任务已完成\n${validDeviceSnapshot()}",
        )
        repository.markAgentRunStarted(
            created.run.id,
            created.steps[1].id,
            "agent-run-complete-run-multi-step-summary",
        )
        repository.completeWorkflowStep(
            workflowRunId = created.run.id,
            workflowStepId = created.steps[1].id,
            status = WorkflowStepStatus.COMPLETED,
            result = "第二步完成",
        )

        repository.completeRun(
            workflowRunId = created.run.id,
            status = WorkflowRunStatus.COMPLETED,
            result = validDeviceSnapshot(),
        )

        val completed = repository.runDetail(created.run.id)!!
        val firstText = WorkflowStepSnapshotCodec.outputText(completedFirst.outputSnapshot)!!
        assertEquals("$firstText\n\n第二步完成", completed.run.result)
        assertFalse(completed.run.result.orEmpty().contains("snapshot-secret"))
        assertFalse(completed.run.result.orEmpty().contains("ref-secret"))
        assertFalse(completed.run.result.orEmpty().contains("银行卡密码"))
    }

    @Test
    fun workflowBlocksNextStepWhenPersistedDeviceSnapshotIsNotVerified() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "设备观察证据不足",
            steps = listOf(
                WorkflowStepDefinitionInput("观察当前页面"),
                WorkflowStepDefinitionInput("根据观察确认事实"),
            ),
        )
        val run = repository.createManualRun(workflow.id, "conversation-device-insufficient")
        val agentRunId = "agent-run-device-insufficient"
        repository.markAgentRunStarted(run.run.id, run.steps[0].id, agentRunId)
        database.agentRunDao().insertToolResult(
            deviceSnapshotResult(agentRunId = agentRunId, verificationStatus = "FAILED"),
        )
        val failure = runCatching {
            repository.completeWorkflowStep(
                workflowRunId = run.run.id,
                workflowStepId = run.steps[0].id,
                status = WorkflowStepStatus.COMPLETED,
                result = validDeviceSnapshot(),
            )
        }.exceptionOrNull()

        assertTrue(failure is WorkflowDeviceObservationEvidenceException)
        assertTrue(failure?.message.orEmpty().contains("VERIFICATION_MISSING"))
        assertEquals(WorkflowStepStatus.RUNNING, repository.runDetail(run.run.id)!!.steps[0].status)
        assertEquals(WorkflowStepStatus.PENDING, repository.runDetail(run.run.id)!!.steps[1].status)
    }

    @Test
    fun scheduledWorkflowPersistsAndPublishesOnlyLocalDeviceDecision() = runBlocking {
        val workflow = repository.createWorkflow("后台设备观察净化", "观察当前页面")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-device-decision")
        val claim = repository.claimScheduledRun(task.id)!!
        val step = claim.run.steps.single()
        val agentRunId = "agent-run-scheduled-device-decision"
        repository.markAgentRunStarted(claim.run.run.id, step.id, agentRunId)
        database.agentRunDao().insertToolResult(
            deviceSnapshotResult(agentRunId = agentRunId, executorVerified = null),
        )

        val completed = repository.completeScheduledWorkflowStep(
            taskId = task.id,
            workflowRunId = claim.run.run.id,
            workflowStepId = step.id,
            result = "Agent 任务已完成\n${validDeviceSnapshot()}",
        )

        val persistedText = WorkflowStepSnapshotCodec.outputText(completed.outputSnapshot)!!
        assertTrue(persistedText.contains("本地设备观察判定"))
        assertFalse(persistedText.contains("snapshot-secret"))
        assertFalse(persistedText.contains("ref-secret"))
        val conversationMessages = database.conversationDao()
            .getMessagesByConversationId(claim.run.run.conversationId)
        assertTrue(conversationMessages.any { it.role == "assistant" && it.text == persistedText })
        assertTrue(conversationMessages.none { it.text.contains("snapshot-secret") || it.text.contains("ref-secret") })
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
    fun systemCancellationPersistsSameTypedStopReasonOnTaskAndWorkflowRun() = runBlocking {
        val workflow = repository.createWorkflow("系统停止审计", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-system-stop")
        val claim = repository.claimScheduledRun(task.id)!!

        repository.settleScheduledWorkflowRun(
            taskId = task.id,
            workflowRunId = claim.run.run.id,
            workflowStatus = WorkflowRunStatus.CANCELLED,
            taskStatus = ScheduledTaskStatus.CANCELLED,
            errorMessage = "系统后台配额限制停止了本次工作流",
            workerStopReasonCode = 10,
            workerStopReasonName = "QUOTA",
        )

        val storedTask = repository.getScheduledTask(task.id)!!
        val storedRun = repository.runDetail(claim.run.run.id)!!.run
        assertEquals(ScheduledTaskStatus.CANCELLED, storedTask.status)
        assertEquals(WorkflowRunStatus.CANCELLED, storedRun.status)
        assertEquals(10, storedTask.workerStopReasonCode)
        assertEquals("QUOTA", storedTask.workerStopReasonName)
        assertEquals(storedTask.workerStopReasonCode, storedRun.workerStopReasonCode)
        assertEquals(storedTask.workerStopReasonName, storedRun.workerStopReasonName)
    }

    @Test
    fun userStopFenceDoesNotGetOverwrittenByLaterWorkManagerCancellationReason() = runBlocking {
        val workflow = repository.createWorkflow("用户停止优先级", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-user-stop-priority")
        val claim = repository.claimScheduledRun(task.id)!!
        repository.requestScheduledTaskStop(task.id, "用户请求停止后台工作流")

        repository.settleScheduledWorkflowRun(
            taskId = task.id,
            workflowRunId = claim.run.run.id,
            workflowStatus = WorkflowRunStatus.CANCELLED,
            taskStatus = ScheduledTaskStatus.CANCELLED,
            errorMessage = "后台工作流已由应用停止",
            workerStopReasonCode = 1,
            workerStopReasonName = "CANCELLED_BY_APP",
        )

        val storedTask = repository.getScheduledTask(task.id)!!
        val storedRun = repository.runDetail(claim.run.run.id)!!.run
        // long: 用户点击停止先形成持久栅栏；随后 WorkManager 返回 CANCELLED_BY_APP 只是执行机制，不能替换用户意图或伪造独立系统停止原因。
        assertEquals(ScheduledTaskStatus.CANCELLED, storedTask.status)
        assertEquals(WorkflowRunStatus.CANCELLED, storedRun.status)
        assertEquals("用户请求停止后台工作流", storedTask.errorMessage)
        assertEquals(storedTask.errorMessage, storedRun.errorMessage)
        assertNull(storedTask.workerStopReasonCode)
        assertNull(storedTask.workerStopReasonName)
        assertNull(storedRun.workerStopReasonCode)
        assertNull(storedRun.workerStopReasonName)
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
    fun workerReentryPreservesStopIntentBeforeAgentRunIsLinked() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "Agent 关联前停止",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("生成回顾"),
            ),
        )
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-stop-before-agent")
        val claim = repository.claimScheduledRun(task.id)!!
        val agentRepository = RoomAgentRunRepository(context, database)
        val agentRunCountBefore = agentRepository.recentRunDetails(limit = 20).size
        val requested = repository.requestScheduledTaskStop(task.id, "用户请求停止后台工作流")!!
        assertEquals(ScheduledTaskStatus.STOP_REQUESTED, requested.status)
        assertNull(repository.runDetail(claim.run.run.id)!!.run.agentRunId)

        val coordinator = ScheduledWorkflowReentryCoordinator(
            loadTask = repository::getScheduledTask,
            loadWorkflowRun = repository::runDetail,
            closeAgentRun = { error("Agent 尚未关联时不应关闭或创建 Agent Run") },
            reconcileWorkflowRun = { workflowRunId ->
                repository.reconcileInterruptedRuns(workflowRunIds = setOf(workflowRunId)) > 0
            },
            reconcileScheduledTask = { taskId ->
                repository.reconcileInterruptedScheduledTasks(taskIds = setOf(taskId)) > 0
            },
        )

        assertTrue(coordinator.reconcile(task.id))

        val reconciledRun = repository.runDetail(claim.run.run.id)!!
        // long: 用户的停止意图早于 Agent Run 创建；Worker 重入必须把整条链视为取消，不能用“关联 Agent 缺失”改写成执行失败。
        assertEquals(WorkflowRunStatus.CANCELLED, reconciledRun.run.status)
        assertEquals(
            listOf(WorkflowStepStatus.CANCELLED, WorkflowStepStatus.CANCELLED),
            reconciledRun.steps.map { it.status },
        )
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(task.id)!!.status)
        assertEquals(agentRunCountBefore, agentRepository.recentRunDetails(limit = 20).size)
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
            settleWorkflowAndTask = { taskId, workflowRunId, reason ->
                repository.settleScheduledWorkflowRun(
                    taskId = taskId,
                    workflowRunId = workflowRunId,
                    workflowStatus = WorkflowRunStatus.CANCELLED,
                    taskStatus = ScheduledTaskStatus.CANCELLED,
                    errorMessage = reason,
                )
            },
            settleTaskWithoutWorkflow = { taskId, reason ->
                repository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
            },
        )
        val stopCoordinator = ScheduledWorkflowStopCoordinator(
            loadTask = repository::getScheduledTask,
            cancelPendingTask = repository::cancelScheduledTask,
            requestScheduledTaskStop = { taskId ->
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
            settleWorkflowAndTask = { taskId, workflowRunId, reason ->
                repository.settleScheduledWorkflowRun(
                    taskId = taskId,
                    workflowRunId = workflowRunId,
                    workflowStatus = WorkflowRunStatus.CANCELLED,
                    taskStatus = ScheduledTaskStatus.CANCELLED,
                    errorMessage = reason,
                )
            },
            settleTaskWithoutWorkflow = { taskId, reason ->
                repository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
            },
        )
        val stopCoordinator = ScheduledWorkflowStopCoordinator(
            loadTask = repository::getScheduledTask,
            cancelPendingTask = repository::cancelScheduledTask,
            requestScheduledTaskStop = { taskId ->
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
    fun userStopFallbackPreservesWorkflowTerminalWhenTaskIsHalfSettled() = runBlocking {
        val workflow = repository.createWorkflow("停止半结算任务", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-user-stop-half-settled")
        val claim = repository.claimScheduledRun(task.id)!!
        repository.requestScheduledTaskStop(task.id, "用户停止后台工作流")
        // long: 模拟停止 fallback 接管前 Workflow 已由旧路径写入终态、Task 仍停在停止请求；最终必须以既有 Workflow 事实修复 Task，不能制造矛盾终态。
        repository.completeRun(
            workflowRunId = claim.run.run.id,
            status = WorkflowRunStatus.COMPLETED,
            result = "已持久化结果",
        )
        val fallback = ScheduledWorkflowStopFallbackCoordinator(
            loadTask = repository::getScheduledTask,
            loadWorkflowRun = repository::runDetail,
            cancelAgentRun = { error("Agent 尚未关联时不应取消 Agent Run") },
            settleWorkflowAndTask = { taskId, workflowRunId, reason ->
                repository.settleScheduledWorkflowRun(
                    taskId = taskId,
                    workflowRunId = workflowRunId,
                    workflowStatus = WorkflowRunStatus.CANCELLED,
                    taskStatus = ScheduledTaskStatus.CANCELLED,
                    errorMessage = reason,
                )
            },
            settleTaskWithoutWorkflow = { taskId, reason ->
                repository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
            },
        )

        assertTrue(fallback.reconcile(task.id))

        assertEquals(WorkflowRunStatus.COMPLETED, repository.runDetail(claim.run.run.id)!!.run.status)
        assertEquals(ScheduledTaskStatus.COMPLETED, repository.getScheduledTask(task.id)!!.status)
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
    fun persistedCancelledWorkflowPreventsLateTaskCompletion() = runBlocking {
        val workflow = repository.createWorkflow("半结算取消保护", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-partial-cancel")
        val claim = repository.claimScheduledRun(task.id)!!
        repository.completeRun(
            workflowRunId = claim.run.run.id,
            status = WorkflowRunStatus.CANCELLED,
            errorMessage = "旧进程已取消工作流",
        )
        val stopResult = repository.requestScheduledTaskStop(task.id, "来晚的停止请求")!!
        assertEquals(ScheduledTaskStatus.CANCELLED, stopResult.status)
        assertEquals("旧进程已取消工作流", stopResult.errorMessage)

        val settled = repository.settleScheduledWorkflowRun(
            taskId = task.id,
            workflowRunId = claim.run.run.id,
            workflowStatus = WorkflowRunStatus.COMPLETED,
            taskStatus = ScheduledTaskStatus.COMPLETED,
            result = "迟到成功结果",
        )!!

        // long: Workflow 已先进入终态时必须成为 Task 的持久事实源，迟到成功不能把半结算链改成互相矛盾的状态。
        assertEquals(ScheduledTaskStatus.CANCELLED, settled.status)
        assertEquals("旧进程已取消工作流", settled.errorMessage)
        val workflowRun = repository.runDetail(claim.run.run.id)!!.run
        assertEquals(WorkflowRunStatus.CANCELLED, workflowRun.status)
        assertNull(workflowRun.result)
    }

    @Test
    fun stopRequestedTaskRejectsLateStepResultBeforeConversationAppend() = runBlocking {
        val workflow = repository.createWorkflow("迟到步骤消息保护", "读取当前时间")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-late-step-message")
        val claim = repository.claimScheduledRun(task.id)!!
        repository.requestScheduledTaskStop(task.id, "用户请求停止后台工作流")

        val failure = runCatching {
            repository.completeScheduledWorkflowStep(
                taskId = task.id,
                workflowRunId = claim.run.run.id,
                workflowStepId = claim.run.steps.single().id,
                result = "不应进入会话的迟到成功结果",
            )
        }.exceptionOrNull()

        // long: 停止请求已经落库后，迟到 Worker 既不能完成步骤，也不能留下看似成功的 Agent 消息。
        assertTrue(failure is CancellationException)
        assertEquals(WorkflowStepStatus.PENDING, repository.runDetail(claim.run.run.id)!!.steps.single().status)
        assertTrue(
            database.conversationDao().getMessagesByConversationId(claim.run.run.conversationId)
                .none { it.text == "不应进入会话的迟到成功结果" },
        )
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

    private fun deviceSnapshotResult(
        agentRunId: String,
        verificationStatus: String = "PASSED",
        executorVerified: Boolean? = verificationStatus == "PASSED",
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-$agentRunId",
        runId = agentRunId,
        eventId = "event-result-$agentRunId",
        toolName = "device.snapshot",
        content = validDeviceSnapshot(),
        success = true,
        errorMessage = null,
        durationMs = 193L,
        executorVerified = executorVerified,
        verificationStatus = verificationStatus,
        verifiedEventId = "event-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 4L,
        verifiedAt = 5L,
    )

    private fun verifiedToolResult(
        agentRunId: String,
        toolName: String,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-$agentRunId-$toolName",
        runId = agentRunId,
        eventId = "event-result-$agentRunId-$toolName",
        toolName = toolName,
        content = "已验证结果",
        success = true,
        errorMessage = null,
        durationMs = 10L,
        executorVerified = true,
        verificationStatus = "PASSED",
        verifiedEventId = "event-verified-$agentRunId-$toolName",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 4L,
        verifiedAt = 5L,
    )

    private fun deviceTapRefResult(
        agentRunId: String,
        executorVerified: Boolean? = true,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-tap-$agentRunId",
        runId = agentRunId,
        eventId = "event-tap-result-$agentRunId",
        toolName = "device.tap_ref",
        content = validDeviceActionResult(),
        success = true,
        errorMessage = null,
        durationMs = 241L,
        executorVerified = executorVerified,
        verificationStatus = "PASSED",
        verifiedEventId = "event-tap-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 6L,
        verifiedAt = 7L,
    )

    private fun deviceBackResult(
        agentRunId: String,
        executorVerified: Boolean? = true,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-back-$agentRunId",
        runId = agentRunId,
        eventId = "event-back-result-$agentRunId",
        toolName = "device.back",
        content = validDeviceActionResult(action = "back"),
        success = true,
        errorMessage = null,
        durationMs = 211L,
        executorVerified = executorVerified,
        verificationStatus = "PASSED",
        verifiedEventId = "event-back-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 6L,
        verifiedAt = 7L,
    )

    private fun deviceHomeResult(
        agentRunId: String,
        executorVerified: Boolean? = true,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-home-$agentRunId",
        runId = agentRunId,
        eventId = "event-home-result-$agentRunId",
        toolName = "device.home",
        content = validDeviceActionResult(action = "home", afterPackageName = "com.miui.home"),
        success = true,
        errorMessage = null,
        durationMs = 229L,
        executorVerified = executorVerified,
        verificationStatus = "PASSED",
        verifiedEventId = "event-home-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 6L,
        verifiedAt = 7L,
    )

    private fun deviceSwipeResult(
        agentRunId: String,
        executorVerified: Boolean? = true,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-swipe-$agentRunId",
        runId = agentRunId,
        eventId = "event-swipe-result-$agentRunId",
        toolName = "device.swipe",
        content = validDeviceActionResult(action = "swipe"),
        success = true,
        errorMessage = null,
        durationMs = 251L,
        executorVerified = executorVerified,
        verificationStatus = "PASSED",
        verifiedEventId = "event-swipe-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 6L,
        verifiedAt = 7L,
    )

    private fun deviceOpenAppResult(
        agentRunId: String,
        executorVerified: Boolean? = true,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-open-app-$agentRunId",
        runId = agentRunId,
        eventId = "event-open-app-result-$agentRunId",
        toolName = "device.open_app",
        content = validDeviceActionResult(action = "open_app", afterPackageName = "com.android.calculator2"),
        success = true,
        errorMessage = null,
        durationMs = 237L,
        executorVerified = executorVerified,
        verificationStatus = "PASSED",
        verifiedEventId = "event-open-app-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 6L,
        verifiedAt = 7L,
    )

    private fun deviceOpenAppCall(agentRunId: String) = AgentToolCallEntity(
        id = "tool-call-open-app-$agentRunId",
        runId = agentRunId,
        toolName = "device.open_app",
        risk = "REQUIRES_APPROVAL",
        argumentsJson = "{\"package_name\":\"com.android.calculator2\"}",
        proposedEventId = "event-open-app-proposed-$agentRunId",
        validatedEventId = "event-open-app-validated-$agentRunId",
        createdAt = 4L,
        validatedAt = 5L,
    )

    private fun deviceTypeTextResult(
        agentRunId: String,
        executorVerified: Boolean? = true,
    ) = AgentToolResultEntity(
        toolCallId = "tool-call-type-text-$agentRunId",
        runId = agentRunId,
        eventId = "event-type-text-result-$agentRunId",
        toolName = "device.type_text",
        content = validDeviceActionResult(action = "type_text"),
        success = true,
        errorMessage = null,
        durationMs = 263L,
        executorVerified = executorVerified,
        verificationStatus = "PASSED",
        verifiedEventId = "event-type-text-verified-$agentRunId",
        memoryIdsJson = "[]",
        knowledgeReferencesJson = "[]",
        replaySafety = "RESTART_REQUIRED",
        receiptToolCallId = null,
        receiptOperationId = null,
        receiptIdempotencyKey = null,
        receiptStatus = null,
        createdAt = 8L,
        verifiedAt = 9L,
    )

    private fun validDeviceActionResult(
        action: String = "tap_ref",
        afterPackageName: String = "com.example.notes",
    ): String = """
        {
          "ruleVersion":"workflow-device-action-result-v1",
          "safetyRuleVersion":"workflow-device-action-safety-v2",
          "action":"$action",
          "beforePackageName":"com.example.notes",
          "afterPackageName":"$afterPackageName",
          "afterNodeCount":3,
          "afterRedactedNodeCount":1,
          "afterTruncated":false,
          "afterObservedAt":1700000000200,
          "verified":true
        }
    """.trimIndent()

    private fun validDeviceSnapshot(): String = """
        {
          "snapshot_id":"snapshot-secret",
          "package":"com.example.notes",
          "window_title":"私人笔记",
          "window_id":7,
          "window_generation":8,
          "captured_at":1700000000000,
          "expires_at":1700000005000,
          "redacted_node_count":1,
          "truncated":false,
          "nodes":[
            {"index":0,"depth":0,"role":"button","text":"公开按钮","bounds":[0,0,100,100],"enabled":true,"selected":false,"redacted":false,"ref":"ref-secret","actions":["tap"]},
            {"index":1,"parent_index":0,"depth":1,"role":"text","bounds":[0,0,100,100],"enabled":true,"selected":false,"redacted":true,"actions":[]}
          ]
        }
    """.trimIndent()
}
