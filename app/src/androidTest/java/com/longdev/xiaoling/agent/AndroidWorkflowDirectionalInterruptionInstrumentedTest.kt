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

/** long: 原生崩溃跳过 finally 后，按本轮 seed、UUID、冻结来源及完整偏好摘要定向恢复，不重放模型或工具。 */
class AndroidWorkflowDirectionalInterruptionInstrumentedTest {
    @Test fun inspectSpecifiedDirectionalInterruption() = runBlocking { inspect(false) }
    @Test fun recoverSpecifiedDirectionalInterruption() = runBlocking { inspect(true) }
    private suspend fun inspect(recover: Boolean) {
        check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("stage298InspectInterruption") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        val workflows = RoomWorkflowRepository(context)
        val agents = RoomAgentRunRepository(context)
        val detail = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage298InterruptedRunId"))))
        val seed = requireNotNull(workflows.runDetail(requireNotNull(args.getString("stage298SeedRunId"))))
        check(detail.run.conversationId.startsWith("conversation-stage298-") && seed.run.workflowId == detail.run.workflowId)
        val reports = JSONArray()
        for (step in detail.steps) step.agentRunId?.let { id ->
            val agent = requireNotNull(agents.runDetail(id))
            reports.put(JSONObject().put("runId", id).put("status", agent.snapshot.run.status.name)
                .put("tools", JSONArray(agent.toolLedger.calls.map { it.toolName }))
                .put("verifications", JSONArray(agent.toolLedger.results.map { it.verificationStatus?.name ?: "absent" }))
                .put("approvals", JSONArray(agent.approvals.map { it.status.name }))
                // long: 仅输出固定失败类别与布尔状态，提供方错误、窗口正文和工具参数都不得进入诊断。
                .put("failureCodes", JSONArray(com.longdev.xiaoling.device.DeviceActionFailure.entries.filter { code ->
                    agent.toolLedger.results.any { it.content.contains(code.name) }
                }.map { it.name }))
                .put("failureFlags", JSONObject(listOf("工具未通过 Executor 回读验证", "未完成", "过期", "预算", "缺少", "前台")
                    .associateWith { agent.snapshot.run.errorMessage.orEmpty().contains(it) }))
                .put("resultStates", JSONArray(agent.toolLedger.results.map { result ->
                    JSONObject().put("tool", result.toolName).put("success", result.success)
                        .put("executorVerified", result.executorVerified ?: JSONObject.NULL)
                })))
        }
        androidLiveStatus("STAGE298_INTERRUPTION " + JSONObject().put("workflowRunId", detail.run.id)
            .put("workflowStatus", detail.run.status.name).put("agents", reports)
            .put("definitionPresent", db.workflowDao().getWorkflow(detail.run.workflowId) != null)
            .put("providerCount", db.providerDao().getAll().size).put("profileCount", db.agentProfileDao().list().size)
            .put("roomDigest", androidLivePreferenceDigest(context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).all))
            .put("uiDigest", androidLivePreferenceDigest(context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE).all)))
        if (recover) {
            check(args.getString("stage298RecoverOwnedFixtures") == "true")
            recoverOwnedFixtures(context, db, workflows, agents, detail, seed)
        }
    }
    private suspend fun recoverOwnedFixtures(context: Context, db: XiaoLingDatabase, workflows: RoomWorkflowRepository,
        agents: RoomAgentRunRepository, detail: WorkflowRunDetail, seed: WorkflowRunDetail) {
        check(seed.run.status == WorkflowRunStatus.CANCELLED && seed.steps.all { it.agentRunId == null })
        val conversationId = detail.run.conversationId
        val prefix = "conversation-stage298-"
        check(conversationId.startsWith(prefix) && seed.run.conversationId == conversationId)
        val id = conversationId.removePrefix(prefix)
        check(java.util.UUID.fromString(id).toString() == id)
        val providerId = "stage298-provider-$id"
        val profileId = "stage298-profile-$id"
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
        val args = InstrumentationRegistry.getArguments()
        val expectedRoom = requireNotNull(args.getString("stage298ExpectedRoomDigest"))
        val expectedUi = requireNotNull(args.getString("stage298ExpectedUiDigest"))
        check(listOf(expectedRoom, expectedUi).all { it.matches(Regex("[0-9a-f]{64}")) })
        val clearedRoom = room.all - selections.keys
        val default = db.agentProfileDao().list().single { it.id != profileId }
        check(default.id == "agent-profile-default" && default.name == "默认 Agent")
        // long: 只接受删除本轮选择后，或补回唯一原默认 Profile 后，完整摘要精确匹配日志基线；未知原值不推测、不覆盖。
        val restoredRoom = listOf(clearedRoom, clearedRoom + ("selected_agent_profile_id" to default.id))
            .single { androidLivePreferenceDigest(it) == expectedRoom }
        check(androidLivePreferenceDigest(ui.all - switches.keys) == expectedUi)
        val ownedOutputs = detail.steps.mapNotNull { WorkflowStepSnapshotCodec.outputText(it.outputSnapshot ?: it.result) } +
            listOfNotNull(detail.run.goalVerificationDecision?.renderForUser())
        val messages = db.conversationDao().getMessagesByConversationId(conversationId)
        // long: 正式入口会写每步用户目标与本地结果；仅允许关联 Agent 的原始 message ID/精确目标及持久输出，陌生正文或附件继续阻断清理。
        check(messages.all { message ->
            (message.role == "user" && message.origin == "USER" && ownedUserMessages[message.id] == message.text) ||
                // long: 相同正文不代表同一 Run；即使没有 TOOL 分片，助手结果也必须携带本轮 Agent 的可信上下文归属。
                (message.role == "assistant" && message.origin == "AGENT_RESULT" && message.text in ownedOutputs &&
                    VerifiedAgentContextCodec.decode(message.verifiedAgentContext)?.runId in agentIds) ||
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
        // long: 仅处理已确认归属的选择键；完整基线已通过精确摘要核对，审计 Run 和未关联的旧记录保持原样。
        check(selections.all { (key, value) -> room.all[key] == value } && switches.all { (key, value) -> ui.all[key] == value })
        check(room.edit().apply {
            selections.keys.forEach(::remove)
            (restoredRoom["selected_agent_profile_id"] as? String)?.let { putString("selected_agent_profile_id", it) }
        }.commit())
        check(ui.edit().apply { switches.keys.forEach(::remove) }.commit())
        if (ui.all.isEmpty()) check(context.deleteSharedPreferences("xiaoling_ui"))
        check(androidLivePreferenceDigest(room.all) == expectedRoom && androidLivePreferenceDigest(ui.all) == expectedUi)
        check(workflows.runDetail(detail.run.id) == stopped && workflows.runDetail(seed.run.id) == seed)
        check(db.providerDao().getAll().none { it.id == providerId } && db.agentProfileDao().list().none { it.id == profileId })
        // long: 公开探针与创建时间的内存归属已丢失，即使配置已空也保留 alias，不把猜测当作删除授权。
        androidLiveStatus("STAGE298_INTERRUPTION_RECOVERY ownedFixturesRemoved=true auditRetained=true modelOrToolsReplayed=false originalPreferenceValuesRecovered=true knownBaselineMatched=true aliasRetained=true ownedMessagesRemoved=${messages.size}")
    }
}
