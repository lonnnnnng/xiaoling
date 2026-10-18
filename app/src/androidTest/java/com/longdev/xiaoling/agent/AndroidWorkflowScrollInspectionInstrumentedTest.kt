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

/** long: 针对本机既有一组 Provider/Profile 和已存在 alias 的基线，只读同一滚动 Run 与原偏好；不启动 VM 或重放动作。 */
class AndroidWorkflowScrollInspectionInstrumentedTest {
    @Test fun inspectSpecifiedCompletedScrollWorkflowWithoutReplay() = runBlocking {
        check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("stage297InspectScroll") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        val workflows = RoomWorkflowRepository(context)
        val agents = RoomAgentRunRepository(context)
        val detail = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage297WorkflowRunId"))))
        val seed = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage297SeedRunId"))))
        check(detail.run.conversationId.startsWith("conversation-stage297-"))
        check(detail.run.status == WorkflowRunStatus.COMPLETED)
        val goal = requireNotNull(detail.run.goalVerificationDecision)
        check(goal.status == WorkflowGoalVerificationStatus.VERIFIED && goal.reason == WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED)
        check(detail.steps.size == 2 && detail.steps.all { it.status == WorkflowStepStatus.COMPLETED })
        check(seed.run.workflowId == detail.run.workflowId && seed.run.conversationId == detail.run.conversationId &&
            seed.run.status == WorkflowRunStatus.CANCELLED && seed.steps.all { it.agentRunId == null })
        check(db.workflowDao().getWorkflow(detail.run.workflowId) == null)
        val actions = listOf("device.open_app", "device.swipe")
        val toolsByStep = actions.map { listOf("device.snapshot", it, "device.snapshot") }
        check(goal.requiredToolNames == toolsByStep.flatten() && goal.matchedRequiredToolNames == toolsByStep.flatten())
        val reports = JSONArray()
        for ((index, step) in detail.steps.withIndex()) {
            val agent = requireNotNull(agents.runDetail(requireNotNull(step.agentRunId)))
            check(agent.snapshot.run.status == AgentRunStatus.COMPLETED)
            check(agent.toolLedger.calls.map { it.toolName } == toolsByStep[index])
            check(agent.toolLedger.results.size == 3 && agent.toolLedger.results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
            val action = agent.toolLedger.calls.single { it.toolName == actions[index] }
            check(agent.toolLedger.results.single { it.toolCallId == action.id }.executorVerified == true)
            if (index == 0) {
                check(action.arguments == mapOf("package_name" to "com.android.settings"))
                check(agent.approvals.single().let { it.status == ApprovalRequestStatus.APPROVED && it.toolCallId == action.id })
            } else {
                check(agent.approvals.isEmpty() && action.arguments["direction"] == "up")
            }
            val input = WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot)
            check(input.goalVerificationContract?.spec?.requiredToolNamesByStep == toolsByStep)
            check(input.goalVerificationContract?.spec?.expectedFinalPackageName == "com.android.settings")
            val output = requireNotNull(WorkflowStepSnapshotCodec.decodeOutput(step.outputSnapshot))
            check(output.verifiedToolNames == toolsByStep[index])
            check(output.deviceActionDecisions.single().let { it.status == WorkflowDeviceActionDecisionStatus.VERIFIED && it.action == actions[index].removePrefix("device.") })
            // long: 独立回读只输出状态、数量和工具名；节点正文、ref 和执行期锚点不进入诊断日志。
            val corrections = agent.snapshot.events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED }
            check(corrections <= 1)
            reports.put(JSONObject().put("runId", agent.snapshot.run.id).put("status", agent.snapshot.run.status.name)
                .put("tools", JSONArray(toolsByStep[index])).put("resultsPassed", 3).put("approvals", agent.approvals.size)
                .put("prematureCompletionCorrections", corrections))
        }
        for (table in listOf("messages", "agent_memories", "agent_notes", "agent_memory_candidates", "knowledge_documents", "workflows", "scheduled_tasks", "workflow_schedules")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'").use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        val preferenceDigests = JSONObject()
        for ((name, argument) in listOf("xiaoling_room_state" to "stage297ExpectedRoomDigest", "xiaoling_ui" to "stage297ExpectedUiDigest")) {
            val expected = requireNotNull(args.getString(argument)).also { check(it.matches(Regex("[0-9a-f]{64}"))) }
            val actual = androidLivePreferenceDigest(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
            check(actual == expected) { "独立进程偏好摘要与专项基线不同：$name" }
            preferenceDigests.put(name, actual)
        }
        check(!File(context.filesDir, "agent-memory-delete-undo.json").exists())
        check(db.providerDao().getAll().size == 1 && db.agentProfileDao().list().size == 1)
        check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("xiaoling_api_key"))
        androidLiveStatus("STAGE297_SCROLL_INSPECTION " + JSONObject().put("workflowRunId", detail.run.id)
            .put("workflowStatus", detail.run.status.name).put("goalStatus", goal.status.name).put("goalReason", goal.reason.name)
            .put("agents", reports).put("preferences", preferenceDigests).put("businessContentEmpty", true)
            .put("definitionRemoved", true).put("providerCount", 1).put("profileCount", 1).put("memoryUndo", "absent")
            .put("aliasPresent", true).put("modelOrToolsReplayed", false))
    }
}
