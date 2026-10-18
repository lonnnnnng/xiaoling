package com.longdev.xiaoling.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import com.longdev.xiaoling.automation.WorkflowGoalVerificationContractCodec
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.security.KeyStore

/** long: 进程原生崩溃会跳过 finally；只读确认指定公开测试 Run 的残留，不启动 VM 或请求真实模型。 */
class AndroidWorkflowInterruptionInstrumentedTest {
    @Test fun inspectSpecifiedPublicWorkflowInterruption() = runBlocking {
        inspectInterruption(recover = false)
    }

    @Test fun recoverSpecifiedPublicWorkflowInterruption() = runBlocking {
        inspectInterruption(recover = true)
    }

    @Test fun restoreVerifiedDefaultProfileSelectionAfterPublicWorkflow() = runBlocking {
        check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("stage296RestoreDefaultProfileSelection") == "true")
        val expected = requireNotNull(args.getString("stage296ExpectedRoomDigest")).also { check(it.matches(Regex("[0-9a-f]{64}"))) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        val workflows = RoomWorkflowRepository(context)
        val run = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage295InterruptedRunId"))))
        val seed = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage295SeedRunId"))))
        check(run.run.status == WorkflowRunStatus.COMPLETED && run.run.goalVerificationDecision?.status?.name == "VERIFIED")
        check(seed.run.workflowId == run.run.workflowId && seed.run.conversationId == run.run.conversationId &&
            seed.run.status == WorkflowRunStatus.CANCELLED && seed.steps.all { it.agentRunId == null })
        check(run.run.conversationId.startsWith("conversation-stage295-") && db.workflowDao().getWorkflow(run.run.workflowId) == null)
        val agents = RoomAgentRunRepository(context)
        check(run.steps.size == 3 && run.steps.all { step ->
            val agent = requireNotNull(agents.runDetail(requireNotNull(step.agentRunId)))
            agent.snapshot.run.status == AgentRunStatus.COMPLETED && agent.approvals.single().status == ApprovalRequestStatus.APPROVED
        })
        for (table in listOf("messages", "agent_memories", "agent_notes", "agent_memory_candidates", "knowledge_documents", "workflows", "scheduled_tasks", "workflow_schedules")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'").use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        val defaultProfile = db.agentProfileDao().list().single()
        check(defaultProfile.id == "agent-profile-default" && defaultProfile.name == "默认 Agent")
        val prefs = context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE)
        val before = prefs.all
        val key = "selected_agent_profile_id"
        // long: 仅恢复本轮已记录的“无选择键”基线：新增值必须是唯一原默认 Profile，删除此键后的全部稳定值摘要必须精确匹配；未知变化一律保留。
        check(before[key] == defaultProfile.id && androidLivePreferenceDigest(before - key) == expected)
        check(prefs.all == before && prefs.edit().remove(key).commit())
        check(androidLivePreferenceDigest(prefs.all) == expected)
        androidLiveStatus("STAGE296_DEFAULT_SELECTION_RESTORE defaultProfileMatched=true singleKeyOnly=true baselineEqual=true toolsReplayed=false")
    }

