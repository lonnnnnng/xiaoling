package com.longdev.xiaoling.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.automation.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** long: 受控持久化边界只证明完成门禁与 Room 重开，不冒充自然 LMK 或原生设备动作。 */
class WorkflowRecoveryContractInstrumentedTest {
    private val names = listOf("device.snapshot", "device.open_app", "device.snapshot")
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun open(name: String) = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name)
        .addMigrations(*XiaoLingDatabase.migrations()).build()

    @Test fun productionUseCaseRestoresFrozenContractAcrossRoomReopenWithoutReplay() = runBlocking {
        for (verifiedCount in 1..3) {
            val databaseName = "stage299-contract-${UUID.randomUUID()}.db"
            var db = open(databaseName)
            try {
                val workflow = RoomWorkflowRepository(context, db)
                val contract = WorkflowGoalVerificationContract("打开设置", WorkflowGoalVerificationSpec(names,
                    "com.android.settings", listOf(names)))
                val (_, frozen) = workflow.createWorkflowAndManualRun("受控恢复完成门禁", listOf(WorkflowStepDefinitionInput("打开设置")),
                    "c", "com.android.settings", contract)
                val repo = RoomAgentRunRepository(context, db)
                val step = frozen.steps.single()
                val ledger = object : AgentRunLedger by repo {
                    override suspend fun createRun(conversationId: String, userMessageId: String, goal: String, retryOfRunId: String?, parentRunId: String?) =
                        createRun(conversationId, userMessageId, goal, retryOfRunId)
                    override suspend fun createRun(conversationId: String, userMessageId: String, goal: String, retryOfRunId: String?): AgentRunRecord {
                        val run = repo.createInvokedRun(conversationId, userMessageId, goal, retryOfRunId, null,
                            AgentChildLifecyclePolicy.invocation(AgentExecutionOrigin.FOREGROUND, AgentInvocationSource.WORKFLOW, false))
                        workflow.markAgentRunStarted(frozen.run.id, step.id, run.id)
                        return run
                    }
                }
                val executed = mutableListOf<String>()
                val registry = object : ToolRegistry {
                    private val definitions = names.distinct().map { ToolDefinition(it, "受控公开事实", ToolRisk.SAFE) }
                    override fun availableTools() = definitions
                    override fun definition(name: String) = definitions.singleOrNull { it.name == name }
                    override suspend fun execute(call: ToolCall): ToolExecutionResult {
                        executed += call.name
                        return ToolExecutionResult(true, "受控已验证事实", verified = true)
                    }
                }
                val llm = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = error("使用顺序规划")
                    override suspend fun proposeNextAction(goal: String, tools: List<ToolDefinition>, completedTools: List<AgentToolExecution>) =
                        if (completedTools.size < names.size) AgentPlanDecision.CallTool(ToolCall(name = names[completedTools.size], arguments = emptyMap(), risk = ToolRisk.SAFE))
                        else AgentPlanDecision.Complete
                    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String = error("应在验证边界中断")
                }
                var count = 0
                val error = runCatching { MinimalAgentRuntime(ledger, registry, llm, faultInjector = object : AgentRuntimeFaultInjector {
                    override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) {
                        if (++count == verifiedCount) throw AgentProcessTerminationSimulation()
                    }
                }).run("c", "m", "打开设置", invocationSource = AgentInvocationSource.WORKFLOW,
                    workflowDeviceActionContext = WorkflowDeviceActionRunContext(frozen.run.id, step.id, step.detail, "com.android.settings", names)) }.exceptionOrNull()
                assertTrue(error is AgentProcessTerminationSimulation)
                val id = requireNotNull(db.workflowDao().getRun(frozen.run.id)?.agentRunId)
                val originalLedger = repo.toolLedger(id)
                db.close()
                db = open(databaseName)
                val reopened = RoomAgentRunRepository(context, db)
                val detail = requireNotNull(reopened.runDetail(id))
                assertEquals(names, requireNotNull(reopened.workflowRecoveryContext(detail)).completionRequiredToolNames)
                val result = runCatching { AgentRunUseCase(context, OpenAiCompatibleClient(), baseLedger = reopened).resumeVerifiedToolRun(detail) }
                if (verifiedCount < 3) assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("恢复未满足步骤完成标准"))
                assertEquals(if (verifiedCount == 3) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED, reopened.snapshot(id).run.status)
                assertEquals(verifiedCount == 3, result.isSuccess)
                assertEquals(originalLedger, reopened.toolLedger(id))
                assertEquals(names.take(verifiedCount), executed)
                androidLiveStatus("STAGE299_EVIDENCE RECOVERY verifiedCount=$verifiedCount completed=${result.isSuccess} noReplay=true frozenContract=true roomReopened=true controlledOnly=true database=$databaseName runId=$id")
            } finally { db.close() }
        }
    }

    @Test fun duplicateAssociationAndOrphanHeaderFailBeforeAnyRecoveryExecution() = runBlocking {
        val databaseName = "stage299-association-${UUID.randomUUID()}.db"
        val db = open(databaseName)
        try {
            val repo = RoomAgentRunRepository(context, db)
            val run = repo.createInvokedRun("c", "m", "只观察", null, null,
                AgentChildLifecyclePolicy.invocation(AgentExecutionOrigin.FOREGROUND, AgentInvocationSource.WORKFLOW, false))
            val workflow = RoomWorkflowRepository(context, db)
            val (_, frozen) = workflow.createWorkflowAndManualRun("关联保护", listOf(WorkflowStepDefinitionInput("只观察")), "c")
            workflow.markAgentRunStarted(frozen.run.id, frozen.steps.single().id, run.id)
            val detail = requireNotNull(repo.runDetail(run.id))
            val step = requireNotNull(db.workflowDao().getStep(frozen.steps.single().id))
            db.workflowDao().upsertStep(step.copy(id = "duplicate", sequence = 2))
            assertNotNull(runCatching { repo.workflowRecoveryContext(detail) }.exceptionOrNull())
            db.workflowDao().upsertStep(step.copy(agentRunId = null))
            db.workflowDao().upsertStep(step.copy(id = "duplicate", sequence = 2, agentRunId = null))
            assertNotNull(runCatching { repo.workflowRecoveryContext(detail) }.exceptionOrNull())
            assertTrue(repo.toolLedger(run.id).calls.isEmpty())
        } finally { db.close() }
    }

    @Test fun invalidFrozenContextSettlesActiveStepWithoutConsumingPendingApproval() = runBlocking {
        val databaseName = "stage299-invalid-approval-${UUID.randomUUID()}.db"
        val db = open(databaseName)
        try {
            val repo = RoomAgentRunRepository(context, db)
            val run = repo.createInvokedRun("c", "m", "公开回显", null, null,
                AgentChildLifecyclePolicy.invocation(AgentExecutionOrigin.FOREGROUND, AgentInvocationSource.WORKFLOW, false))
            val workflow = RoomWorkflowRepository(context, db)
            val (_, frozen) = workflow.createWorkflowAndManualRun("坏关联审批保护", listOf(WorkflowStepDefinitionInput("公开回显")), "c")
            workflow.markAgentRunStarted(frozen.run.id, frozen.steps.single().id, run.id)
            val call = ToolCall(name = "fake.echo", arguments = mapOf("goal" to "公开回显"), risk = ToolRisk.REQUIRES_APPROVAL)
            val metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments)
            repo.appendEvent(run.id, "tool.call.validated", "已校验公开工具", metadata)
            val active = repo.appendStep(run.id, "approval", "待审批", "仅批准本次公开工具", AgentStepStatus.RUNNING)
            val approval = repo.createApprovalRequest("c", run.id, call, requireNotNull(FakeToolRegistry().definition(call.name)))
            repo.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
            val step = requireNotNull(db.workflowDao().getStep(frozen.steps.single().id))
            db.workflowDao().upsertStep(step.copy(inputSnapshot = "{}"))
            val error = runCatching { AgentRunUseCase(context, OpenAiCompatibleClient(), baseLedger = repo).resumeApprovedRun(
                requireNotNull(repo.runDetail(run.id)), approval, ProviderRequestConfig("https://example.invalid", "public-fixture", "gpt-6-luna"),
                "本测试不得发出模型请求", approvalReason = "确认原公开工具") }.exceptionOrNull()
            assertNotNull(error)
            val final = requireNotNull(repo.runDetail(run.id))
            assertEquals(AgentRunStatus.FAILED, final.snapshot.run.status)
            assertEquals(AgentStepStatus.FAILED, final.snapshot.steps.single { it.id == active.id }.status)
            assertEquals(ApprovalRequestStatus.PENDING, final.approvals.single().status)
            assertTrue(final.toolLedger.results.isEmpty())
        } finally { db.close() }
    }
}
