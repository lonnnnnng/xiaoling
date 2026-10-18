package com.longdev.xiaoling.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.automation.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** long: 独立进程只读本轮往返/顶部失败账本；不启动 VM、模型或动作，也不放宽旧阶段固定两步成功断言。 */
class AndroidWorkflowDirectionalScrollInspectionInstrumentedTest {
    @Test fun inspectSpecifiedDirectionalWorkflowWithoutReplay() = runBlocking {
        check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("stage298Inspect") == "true")
        val boundary = when (args.getString("stage298Case")) {
            "device-scroll-return" -> false
            "device-scroll-boundary" -> true
            else -> error("未选择本阶段明确验收项")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        val workflows = RoomWorkflowRepository(context)
        val agents = RoomAgentRunRepository(context)
        val detail = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage298WorkflowRunId"))))
        val seed = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage298SeedRunId"))))
        check(detail.run.conversationId.startsWith("conversation-stage298-"))
        check(detail.run.status == if (boundary) WorkflowRunStatus.FAILED else WorkflowRunStatus.COMPLETED)
        check(detail.steps.map { it.status } == if (boundary) listOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.FAILED)
            else List(3) { WorkflowStepStatus.COMPLETED })
        check(seed.run.workflowId == detail.run.workflowId && seed.run.conversationId == detail.run.conversationId &&
            seed.run.status == WorkflowRunStatus.CANCELLED && seed.steps.all { it.agentRunId == null })
        check(db.workflowDao().getWorkflow(detail.run.workflowId) == null)
        val actions = if (boundary) listOf("device.open_app", "device.swipe") else listOf("device.open_app", "device.swipe", "device.swipe")
        val toolsByStep = actions.map { listOf("device.snapshot", it, "device.snapshot") }
        val goal = detail.run.goalVerificationDecision
        if (boundary) check(goal?.status != WorkflowGoalVerificationStatus.VERIFIED)
        else {
            check(requireNotNull(goal).status == WorkflowGoalVerificationStatus.VERIFIED && goal.reason == WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED)
            check(goal.requiredToolNames == toolsByStep.flatten() && goal.matchedRequiredToolNames == toolsByStep.flatten())
        }
        val reports = JSONArray()
        val observations = mutableListOf<List<JSONObject>>()
        for ((index, step) in detail.steps.withIndex()) {
            val failed = boundary && index == 1
            val agent = requireNotNull(agents.runDetail(requireNotNull(step.agentRunId)))
            check(agent.snapshot.run.status == if (failed) AgentRunStatus.FAILED else AgentRunStatus.COMPLETED)
            val expectedTools = if (failed) toolsByStep[index].take(2) else toolsByStep[index]
            check(agent.toolLedger.calls.map { it.toolName } == expectedTools && agent.toolLedger.results.size == expectedTools.size)
            val call = agent.toolLedger.calls.single { it.toolName == actions[index] }
            val result = agent.toolLedger.results.single { it.toolCallId == call.id }
            observations.add(agent.toolLedger.results.filter { it.toolName == "device.snapshot" }.map { JSONObject(it.content) })
            if (failed) {
                // long: 普通模型/网络失败不能冒充顶部边界；必须是原生动作拒绝或执行器拒绝已发送的无效动作。
                check((!result.success && (result.content.contains("ACTION_NOT_SUPPORTED") || result.content.contains("ACTION_FAILED"))) ||
                    (result.success && result.executorVerified == false && result.verificationStatus == ToolVerificationStatus.FAILED))
                check(WorkflowStepSnapshotCodec.decodeOutput(step.outputSnapshot)?.deviceActionDecisions.orEmpty().isEmpty())
            } else {
                check(agent.toolLedger.results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
                check(result.executorVerified == true)
                val output = requireNotNull(WorkflowStepSnapshotCodec.decodeOutput(step.outputSnapshot))
                check(output.verifiedToolNames == expectedTools)
                check(output.deviceActionDecisions.single().let { it.status == WorkflowDeviceActionDecisionStatus.VERIFIED && it.action == actions[index].removePrefix("device.") })
            }
            if (index == 0) {
                check(call.arguments == mapOf("package_name" to "com.android.settings"))
                check(agent.approvals.single().let { it.status == ApprovalRequestStatus.APPROVED && it.toolCallId == call.id && it.arguments == call.arguments })
            } else check(agent.approvals.isEmpty() && call.arguments["direction"] == if (failed || index == 2) "down" else "up")
            val contract = requireNotNull(WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot).goalVerificationContract)
            check(contract.spec.requiredToolNamesByStep == toolsByStep && contract.spec.expectedFinalPackageName == "com.android.settings")
            reports.put(JSONObject().put("runId", agent.snapshot.run.id).put("status", agent.snapshot.run.status.name)
                .put("tools", JSONArray(expectedTools)).put("approvals", agent.approvals.size)
                .put("prematureCompletionCorrections", agent.snapshot.events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED }))
        }
        if (!boundary) {
            // long: 独立回读以同一持久 Run 的首尾真实 snapshot 比较往返内容；只返回布尔结果，正文不进入诊断日志。
            check(visibleContent(observations.first().last()) == visibleContent(observations.last().last()))
        }
        for (table in listOf("messages", "agent_memories", "agent_notes", "agent_memory_candidates", "knowledge_documents", "workflows", "scheduled_tasks", "workflow_schedules")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
        val digests = JSONObject()
        for ((name, argument) in listOf("xiaoling_room_state" to "stage298ExpectedRoomDigest", "xiaoling_ui" to "stage298ExpectedUiDigest")) {
            val expected = requireNotNull(args.getString(argument)).also { check(it.matches(Regex("[0-9a-f]{64}"))) }
            val actual = androidLivePreferenceDigest(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
            check(expected == actual); digests.put(name, actual)
        }
        check(!File(context.filesDir, "agent-memory-delete-undo.json").exists())
        check(db.providerDao().getAll().size == 1 && db.agentProfileDao().list().size == 1)
        check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("xiaoling_api_key"))
        androidLiveStatus("STAGE298_DIRECTIONAL_INSPECTION " + JSONObject().put("case", args.getString("stage298Case"))
            .put("workflowRunId", detail.run.id).put("workflowStatus", detail.run.status.name)
            .put("goalStatus", goal?.status?.name ?: "absent").put("agents", reports).put("preferences", digests)
            .put("businessContentEmpty", true).put("definitionRemoved", true).put("providerCount", 1).put("profileCount", 1)
            .put("memoryUndo", "absent").put("aliasPresent", true).put("modelOrToolsReplayed", false)
            .put("returnedContentEqual", if (boundary) JSONObject.NULL else true).put("physicalBoundaryReobserved", false))
    }

    private fun visibleContent(json: JSONObject): Set<String> {
        val nodes = json.getJSONArray("nodes")
        return (0 until nodes.length()).map(nodes::getJSONObject).flatMap { node ->
            listOf("text", "description", "hint").map { node.optString(it) }.filter(String::isNotBlank)
        }.toSet()
    }
}
