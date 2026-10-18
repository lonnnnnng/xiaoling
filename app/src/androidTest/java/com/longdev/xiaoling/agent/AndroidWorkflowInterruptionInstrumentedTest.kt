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
        val currentGoals = definition?.let { db.workflowDao().getWorkflowStepDefinitions(it.id).sortedBy { step -> step.sequence }.map { step -> step.goal } }
        val agentDetails = JSONArray()
        for (step in detail.steps) {
            val agentId = step.agentRunId ?: continue
            val agent = requireNotNull(agents.runDetail(agentId))
            val profile = agent.snapshot.events.mapNotNull { (it.metadata as? RunEventMetadata.AgentProfileSelection)?.profile }.single()
            check(profile.id.startsWith("stage295-profile-") && profile.providerId.startsWith("stage295-provider-"))
            val observations = JSONArray()
            val typeCall = agent.toolLedger.calls.singleOrNull { it.toolName == "device.type_text" }
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
                .put("profileId", profile.id).put("providerId", profile.providerId)
                .put("tools", JSONArray(agent.toolLedger.calls.map { it.toolName }))
                .put("results", JSONArray(agent.toolLedger.results.map { "${it.toolName}:${it.success}:${it.verificationStatus}" }))
                .put("approvals", JSONArray(agent.approvals.map { "${it.id}:${it.status}" })).put("observations", observations))
        }
        val values = JSONObject()
        for (name in listOf("xiaoling_room_state", "xiaoling_ui", "xiaoling", "xiaoling_mcp")) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            values.put(name, JSONObject().put("keys", JSONArray(prefs.keys.sorted())).put("digest", androidLivePreferenceDigest(prefs)))
        }
        androidLiveStatus("STAGE295_INTERRUPTION " + JSONObject().put("workflowRunId", runId)
            .put("workflowId", detail.run.workflowId).put("workflowStatus", detail.run.status.name)
            .put("goalStatus", detail.run.goalVerificationDecision?.status?.name)
            .put("goalReason", detail.run.goalVerificationDecision?.reason?.name)
            .put("seedStatus", seed.run.status.name).put("definitionPresent", definition != null)
            .put("definitionGoalsEqualFrozenSeed", currentGoals?.let { it == frozenGoals } ?: JSONObject.NULL)
            .put("conversationId", detail.run.conversationId).put("agents", agentDetails).put("preferences", values)
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