    private suspend fun inspectInterruption(recover: Boolean) {
        check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36)
        val args = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(args.getString("stage295InterruptedRunId"))
        val seedId = requireNotNull(args.getString("stage295SeedRunId"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        val workflows = RoomWorkflowRepository(context)
        val agents = RoomAgentRunRepository(context)
        val detail = requireNotNull(workflows.runDetail(runId))
        val seed = requireNotNull(workflows.runDetail(seedId))
        check(seed.run.workflowId == detail.run.workflowId)
        val definition = db.workflowDao().getWorkflow(detail.run.workflowId)
        check(detail.run.conversationId.startsWith("conversation-stage295-"))
        definition?.let { check(it.name.startsWith("公开工作流验收-")) }
        val frozenGoals = seed.steps.map { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot).goal }
        // long: 隐私诊断只能使用 seed 中唯一冻结的公开 JSON 参数；不从失败工具正文反推输入，也不把输入本身写入日志。
        val inputPrefix = "本次已确认的输入参数数据："
        val inputGoal = seed.steps.sortedBy { it.sequence }.getOrNull(2)
            ?.let { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot).goal }
        val publicInput = inputGoal?.takeIf { it.contains(inputPrefix) }?.let { goal ->
            check(goal.split(inputPrefix).size == 2)
            JSONObject(goal.substringAfter(inputPrefix)).getString("text").also {
                check(it.matches(Regex("[a-p]{8}")))
            }
        }
        val currentGoals = definition?.let { db.workflowDao().getWorkflowStepDefinitions(it.id).sortedBy { step -> step.sequence }.map { step -> step.goal } }
        val agentDetails = JSONArray()
        for (step in detail.steps) {
            val agentId = step.agentRunId ?: continue
            val agent = requireNotNull(agents.runDetail(agentId))
            val profile = agent.snapshot.events.mapNotNull { (it.metadata as? RunEventMetadata.AgentProfileSelection)?.profile }.single()
            check(profile.id.startsWith("stage295-profile-") && profile.providerId.startsWith("stage295-provider-"))
            val observations = JSONArray()
            val typeCall = agent.toolLedger.calls.singleOrNull { it.toolName == "device.type_text" }
            val privacyEvents = JSONArray()
            publicInput?.let { marker ->
                for (event in agent.snapshot.events.filter {
                    it.type in setOf("tool.result", "tool.call.proposed", "tool.call.validated")
                }) {
                    val result = event.metadata as? RunEventMetadata.ToolResult
                    val call = event.metadata as? RunEventMetadata.ToolCall
                    privacyEvents.put(JSONObject().put("eventType", event.type)
                        .put("toolName", result?.toolName ?: call?.toolName)
                        .put("eventHit", event.toString().contains(marker))
                        .put("contentHit", result?.content?.contains(marker) == true)
                        .put("readableEvidenceHit", result?.readableEvidence?.toString()?.contains(marker) == true))
                }
            }
            // long: 只输出公开搜索控件标签与动作摘要，诊断首页/搜索页混淆；其他节点正文、引用和窗口标题不进入日志。
            for (result in agent.toolLedger.results.filter { it.toolName == "device.snapshot" }) {
                val json = JSONObject(result.content)
                val nodes = json.getJSONArray("nodes")
                val searchNodes = JSONArray()
                val editableNodes = JSONArray()
                var editableCount = 0
                for (index in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(index)
                    val actions = node.optJSONArray("actions") ?: JSONArray()
                    if ((0 until actions.length()).any { actions.getString(it) == "type_text" }) {
                        editableCount++
                        val labels = listOf("text", "description", "hint").map { node.optString(it) }.filter(String::isNotBlank)
                        editableNodes.put(JSONObject().put("semanticLabelsEmpty", labels.isEmpty())
                            .put("textPresent", node.optString("text").isNotBlank()).put("hintPresent", node.optString("hint").isNotBlank())
                            .put("descriptionPresent", node.optString("description").isNotBlank())
                            .put("textEqualsHint", node.optString("text") == node.optString("hint"))
                            .put("matchesProposedTypeTarget", typeCall?.arguments?.get("snapshot_id") == json.optString("snapshot_id") &&
                                typeCall.arguments["ref"] == node.optString("ref")))
                    }
                    val publicLabels = listOf("text", "description", "hint").mapNotNull { name ->
                        node.optString(name).takeIf { it in setOf("搜索设置", "搜索", "Search settings", "Search", "在设置中搜索", "在“设置”中搜索", "在「设置」中搜索") }
                    }
                    if (publicLabels.isNotEmpty()) searchNodes.put(JSONObject().put("publicLabels", JSONArray(publicLabels)).put("actions", actions))
                }
                observations.put(JSONObject().put("package", json.optString("package")).put("editableCount", editableCount)
                    .put("editableNodes", editableNodes).put("searchNodes", searchNodes))
            }
            agentDetails.put(JSONObject().put("runId", agentId).put("status", agent.snapshot.run.status.name)
                // long: 只输出纠错次数以核对真实模型是否使用同一有界门禁，不输出模型输入、结果正文或未知节点标签。
                .put("prematureCompletionCorrections", agent.snapshot.events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
                .put("profileId", profile.id).put("providerId", profile.providerId)
                .put("tools", JSONArray(agent.toolLedger.calls.map { it.toolName }))
                .put("results", JSONArray(agent.toolLedger.results.map { "${it.toolName}:${it.success}:${it.verificationStatus}" }))
                .put("approvals", JSONArray(agent.approvals.map { "${it.id}:${it.status}" })).put("observations", observations)
                .put("privacyEvents", privacyEvents))
        }
        val values = JSONObject()
        val expectedRoomDigest = args.getString("stage296ExpectedRoomDigest")?.also { check(it.matches(Regex("[0-9a-f]{64}"))) }
        for (name in listOf("xiaoling_room_state", "xiaoling_ui", "xiaoling", "xiaoling_mcp")) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val summary = JSONObject().put("keys", JSONArray(prefs.keys.sorted())).put("digest", androidLivePreferenceDigest(prefs))
            if (name == "xiaoling_room_state" && expectedRoomDigest != null) {
                val fixtureId = detail.run.conversationId.removePrefix("conversation-stage295-")
                check(java.util.UUID.fromString(fixtureId).toString() == fixtureId)
                val owned = mapOf("selected_provider_id" to "stage295-provider-$fixtureId",
                    "selected_agent_profile_id" to "stage295-profile-$fixtureId", "selected_conversation_id" to detail.run.conversationId)
                // long: 诊断延迟落盘只报告本轮归属匹配和删除单键能否恢复已记录摘要，不输出偏好正文，也不据此直接修改未知原值。
                summary.put("expectedDigestMatches", androidLivePreferenceDigest(prefs) == expectedRoomDigest)
                    .put("selectionMatchesSoleDefaultProfile", prefs["selected_agent_profile_id"] ==
                        db.agentProfileDao().list().singleOrNull()?.takeIf { it.name == "默认 Agent" }?.id)
                    .put("selectionMatchesOwnedFixtures", JSONObject(owned.mapValues { (key, value) -> prefs[key] == value }))
                    .put("singleKeyRemovalRestoresExpectedDigest", JSONArray(prefs.keys.filter {
                        androidLivePreferenceDigest(prefs - it) == expectedRoomDigest
                    }.sorted()))
            }
            values.put(name, summary)
        }
        androidLiveStatus("STAGE295_INTERRUPTION " + JSONObject().put("workflowRunId", runId)
            .put("workflowId", detail.run.workflowId).put("workflowStatus", detail.run.status.name)
            .put("goalStatus", detail.run.goalVerificationDecision?.status?.name)
            .put("goalReason", detail.run.goalVerificationDecision?.reason?.name)
            .put("seedStatus", seed.run.status.name).put("definitionPresent", definition != null)
            .put("definitionGoalsEqualFrozenSeed", currentGoals?.let { it == frozenGoals } ?: JSONObject.NULL)
            .put("conversationId", detail.run.conversationId).put("agents", agentDetails).put("preferences", values)
            .put("publicInputParsed", publicInput != null)
            .put("providerCount", db.providerDao().getAll().size).put("profileCount", db.agentProfileDao().list().size)
            .put("aliasPresent", KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("xiaoling_api_key")))
        if (recover) {
            check(args.getString("stage295RecoverOwnedFixtures") == "true") { "恢复清理必须显式选择" }
            recoverOwnedFixtures(context, db, workflows, agents, detail, seed)
        }
    }

    private suspend fun recoverOwnedFixtures(context: Context, db: XiaoLingDatabase, workflows: RoomWorkflowRepository,
        agents: RoomAgentRunRepository, detail: WorkflowRunDetail, seed: WorkflowRunDetail) {
        check(seed.run.status == WorkflowRunStatus.CANCELLED && seed.steps.all { it.agentRunId == null })
        val conversationId = detail.run.conversationId
        val prefix = "conversation-stage295-"
        check(conversationId.startsWith(prefix) && seed.run.conversationId == conversationId)
        val id = conversationId.removePrefix(prefix)
        check(java.util.UUID.fromString(id).toString() == id)
        val providerId = "stage295-provider-$id"
        val profileId = "stage295-profile-$id"
        val definition = requireNotNull(db.workflowDao().getWorkflow(detail.run.workflowId))
        val definitions = db.workflowDao().getWorkflowStepDefinitions(definition.id).sortedBy { it.sequence }
        val frozen = seed.steps.sortedBy { it.sequence }.map { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot) }
        // long: 内存快照丢失后以 seed 的冻结输入、步骤身份和创建版本确认归属，不能把当前定义自身当作原始快照。
        check(definition.name == "公开工作流验收-${id.take(8)}" && definition.createdAt == seed.run.createdAt && definition.updatedAt == definition.createdAt)
        check(definitions.size == 3 && frozen.size == 3 && definition.goal == frozen.first().goal)
        check(definition.targetAppPackage == "com.android.settings" && frozen.all { it.targetAppPackage == definition.targetAppPackage })
        check(WorkflowGoalVerificationContractCodec.decode(requireNotNull(definition.goalVerificationContract)) == frozen.first().goalVerificationContract)
        for (index in definitions.indices) {
            val current = definitions[index]
            check(current.goal == frozen[index].goal && current.id == seed.steps[index].definitionStepId &&
                current.idempotencyKey == seed.steps[index].idempotencyKey && current.updatedAt == current.createdAt)
        }
        val provider = db.providerDao().getAll().single { it.id == providerId }
        val profile = db.agentProfileDao().list().single { it.id == profileId }
        check(provider.name == "公开 Workflow 验收" && profile.name == provider.name && profile.providerId == providerId &&
            profile.model == provider.model && profile.createdAt == profile.updatedAt)
        val agentIds = detail.steps.mapNotNull { it.agentRunId }.toSet()
        val ownedUserMessages = mutableMapOf<String, String>()
        for (agentId in agentIds) {
            val agent = requireNotNull(agents.runDetail(agentId))
            val selected = agent.snapshot.events.mapNotNull {
                (it.metadata as? RunEventMetadata.AgentProfileSelection)?.profile
            }.single()
            check(selected.id == profileId && selected.providerId == providerId && selected.model == provider.model)
            ownedUserMessages[agent.snapshot.run.userMessageId] = "/agent ${detail.steps.single { it.agentRunId == agentId }.detail}"
        }
        // long: 定向收敛仅终止已丢失执行栈的活动记录，不重放工具；已经完成的 Run 保留其原始状态和局部成功证据。
        agents.closeInterruptedRuns(runIds = agentIds, preserveResumableCandidates = false)
        workflows.reconcileInterruptedRuns(workflowRunIds = setOf(detail.run.id))
        val stopped = requireNotNull(workflows.runDetail(detail.run.id))
        check(stopped.run.status in setOf(WorkflowRunStatus.COMPLETED, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED, WorkflowRunStatus.BLOCKED))
        val room = context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE)
        val ui = context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE)
        val selections = mapOf("selected_provider_id" to providerId, "selected_agent_profile_id" to profileId, "selected_conversation_id" to conversationId)
        val switches = mapOf("memory_candidates_enabled" to false, "device_agent_enabled" to true, "device_action_auto_approval_enabled" to false)
        check(selections.all { (key, value) -> room.all[key] == value } && switches.all { (key, value) -> ui.all[key] == value })
        val ownedOutputs = detail.steps.mapNotNull { WorkflowStepSnapshotCodec.outputText(it.outputSnapshot ?: it.result) } +
            listOfNotNull(detail.run.goalVerificationDecision?.renderForUser())
        val messages = db.conversationDao().getMessagesByConversationId(conversationId)
        // long: 正式入口会写每步用户目标与本地结果；仅允许关联 Agent 的原始 message ID/精确目标及持久输出，陌生正文或附件继续阻断清理。
        check(messages.all { message ->
            (message.role == "user" && message.origin == "USER" && ownedUserMessages[message.id] == message.text) ||
                (message.role == "assistant" && message.origin == "AGENT_RESULT" && message.text in ownedOutputs) ||
                (message.role == "error" && message.origin == "ERROR" && detail.run.errorMessage != null &&
                    message.text == "工作流失败\n${detail.run.errorMessage}")
        }) { "遗留会话出现无法关联本 Run 的正文，保留现场" }
        val messagesById = messages.associateBy { it.id }
        val parts = db.conversationDao().getMessagePartsByConversationId(conversationId)
        // long: AGENT_RESULT 正常持久化 TEXT + TOOL 证据；工具分片必须关联本轮 Agent，图片/文档及任何二进制附件仍拒绝。
        check(parts.all { part ->
            val message = requireNotNull(messagesById[part.messageId])
            part.binaryData == null && part.fileName == null && part.mimeType == null && when (part.type) {
                "TEXT" -> part.text == message.text
                "TOOL" -> message.origin == "AGENT_RESULT" && VerifiedAgentContextCodec.decode(message.verifiedAgentContext)?.runId in agentIds
                else -> false
            }
        }) { "遗留会话出现无法关联的分片或附件，保留现场" }
        db.withTransaction {
            check(db.workflowDao().getWorkflow(definition.id) == definition && db.workflowDao().getWorkflowStepDefinitions(definition.id).sortedBy { it.sequence } == definitions)
            check(db.providerDao().getAll().single { it.id == providerId } == provider && db.agentProfileDao().list().single { it.id == profileId } == profile)
            check(db.conversationDao().getMessagesByConversationId(conversationId) == messages) { "遗留会话被同期修改，保留现场" }
            check(db.conversationDao().getMessagePartsByConversationId(conversationId) == parts) { "遗留消息分片被同期修改，保留现场" }
            db.workflowDao().deleteWorkflowStepDefinitions(definition.id)
            db.openHelper.writableDatabase.execSQL("DELETE FROM workflows WHERE id = ?", arrayOf(definition.id))
            db.conversationDao().deleteMessageParts(messages.map { it.id })
            db.conversationDao().deleteMessagesByConversationIds(listOf(conversationId))
            db.conversationDao().deleteConversations(listOf(conversationId))
            db.agentProfileDao().delete(profileId)
            db.openHelper.writableDatabase.execSQL("DELETE FROM providers WHERE id = ?", arrayOf(providerId))
        }
        // long: 原选择值随崩溃丢失，只移除仍精确指向本轮配置的键和本轮开关，回到默认选择；不宣称恢复崩溃前的全部偏好值。
        check(selections.all { (key, value) -> room.all[key] == value } && switches.all { (key, value) -> ui.all[key] == value })
        check(room.edit().apply { selections.keys.forEach(::remove) }.commit())
        check(ui.edit().apply { switches.keys.forEach(::remove) }.commit())
        if (ui.all.isEmpty()) check(context.deleteSharedPreferences("xiaoling_ui"))
        check(workflows.runDetail(detail.run.id) == stopped && workflows.runDetail(seed.run.id) == seed)
        check(db.providerDao().getAll().none { it.id == providerId } && db.agentProfileDao().list().none { it.id == profileId })
        // long: 公开探针与创建时间的内存归属已丢失，即使配置已空也保留 alias，不把猜测当作删除授权。
        androidLiveStatus("STAGE295_INTERRUPTION_RECOVERY ownedFixturesRemoved=true auditRetained=true modelOrToolsReplayed=false originalPreferenceValuesRecovered=false ownedSelectionsCleared=true aliasRetained=true ownedMessagesRemoved=${messages.size}")
    }
}
