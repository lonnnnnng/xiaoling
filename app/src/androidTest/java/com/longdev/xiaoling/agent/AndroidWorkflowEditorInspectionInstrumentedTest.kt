package com.longdev.xiaoling.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.automation.*
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** long: 用独立 instrumentation 进程只读回查指定 Run，不启动页面、模型、工具或重放动作。 */
class AndroidWorkflowEditorInspectionInstrumentedTest {
    @Test fun inspectSpecifiedEditorWorkflowWithoutReplay() = runBlocking {
        requireAndroidLiveScope(300)
        val args = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(args.getString("stage300InspectionRunId"))
        require(runId.startsWith("workflow-run-"))
        val denied = when (args.getString("stage300InspectionDecision")) {
            "deny" -> true
            "approve" -> false
            else -> error("缺少明确验收决定")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workflows = RoomWorkflowRepository(context)
        val agents = RoomAgentRunRepository(context)
        val before = requireNotNull(workflows.runDetail(runId))
        val step = before.steps.single()
        val input = WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot)
        val contract = requireNotNull(input.goalVerificationContract)
        val tools = listOf("device.snapshot", "device.open_app", "device.snapshot")
        assertEquals("com.android.settings", input.targetAppPackage)
        assertEquals("打开系统设置并通过后置页面观察确认完成", contract.sourceGoal)
        assertEquals("com.android.settings", contract.spec.expectedFinalPackageName)
        assertEquals(tools, contract.spec.requiredToolNames)
        assertEquals(listOf(tools), contract.spec.requiredToolNamesByStep)
        val agent = requireNotNull(agents.runDetail(requireNotNull(step.agentRunId)))
        assertTrue(agent.snapshot.events.mapNotNull { it.metadata as? RunEventMetadata.LlmRequest }
            .let { it.isNotEmpty() && it.all { event -> event.model == "gpt-6-luna" } })
        assertEquals(if (denied) WorkflowRunStatus.FAILED else WorkflowRunStatus.COMPLETED, before.run.status)
        assertEquals(if (denied) WorkflowStepStatus.FAILED else WorkflowStepStatus.COMPLETED, step.status)
        assertEquals(if (denied) AgentRunStatus.FAILED else AgentRunStatus.COMPLETED, agent.snapshot.run.status)
        val actionCall = agent.toolLedger.calls.single { it.toolName == "device.open_app" }
        val approval = agent.approvals.single()
        assertEquals(actionCall.id, approval.toolCallId)
        assertEquals(if (denied) ApprovalRequestStatus.DENIED else ApprovalRequestStatus.APPROVED, approval.status)
        assertEquals(mapOf("package_name" to "com.android.settings"), approval.arguments)
        assertEquals(if (denied) tools.take(2) else tools, agent.toolLedger.calls.map { it.toolName })
        if (denied) {
            assertTrue(agent.toolLedger.results.none { it.toolName == "device.open_app" })
            assertTrue(before.run.goalVerificationDecision?.status != WorkflowGoalVerificationStatus.VERIFIED)
            assertTrue(before.run.errorMessage.orEmpty().contains("工具未获批准"))
        } else {
            assertEquals(3, agent.toolLedger.results.size)
            assertTrue(agent.toolLedger.results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
            assertEquals(true, agent.toolLedger.results.single { it.toolCallId == actionCall.id }.executorVerified)
            assertEquals(WorkflowGoalVerificationStatus.VERIFIED, before.run.goalVerificationDecision!!.status)
            assertEquals(WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED, before.run.goalVerificationDecision!!.reason)
        }
        // long: 回查前后的完整持久对象都必须相同，不能让诊断顺手结算 Run 或补写工具事实。
        assertEquals(before, workflows.runDetail(runId))
        assertEquals(agent, agents.runDetail(agent.snapshot.run.id))
        androidLiveStatus("STAGE300_EVIDENCE WORKFLOW_EDITOR_INSPECT decision=${if (denied) "deny" else "approve"} frozenEqual=true terminalEqual=true ledgerEqual=true noReplay=true workflowRunId=$runId")
    }
}
