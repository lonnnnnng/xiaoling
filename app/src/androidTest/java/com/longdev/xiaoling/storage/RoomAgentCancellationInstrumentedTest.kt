package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.ui.AgentRunStopCoordinator
import com.longdev.xiaoling.automation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoomAgentCancellationInstrumentedTest {
    @Test fun cancellationAtCompletionCommitCannotReturnSuccessOrContinueWorkflow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            val repository = RoomAgentRunRepository(context, database)
            val workflows = RoomWorkflowRepository(context, database)
            val definition = workflows.createWorkflow("提交竞争", listOf(WorkflowStepDefinitionInput("第一步"), WorkflowStepDefinitionInput("不能执行的后续步骤")))
            val workflow = workflows.createManualRun(definition.id, "completion-race")
            var commitRunId: String? = null
            val ledger = object : AgentRunLedger by repository {
                // long: 此用例专门验证提交 SQL 的栅栏，关闭轮询抢跑，确保取消确实发生在最后一次完成提交内。
                override suspend fun isCancellationRequested(runId: String) = false
                override suspend fun completeRun(runId: String, result: String): Boolean {
                    commitRunId = runId
                    workflows.markAgentRunStarted(workflow.run.id, workflow.steps.first().id, runId)
                    assertTrue(repository.requestCancel(runId, "完成提交竞争"))
                    return repository.completeRun(runId, result)
                }
            }
            val runtime = MinimalAgentRuntime(ledger, llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) =
                    ToolCall(name = "fake.echo", arguments = mapOf("goal" to goal), risk = ToolRisk.REQUIRES_APPROVAL)
                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) = "{}"
            })
            var nextStepStarted = false
            val failure = runCatching {
                runtime.run(workflow.run.conversationId, "completion-race-message", "第一步")
                nextStepStarted = true
            }.exceptionOrNull()
            assertTrue("提交被停止栅栏拒绝后必须传播取消", failure is CancellationException)
            val runId = requireNotNull(commitRunId)
            workflows.completeByAgentRunId(runId, WorkflowRunStatus.CANCELLED, errorMessage = "完成提交竞争")
            val snapshot = repository.snapshot(runId)
            assertFalse(nextStepStarted)
            assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
            assertNull(snapshot.run.result)
            assertFalse(snapshot.events.any { it.type == "run.status" && it.message == "COMPLETED" })
            assertFalse(AgentRunExecutionRegistry.isRunning(runId))
            assertEquals(listOf(WorkflowStepStatus.CANCELLED, WorkflowStepStatus.CANCELLED), workflows.runDetail(workflow.run.id)!!.steps.map { it.status })
        } finally { database.close() }
    }

    @Test fun completionCommitRespectsTerminalStatesAndIdempotentResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            val repository = RoomAgentRunRepository(context, database)
            for (status in listOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELLED)) {
                val run = repository.createRun("terminal-$status", "message", "保持终态")
                repository.updateRunStatus(run.id, status)
                assertFalse(repository.completeRun(run.id, "迟到结果"))
                assertEquals(status, repository.snapshot(run.id).run.status)
            }
            val completed = repository.createRun("completed", "message", "完成幂等")
            assertTrue(repository.completeRun(completed.id, "原结果"))
            assertTrue(repository.completeRun(completed.id, "原结果"))
            assertFalse(repository.completeRun(completed.id, "不同结果"))
            assertFalse(repository.requestCancel(completed.id, "迟到停止"))
            assertEquals("原结果", repository.snapshot(completed.id).run.result)
        } finally { database.close() }
    }

    @Test fun stoppingRecoveredApprovalAlsoClosesItsWorkflowSuffixOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            val agents = RoomAgentRunRepository(context, database)
            val workflows = RoomWorkflowRepository(context, database)
            val definition = workflows.createWorkflow("恢复的前台任务", listOf(WorkflowStepDefinitionInput("等审批"), WorkflowStepDefinitionInput("后续步骤")))
            val workflow = workflows.createManualRun(definition.id, "recovered-workflow-conversation")
            val agent = agents.createRun(workflow.run.conversationId, "approval-message", "等审批")
            agents.updateRunStatus(agent.id, AgentRunStatus.WAITING_APPROVAL)
            workflows.markAgentRunStarted(workflow.run.id, workflow.steps.first().id, agent.id)
            val other = agents.createRun("unrelated-conversation", "unrelated-message", "继续思考")
            agents.updateRunStatus(other.id, AgentRunStatus.THINKING)
            val coordinator = AgentRunStopCoordinator(
                loadRun = agents::runDetail,
                linkedScheduledTaskId = workflows::scheduledTaskIdForAgentRun,
                stopScheduledTask = { error("前台任务不能误停后台 Task") },
                requestCancel = agents::requestCancel,
                isExecuting = { false },
                cancelInactiveRun = agents::cancelActiveRun,
                settleCancelledWorkflow = { id, reason -> workflows.completeByAgentRunId(id, WorkflowRunStatus.CANCELLED, errorMessage = reason); Unit },
            )
            assertEquals(AgentRunStatus.CANCELLED, coordinator.stop(agent.id, "停止恢复中的任务")!!.snapshot.run.status)
            val cancelled = workflows.runDetail(workflow.run.id)!!
            assertEquals(WorkflowRunStatus.CANCELLED, cancelled.run.status)
            assertEquals(listOf(WorkflowStepStatus.CANCELLED, WorkflowStepStatus.CANCELLED), cancelled.steps.map { it.status })
            assertEquals(AgentRunStatus.THINKING, agents.runDetail(other.id)!!.snapshot.run.status)
        } finally { database.close() }
    }

    @Test fun stoppingBackgroundRunUsesTaskStopFenceAndJointSettlement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            val agents = RoomAgentRunRepository(context, database)
            val workflows = RoomWorkflowRepository(context, database)
            val definition = workflows.createWorkflow("后台任务", listOf(WorkflowStepDefinitionInput("第一步"), WorkflowStepDefinitionInput("读取当前时间")))
            val task = workflows.createOneTimeScheduledTask(definition.id, 30)
            workflows.attachWorkRequest(task.id, "m1-task-stop-work")
            val claim = workflows.claimScheduledRun(task.id)!!
            val previous = agents.createRun(claim.run.run.conversationId, claim.userMessageId, "第一步")
            workflows.markAgentRunStarted(claim.run.run.id, claim.run.steps.first().id, previous.id)
            assertTrue(agents.completeRun(previous.id, "第一步完成"))
            workflows.completeWorkflowStep(claim.run.run.id, claim.run.steps.first().id, WorkflowStepStatus.COMPLETED, result = "第一步完成")
            val agent = agents.createRun(claim.run.run.conversationId, claim.userMessageId, "读取当前时间")
            agents.updateRunStatus(agent.id, AgentRunStatus.THINKING)
            workflows.markAgentRunStarted(claim.run.run.id, claim.run.steps.last().id, agent.id)
            // long: 后台已进入下一步，旧 Step 仍必须能定位同一 Task，不能因 header 的 Agent ID 变化而丢失停止归属。
            assertEquals(task.id, workflows.scheduledTaskIdForAgentRun(previous.id))
            val cancellations = mutableListOf<String>()
            val fallback = ScheduledWorkflowStopFallbackCoordinator(
                loadTask = workflows::getScheduledTask, loadWorkflowRun = workflows::runDetail,
                cancelAgentRun = { agents.cancelActiveRun(it, "后台停止") },
                settleWorkflowAndTask = { id, runId, reason -> workflows.settleScheduledWorkflowRun(id, runId, WorkflowRunStatus.CANCELLED, ScheduledTaskStatus.CANCELLED, errorMessage = reason); Unit },
                settleTaskWithoutWorkflow = { id, reason -> workflows.finishScheduledTask(id, ScheduledTaskStatus.CANCELLED, reason); Unit },
            )
            val scheduled = ScheduledWorkflowStopCoordinator(
                loadTask = workflows::getScheduledTask, cancelPendingTask = workflows::cancelScheduledTask,
                requestScheduledTaskStop = { workflows.requestScheduledTaskStop(it, "后台停止") },
                cancelSystemWork = { id ->
                    assertEquals(ScheduledTaskStatus.STOP_REQUESTED, workflows.getScheduledTask(id)!!.status)
                    cancellations += id
                },
                waitForWorkerSettlement = {}, reconcileUnsettledTask = fallback::reconcile,
            )
            val coordinator = AgentRunStopCoordinator(
                loadRun = agents::runDetail, linkedScheduledTaskId = workflows::scheduledTaskIdForAgentRun,
                stopScheduledTask = { scheduled.stop(it); Unit }, requestCancel = agents::requestCancel,
                isExecuting = { false }, cancelInactiveRun = agents::cancelActiveRun,
                settleCancelledWorkflow = { _, _ -> error("后台任务不能走前台单独结算") },
            )
            coordinator.stop(agent.id, "任务中心停止")
            assertEquals(listOf(task.id), cancellations)
            assertEquals(AgentRunStatus.CANCELLED, agents.runDetail(agent.id)!!.snapshot.run.status)
            assertEquals(AgentRunStatus.COMPLETED, agents.runDetail(previous.id)!!.snapshot.run.status)
            assertEquals(WorkflowRunStatus.CANCELLED, workflows.runDetail(claim.run.run.id)!!.run.status)
            assertEquals(ScheduledTaskStatus.CANCELLED, workflows.getScheduledTask(task.id)!!.status)
        } finally { database.close() }
    }

    @Test
    fun persistedStopClosesWaitingRuntimeAndKeepsConsumedBudget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            val repository = RoomAgentRunRepository(context, database)
            val started = CompletableDeferred<Unit>()
            val runtime = MinimalAgentRuntime(repository, llm = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
                    started.complete(Unit)
                    awaitCancellation()
                }
                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String =
                    error("停止等待不能进入总结")
            })
            val job = launch(Dispatchers.IO) { runtime.run("m1-stop", "m1-stop-message", "等待模型后停止") }
            withTimeout(5_000) { started.await() }
            val run = repository.recentRunDetails(1).single().snapshot.run
            delay(150)
            // long: 与任务中心一致，活跃执行先持久化停止意图，Runtime 结算预算后再冻结取消终态。
            assertTrue(AgentRunExecutionRegistry.isRunning(run.id))
            assertTrue(repository.requestCancel(run.id, "M1 停止验证"))
            withTimeout(5_000) { job.join() }
            val snapshot = repository.snapshot(run.id)
            assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
            assertTrue(snapshot.run.cancelRequestedAt != null)
            assertFalse(AgentRunExecutionRegistry.isRunning(run.id))
            assertEquals(AgentStepStatus.CANCELLED, snapshot.steps.single().status)
            val budgetIndex = snapshot.events.indexOfLast { it.type == AgentEventTypes.EXECUTION_BUDGET_UPDATED }
            val cancelIndex = snapshot.events.indexOfFirst { it.type == "run.cancelled" }
            assertTrue(budgetIndex >= 0 && budgetIndex < cancelIndex)
            val budget = snapshot.events[budgetIndex].metadata as RunEventMetadata.ExecutionBudget
            assertTrue("停止时必须保留实际模型等待耗时", budget.consumedMs >= 100)
        } finally {
            database.close()
        }
    }
}
