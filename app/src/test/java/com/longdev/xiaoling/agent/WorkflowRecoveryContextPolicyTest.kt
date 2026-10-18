package com.longdev.xiaoling.agent

import com.longdev.xiaoling.automation.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class WorkflowRecoveryContextPolicyTest {
    private val required = listOf("device.snapshot", "device.open_app", "device.snapshot")
    private val contract = WorkflowGoalVerificationContract("打开设置", WorkflowGoalVerificationSpec(required, "com.android.settings", listOf(required)))
    private val parent = WorkflowRunRecord("w", "definition", WorkflowTrigger.MANUAL, null, null, "c", null,
        WorkflowRunStatus.RUNNING, null, null, 1, 1, null)
    private fun step(agentRunId: String, raw: String = WorkflowStepSnapshotCodec.encodeInput("打开设置", emptyList(), "com.android.settings", contract)) =
        WorkflowStepRecord("s", "w", 1, "agent", WorkflowStepStatus.RUNNING, "步骤", "打开设置", agentRunId,
            null, null, 1, 1, null, inputSnapshot = raw)

    @Test fun frozenContractRestoresOrderAndLegacyKeepsEmptyRequirements() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun("c", "m", "包装后的执行目标")
        val detail = AgentRunDetailRecord(ledger.snapshot(run.id), emptyList())
        val context = requireNotNull(WorkflowRecoveryContextPolicy.resolve(detail, parent, listOf(step(run.id))))
        assertEquals(required, context.completionRequiredToolNames)
        assertEquals("打开设置", context.userIntent)
        assertEquals("com.android.settings", context.targetAppPackage)
        val old = step(run.id, WorkflowStepSnapshotCodec.encodeInput("打开设置", emptyList()))
        assertTrue(requireNotNull(WorkflowRecoveryContextPolicy.resolve(detail, parent, listOf(old))).completionRequiredToolNames.isEmpty())
    }

    @Test fun damagedContractAssociationAndStepMappingCannotFallBackToDirect() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun("c", "m", "打开设置")
        ledger.appendEvent(run.id, AgentChildLifecyclePolicy.INVOCATION_EVENT, "来源", RunEventMetadata.Reason(
            AgentChildLifecyclePolicy.invocation(AgentExecutionOrigin.FOREGROUND, AgentInvocationSource.WORKFLOW, false)))
        val detail = AgentRunDetailRecord(ledger.snapshot(run.id), emptyList())
        val normal = step(run.id)
        val damaged = JSONObject(normal.inputSnapshot).put("goalVerificationContract", "bad").toString()
        for ((runParent, steps) in listOf(null to emptyList(), parent to listOf(normal, normal),
            parent to listOf(normal.copy(sequence = 2)), parent.copy(conversationId = "other") to listOf(normal),
            parent.copy(status = WorkflowRunStatus.CANCELLED) to listOf(normal), parent to listOf(step(run.id, damaged)),
            parent to listOf(normal, normal.copy(id = "other", sequence = 2, agentRunId = null)))) {
            assertNotNull(runCatching { WorkflowRecoveryContextPolicy.resolve(detail, runParent, steps) }.exceptionOrNull())
        }
    }
}
