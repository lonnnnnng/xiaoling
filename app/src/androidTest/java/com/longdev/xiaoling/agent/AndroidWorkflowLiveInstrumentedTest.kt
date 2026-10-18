package com.longdev.xiaoling.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.*
import com.longdev.xiaoling.data.*
import com.longdev.xiaoling.device.*
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** long: 正式页面发起真实 Workflow；业务内容非空即拒绝，只移除本轮配置/定义并保留实际 Run 与步骤审计。 */
class AndroidWorkflowLiveInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun actualWorkflowPageUsesRealModelAndPersistsResult() = runBlocking {
        val config = androidLiveProvider(294)
        val case = requireNotNull(InstrumentationRegistry.getArguments().getString("stage294Case"))
        require(case in setOf("create-readonly", "device-approve", "device-deny"))
        val device = case != "create-readonly"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        val workflowRepo = RoomWorkflowRepository(context)
        val agentRepo = RoomAgentRunRepository(context)
        val automation = requireNotNull(InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        // long: 正式 VM 会读取本机消息与恢复账本；空会话本身不足以隔离用户数据，还必须拒绝业务内容和未结束执行。
        listOf("messages", "agent_memories", "agent_notes", "agent_memory_candidates", "knowledge_documents", "workflows", "scheduled_tasks", "workflow_schedules").forEach { table ->
            assertZero(db, "SELECT COUNT(*) FROM $table", "业务内容非空：$table")
        }
        assertZero(db, "SELECT COUNT(*) FROM conversations WHERE title NOT IN ('新会话', '') OR summary != '' OR summaryUntilMessageId IS NOT NULL", "已有用户会话内容")
        assertZero(db, "SELECT COUNT(*) FROM agent_runs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')", "已有未结束 Agent Run")
        assertZero(db, "SELECT COUNT(*) FROM workflow_runs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'BLOCKED')", "已有未结束 Workflow Run")
        assertZero(db, "SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'", "已有待审批记录")
        check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("xiaoling_api_key")) { "既有 Provider 密钥不存在，拒绝新增持久密钥" }
        val auditTables = listOf("agent_runs", "agent_steps", "approval_requests", "run_events", "agent_context_snapshots",
            "agent_context_compressions", "agent_tool_calls", "agent_tool_results", "workflow_runs", "workflow_steps")
        val originalAudit = auditTables.associateWith { rows(db, it) }
        val providers = db.providerDao().getAll().toSet()
        val profiles = db.agentProfileDao().list().toSet()
        val preferences = listOf("xiaoling_room_state", "xiaoling_ui").associateWith { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            Triple(prefs, prefs.all.toMap(), File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists())
        }
        val undo = File(context.filesDir, "agent-memory-delete-undo.json")
        // long: 正式初始化会清理损坏或不兼容的 undo；只读哈希不足以保护旧文件，已有 undo（含 AtomicFile 旁文件）必须在启动前拒绝。
        check(listOf(undo, File(undo.path + ".bak"), File(undo.path + ".new")).none(File::exists)) { "既有 Memory undo 文件存在，拒绝启动正式 VM" }
        fun undoHash() = if (undo.exists()) hash(undo.readBytes()) else "absent"
        val originalUndo = undoHash()
        val authorization = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val id = UUID.randomUUID().toString()
        val providerId = "stage294-provider-$id"
        val profileId = "stage294-profile-$id"
        val conversationId = "conversation-stage294-$id"
        val workflowName = "公开工作流验收-${id.take(8)}"
        val roomOwned = mapOf<String, Any>("providers_migrated" to true, "conversations_migrated" to true,
            "selected_provider_id" to providerId, "selected_agent_profile_id" to profileId, "selected_conversation_id" to conversationId)
        val uiOwned = buildMap<String, Any> {
            put("memory_candidates_enabled", false)
            if (device) { put("device_agent_enabled", true); put("device_action_auto_approval_enabled", false) }
        }
        val tools = if (device) listOf("device.snapshot", "device.open_app", "device.back", "device.home", "device.tap_ref", "device.type_text", "device.swipe")
            else listOf("app.current_time", "app.get_info")
        val goals = if (device) listOf("先调用 device.snapshot，再仅调用一次 device.open_app，package_name 必须为 com.android.settings。目标是打开系统设置首页。依据工具后置验证完成后直接结束，不调用其他工具。")
            else listOf("只调用一次 app.current_time 获取当前时间，使用实际结果总结后结束。", "只调用一次 app.get_info 获取应用版本，使用本步实际结果总结后结束。")
        var ownedWorkflowId: String? = null
        var ownedDefinitionSnapshot: Pair<WorkflowEntity, List<WorkflowStepDefinitionEntity>>? = null
        suspend fun captureOwnedDefinition(workflowId: String) = db.withTransaction {
            ownedWorkflowId = workflowId
            val snapshot = requireNotNull(db.workflowDao().getWorkflow(workflowId)) to db.workflowDao().getWorkflowStepDefinitions(workflowId)
            check(ownedDefinitionSnapshot == null || ownedDefinitionSnapshot == snapshot) { "本轮定义在页面回读前被修改，保留现场" }
            ownedDefinitionSnapshot = snapshot
        }
        var verifiedWorkflowRunId: String? = null
        var actorStopped = true
        var primaryFailure: Throwable? = null
        androidLiveStatus("STAGE294_EVIDENCE WORKFLOW_SCOPE businessContentEmpty=true oldAuditRows=${originalAudit.values.sumOf { it.size }} case=$case definitionSeeded=$device")
        try {
            if (device) rebind(context, automation, requireNotNull(authorization))
            val encrypted = ApiKeyCipher().encrypt(config.apiKey)
            val now = System.currentTimeMillis()
            db.providerDao().insertAll(listOf(ProviderEntity(providerId, "公开 Workflow 验收", config.baseUrl,
                encrypted.iv, encrypted.ciphertext, config.model, "[\"${config.model}\"]", "[\"${config.model}\"]", "")))
            RoomAgentProfileStore(context).upsert(AgentProfileRecord(profileId, "公开 Workflow 验收", "W", providerId,
                config.model, ApiMode.CHAT_COMPLETIONS, "严格只执行当前步骤明确指定的工具与次数，使用实际工具结果，不增加动作。", AgentContextPolicy.CURRENT_CONVERSATION,
                tools, if (device) listOf("device-control") else emptyList(), false, now, now))
            db.conversationDao().insertConversations(listOf(ConversationEntity(conversationId, "公开验收", "", null, null, null, now, now)))
            putOwned(preferences.getValue("xiaoling_room_state").first, roomOwned)
            putOwned(preferences.getValue("xiaoling_ui").first, uiOwned)
            if (device) {
                // long: 普通编辑器没有目标应用字段；限定应用定义由夹具预置，初始空 Run 取消保留，真正被验收的 Run 必须由可见按钮新建。
                val seed = workflowRepo.createWorkflowAndManualRun(workflowName, goals.map(::WorkflowStepDefinitionInput), conversationId, "com.android.settings")
                captureOwnedDefinition(seed.first.id)
                workflowRepo.completeRun(seed.second.run.id, WorkflowRunStatus.CANCELLED, errorMessage = "公开定义预置，不执行此 Run")
                androidLiveStatus("STAGE294_WORKFLOW_SEED workflowId=${seed.first.id} seedRunId=${seed.second.run.id} cancelledWithoutAgent=true")
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                actorStopped = false
                var executionFailure: Throwable? = null
                try {
                    scenario.awaitState { !it.loadingConversationMessages && it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId }
                    openWorkflowPage()
                    if (!device) {
                        rule.onNodeWithContentDescription("新建工作流").performClick()
                        rule.onNode(hasText("名称") and hasSetTextAction()).performTextInput(workflowName)
                        rule.onAllNodes(hasText("Agent 目标") and hasSetTextAction())[0].performTextInput(goals[0])
                        rule.onNodeWithText("添加步骤").performScrollTo().performClick()
                        rule.onAllNodes(hasText("Agent 目标") and hasSetTextAction())[1].performScrollTo().performTextInput(goals[1])
                        rule.onNode(hasText("保存") and hasClickAction()).performClick()
                    }
                    val ready = scenario.awaitState { !it.loadingWorkflows && it.workflows.any { item -> item.name == workflowName } }
                    val definition = ready.workflows.single { it.name == workflowName }
                    check(ownedWorkflowId == null || ownedWorkflowId == definition.id)
                    captureOwnedDefinition(definition.id)
                    assertEquals(goals, definition.steps.map { it.goal })
                    assertEquals(if (device) "com.android.settings" else null, definition.targetAppPackage)
                    val priorRunIds = ready.workflowRuns.map { it.run.id }.toSet()
                    scenario.onActivity { activity -> context.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
                    rule.onNodeWithTag("workflow-item-${definition.id}").performScrollTo()
                    rule.onNode(hasContentDescription("手动运行") and hasAnyAncestor(hasTestTag("workflow-item-${definition.id}"))).assertIsEnabled().performClick()
                    val started = scenario.awaitState { it.workflowRuns.any { detail -> detail.run.workflowId == definition.id && detail.run.id !in priorRunIds } }
                    val workflowRunId = started.workflowRuns.single { it.run.workflowId == definition.id && it.run.id !in priorRunIds }.run.id
                    verifiedWorkflowRunId = workflowRunId
                    androidLiveStatus("STAGE294_WORKFLOW_RUN case=$case workflowRunId=$workflowRunId visibleRunCreated=true")
                    if (device) {
                        val pending = awaitPending(workflowRunId, workflowRepo, agentRepo)
                        val agentId = pending.runId
                        androidLiveStatus("STAGE294_WORKFLOW_CHECKPOINT case=$case phase=pending runId=$agentId approvalId=${pending.id}")
                        // long: 完整 device-control 是生产 Skill 的静态配置约束；测试只批准精确 open_app，任何其他可见审批都失败并在 finally 停止本轮。
                        assertEquals("device.open_app", pending.toolName)
                        assertEquals(mapOf("package_name" to "com.android.settings"), pending.arguments)
                        assertTrue(agentRepo.toolLedger(agentId).results.none { it.toolCallId == pending.toolCallId })
                        val overlay = awaitOverlay(automation)
                        assertTrue(overlay.texts().contains("小灵设备动作审批"))
                        assertTrue(overlay.texts().any { it.contains("device.open_app") })
                        awaitAppWindow(automation, context.packageName)
                        click(overlay, if (case == "device-approve") "批准执行" else "拒绝")
                    }
                    scenario.awaitState(120_000) { !it.sendingMessage && it.runningWorkflowId == null &&
                        it.workflowRuns.any { detail -> detail.run.id == workflowRunId && detail.run.status in setOf(WorkflowRunStatus.COMPLETED, WorkflowRunStatus.FAILED) } }
                    val detail = requireNotNull(workflowRepo.runDetail(workflowRunId))
                    val expected = if (case == "device-deny") WorkflowRunStatus.FAILED else WorkflowRunStatus.COMPLETED
                    assertEquals(expected, detail.run.status)
                    assertTrue(detail.steps.all { it.status == if (case == "device-deny") WorkflowStepStatus.FAILED else WorkflowStepStatus.COMPLETED })
                    val agents = detail.steps.map { requireNotNull(agentRepo.runDetail(requireNotNull(it.agentRunId))) }
                    agents.forEach { agent ->
                        val selected = agent.snapshot.events.mapNotNull { (it.metadata as? RunEventMetadata.AgentProfileSelection)?.profile }.single()
                        assertEquals(profileId, selected.id); assertEquals(config.model, selected.model)
                        val requests = agent.snapshot.events.mapNotNull { it.metadata as? RunEventMetadata.LlmRequest }
                        assertTrue(requests.isNotEmpty()); assertTrue(requests.all { it.model == config.model })
                    }
                    if (!device) {
                        assertEquals(2, agents.size)
                        agents.zip(listOf("app.current_time", "app.get_info")).forEach { (agent, tool) ->
                            assertEquals(AgentRunStatus.COMPLETED, agent.snapshot.run.status)
                            assertEquals(listOf(tool), agent.toolLedger.calls.map { it.toolName })
                            assertEquals(ToolVerificationStatus.PASSED, agent.toolLedger.results.single().verificationStatus)
                        }
                        assertEquals(listOf(requireNotNull(WorkflowStepSnapshotCodec.outputText(detail.steps[0].outputSnapshot))),
                            WorkflowStepSnapshotCodec.decodeInput(detail.steps[1].inputSnapshot).previousOutputs)
                    } else {
                        val agent = agents.single()
                        assertEquals(listOf("device.snapshot", "device.open_app"), agent.toolLedger.calls.map { it.toolName })
                        assertEquals(if (case == "device-deny") ApprovalRequestStatus.DENIED else ApprovalRequestStatus.APPROVED, agent.approvals.single().status)
                        assertEquals(if (case == "device-deny") AgentRunStatus.FAILED else AgentRunStatus.COMPLETED, agent.snapshot.run.status)
                        if (case == "device-deny") {
                            assertTrue(agent.toolLedger.results.none { it.toolName == "device.open_app" })
                            // long: 页面刷新会清空临时 workflowError；拒绝原因必须从本次持久化 Run 复核，不能把瞬时提示消失误判为拒绝未落库。
                            assertTrue(detail.run.errorMessage.orEmpty().contains("工具未获批准"))
                            awaitAppWindow(automation, context.packageName)
                        } else {
                            val result = agent.toolLedger.results.single { it.toolName == "device.open_app" }
                            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus); assertEquals(true, result.executorVerified)
                            val action = requireNotNull(WorkflowStepSnapshotCodec.decodeOutput(detail.steps.single().outputSnapshot)).deviceActionDecisions.single()
                            assertEquals(WorkflowDeviceActionDecisionStatus.VERIFIED, action.status)
                            assertEquals("open_app", action.action); assertEquals("com.android.settings", action.afterPackageName)
                            awaitAppWindow(automation, "com.android.settings")
                        }
                    }
                    // long: 动作后再回小灵只用于查阅已落库的结果；不在审批/执行期间切窗，避免人为撤销真实窗口许可。
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    scenario.awaitState { !it.sendingMessage }
                    openWorkflowPage()
                    rule.onNodeWithTag("workflow-item-${definition.id}").performScrollTo().performClick()
                    // long: 可点击工作流卡片会合并后代语义；结果与动作的独立 tag 仍在真实子树中，必须从未合并树定位而非误判页面缺失。
                    rule.onNodeWithTag("workflow-run-result-$workflowRunId", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    if (case == "device-approve") rule.onNodeWithTag("workflow-device-action-$workflowRunId-1-0", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    if (case == "device-deny") {
                        val denied = rule.onAllNodes(hasText("工具未获批准", substring = true) and
                            hasAnyAncestor(hasTestTag("workflow-item-${definition.id}")), useUnmergedTree = true)
                        assertTrue(denied.fetchSemanticsNodes().isNotEmpty())
                        denied[0].performScrollTo().assertIsDisplayed()
                    }
                    assertEquals(detail, workflowRepo.runDetail(workflowRunId))
                    androidLiveStatus("STAGE294_EVIDENCE WORKFLOW case=$case workflowRunId=$workflowRunId agentRunIds=${agents.joinToString(",") { it.snapshot.run.id }} visibleRun=true actualViewModel=true productionUseCase=true editorCreated=${!device} definitionSeeded=$device status=$expected steps=${detail.steps.size} roomVerified=true visibleResult=true")
                } catch (error: Throwable) {
                    executionFailure = error
                    throw error
                } finally {
                    // long: 停止超时必须保留正文失败的原因；附加异常留下停止失败，同时让外层拒绝清理尚在执行的配置。
                    runCatching {
                        scenario.onActivity { ViewModelProvider(it)[XiaoLingViewModel::class.java].apply { if (uiState.sendingMessage) stopGenerating() } }
                        scenario.awaitState(30_000) { !it.sendingMessage && it.runningWorkflowId == null }
                        actorStopped = true
                    }.exceptionOrNull()?.let { failure -> executionFailure?.addSuppressed(failure) ?: throw failure }
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            // long: 恢复失败附加到最初的验收异常，避免 finally 覆盖真实模型、页面或停止阶段的失败证据。
            runCatching {
                // long: 停止超时不能假定执行者已退出；保留临时配置/定义及当前选择，防止仍运行的协程读到被清理或恢复后的另一份配置。
                check(actorStopped) { "未确认 Workflow 执行者已停止，临时配置与定义保留，拒绝继续清理" }
                val errors = mutableListOf<Throwable>()
                runCatching { db.withTransaction {
                    // long: 名称只能用于页面定位；稳定 ID 与完整定义/步骤快照同时匹配才允许删除，保留同期修改目标或步骤的定义。
                    ownedWorkflowId?.let { workflowId ->
                        val current = db.workflowDao().getWorkflow(workflowId)
                        val currentSteps = db.workflowDao().getWorkflowStepDefinitions(workflowId)
                        val before = requireNotNull(ownedDefinitionSnapshot) { "定义快照尚未确认，保留现场" }
                        check(if (current == null) currentSteps.isEmpty() else (current to currentSteps) == before) { "本轮定义或步骤被同期修改，保留现场" }
                        db.workflowDao().deleteWorkflowStepDefinitions(workflowId)
                        db.openHelper.writableDatabase.execSQL("DELETE FROM workflows WHERE id = ?", arrayOf(workflowId))
                    } ?: check(db.workflowDao().listWorkflows().none { it.name == workflowName }) {
                        "页面产生了尚未确认归属的定义，保留现场，拒绝按名称删除"
                    }
                    // long: Workflow/Agent Run 与持久步骤没有定义/会话删除级联；保留成功与失败审计，仅删除本轮 UUID 会话正文和配置。
                    val ids = db.conversationDao().getMessagesByConversationId(conversationId).map { it.id }
                    if (ids.isNotEmpty()) db.conversationDao().deleteMessageParts(ids)
                    db.conversationDao().deleteMessagesByConversationIds(listOf(conversationId))
                    db.conversationDao().deleteConversations(listOf(conversationId))
                    db.agentProfileDao().delete(profileId)
                    db.openHelper.writableDatabase.execSQL("DELETE FROM providers WHERE id = ?", arrayOf(providerId))
                } }.exceptionOrNull()?.let(errors::add)
                preferences.forEach { (name, snapshot) ->
                    val (prefs, original, existed) = snapshot
                    val owned = if (name == "xiaoling_room_state") roomOwned else uiOwned
                    owned.forEach { (key, value) -> runCatching {
                        check(prefs.all[key] == value || prefs.all[key] == original[key]) { "偏好选择已被同期操作修改：$key" }
                        val edit = prefs.edit()
                        when (val before = original[key]) {
                            null -> edit.remove(key)
                            is String -> edit.putString(key, before)
                            is Boolean -> edit.putBoolean(key, before)
                            else -> error("未知偏好类型")
                        }
                        check(edit.commit())
                    }.exceptionOrNull()?.let(errors::add) }
                    runCatching { check(prefs.all == original); if (!existed && prefs.all.isEmpty()) check(context.deleteSharedPreferences(name)) }
                        .exceptionOrNull()?.let(errors::add)
                    androidLiveStatus("STAGE294_PREF_VALUES name=$name before=${androidLivePreferenceDigest(original)} after=${androidLivePreferenceDigest(prefs.all)}")
                }
                runCatching { check(providers == db.providerDao().getAll().toSet()); check(profiles == db.agentProfileDao().list().toSet()); check(undoHash() == originalUndo) }
                    .exceptionOrNull()?.let(errors::add)
                runCatching { check(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == authorization) }
                    .exceptionOrNull()?.let(errors::add)
                runCatching { originalAudit.forEach { (table, before) -> val after = rows(db, table)
                    check(before.all { (key, value) -> after[key] == value }) { "旧审计记录发生变化：$table" } } }
                    .exceptionOrNull()?.let(errors::add)
                verifiedWorkflowRunId?.let { runId -> runCatching { check(workflowRepo.runDetail(runId) != null) }.exceptionOrNull()?.let(errors::add) }
                if (errors.isNotEmpty()) throw IllegalStateException("Workflow 专项恢复失败").also { error -> errors.forEach(error::addSuppressed) }
                androidLiveStatus("STAGE294_EVIDENCE WORKFLOW_RESTORE originalProviderAndProfiles=true preferencesEqual=true oldAuditRowsEqual=true memoryUndo=$originalUndo authorizationSelectionEqual=true temporaryDefinitionsRemoved=true runAuditRetained=true")
            }.exceptionOrNull()?.let { failure -> primaryFailure?.addSuppressed(failure) ?: throw failure }
        }
    }

    private fun assertZero(db: XiaoLingDatabase, sql: String, reason: String) = db.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst() && it.getLong(0) == 0L) { reason }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rows(db: XiaoLingDatabase, table: String): Map<Long, String> = buildMap {
        db.openHelper.readableDatabase.query("SELECT rowid, * FROM $table").use { cursor -> while (cursor.moveToNext()) {
            val packed = (1 until cursor.columnCount).joinToString("|") { index ->
                val value = when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> "null"
                    Cursor.FIELD_TYPE_BLOB -> hash(cursor.getBlob(index))
                    else -> cursor.getString(index)
                }
                "${cursor.getType(index)}:${value.length}:$value"
            }
            put(cursor.getLong(0), hash(packed.toByteArray()))
        } }
    }
    private fun putOwned(prefs: android.content.SharedPreferences, values: Map<String, Any>) {
        val edit = prefs.edit(); values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); else -> error("未知偏好类型")
        } }; check(edit.commit())
    }
    private fun openWorkflowPage() {
        rule.onNodeWithTag("bottom_tab_settings").performClick()
        if (rule.onAllNodesWithContentDescription("新建工作流").fetchSemanticsNodes().isEmpty())
            rule.onNodeWithText("工作流").performScrollTo().performClick()
        rule.onNodeWithContentDescription("新建工作流").assertIsDisplayed()
    }
    private fun ActivityScenario<MainActivity>.awaitState(timeoutMs: Long = 20_000, predicate: (XiaoLingUiState) -> Boolean): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: XiaoLingUiState? = null
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle(); onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(requireNotNull(latest))) return requireNotNull(latest)
            Thread.sleep(50)
        }
        androidLiveStatus("STAGE294_WORKFLOW_STATE loading=${latest?.loadingWorkflows} running=${latest?.runningWorkflowId} sending=${latest?.sendingMessage} error=${latest?.workflowError}")
        error("正式 Workflow 状态超时")
    }
    private fun overlayRoot(automation: UiAutomation): AccessibilityNodeInfo? = automation.windows
        .singleOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() == "XiaoLingDeviceActionApproval" }?.root
    private suspend fun awaitOverlay(automation: UiAutomation) = withTimeout(15_000) {
        while (true) { overlayRoot(automation)?.let { return@withTimeout it }; delay(100) }; error("unreachable")
    }
    private suspend fun awaitPending(workflowRunId: String, workflows: RoomWorkflowRepository, agents: RoomAgentRunRepository): ApprovalRequestRecord = withTimeout(120_000) {
        // long: Runtime 的 WAITING_APPROVAL 快照先于 Gate 创建审批；等待本 Workflow 步骤实际关联的 Room 请求，不能把 UI 状态当成落库完成。
        while (true) {
            rule.waitForIdle()
            val step = requireNotNull(workflows.runDetail(workflowRunId)).steps.single()
            step.agentRunId?.let { runId ->
                val detail = requireNotNull(agents.runDetail(runId))
                detail.approvals.singleOrNull { it.status == ApprovalRequestStatus.PENDING }?.let { return@withTimeout it }
                check(detail.snapshot.run.status !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED,
                    AgentRunStatus.BLOCKED, AgentRunStatus.BUDGET_EXHAUSTED)) { "本 Workflow 未进入可见审批：${detail.snapshot.run.errorMessage}" }
            }
            delay(100)
        }
        error("unreachable")
    }
    private suspend fun awaitAppWindow(automation: UiAutomation, expected: String) = withTimeout(10_000) {
        // long: 浮层退出时窗口清单可能短暂没有 active 应用；等待唯一 active/focused 应用匹配目标包，不能把空列表当作已打开或未打开，也不把一次匹配称为持续稳定观察。
        while (true) {
            val window = automation.windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            if (window?.root?.packageName?.toString() == expected) {
                androidLiveStatus("STAGE294_WORKFLOW_CHECKPOINT phase=window package=$expected active=${window.isActive} focused=${window.isFocused}")
                return@withTimeout
            }
            delay(100)
        }
    }
    private fun AccessibilityNodeInfo.texts(): List<String> = buildList {
        text?.toString()?.let(::add); for (index in 0 until childCount) getChild(index)?.let { addAll(it.texts()) }
    }
    private fun click(root: AccessibilityNodeInfo, label: String) {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.isClickable && node.isEnabled && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        check(requireNotNull(find(root)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private suspend fun rebind(context: Context, automation: UiAutomation, original: String) {
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val component = ComponentName(context, XiaoLingAccessibilityService::class.java)
        val parts = original.split(':'); check(parts.any { ComponentName.unflattenFromString(it) == component }) { "原授权不存在" }
        val native = AndroidDeviceAccessibilityGateway(context)
        if (!native.isServiceConnected()) {
            val reduced = parts.filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.getString(context.contentResolver, key) == original); check(Settings.Secure.putString(context.contentResolver, key, reduced))
                try { delay(300) } finally { check(Settings.Secure.getString(context.contentResolver, key) == reduced); check(Settings.Secure.putString(context.contentResolver, key, original)) }
            } finally { automation.dropShellPermissionIdentity() }
        }
        withTimeout(15_000) { while (!native.isServiceAuthorized() || !native.isServiceConnected()) delay(100) }
    }
}
