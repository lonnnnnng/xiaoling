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
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
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
    private var acceptanceStage = 294

    @Test fun actualWorkflowPageUsesRealModelAndPersistsResult() = runBlocking {
        val case = requireNotNull(InstrumentationRegistry.getArguments().getString("stage294Case"))
        runAcceptance(294, case)
    }

    @Test fun actualWorkflowSearchUsesRealModelAndPersistsVerifiedGoal() = runBlocking {
        runAcceptance(295, "device-search")
    }

    @Test fun actualWorkflowScrollUsesRealModelAndPersistsVerifiedGoal() = runBlocking {
        runAcceptance(297, "device-scroll")
    }

    @Test fun actualWorkflowDirectionalScrollUsesRealModelAndPreservesFailure() = runBlocking {
        runAcceptance(298, requireNotNull(InstrumentationRegistry.getArguments().getString("stage298Case")))
    }

    @Test fun actualEditorConfirmsScopeAndContractThroughRealModel() = runBlocking {
        runAcceptance(300, requireNotNull(InstrumentationRegistry.getArguments().getString("stage300Case")))
    }

    private suspend fun runAcceptance(stage: Int, case: String) {
        acceptanceStage = stage
        val config = androidLiveProvider(stage)
        require(when (stage) {
            295 -> case == "device-search"
            297 -> case == "device-scroll"
            298 -> case in setOf("device-scroll-return", "device-scroll-boundary")
            300 -> case in setOf("device-editor-approve", "device-editor-deny")
            else -> stage == 294 && case in setOf("create-readonly", "device-approve", "device-deny")
        })
        val search = case == "device-search"
        val scroll = case.startsWith("device-scroll")
        val roundTrip = case == "device-scroll-return"
        val boundary = case == "device-scroll-boundary"
        val editor = case.startsWith("device-editor-")
        val denied = case == "device-deny" || case == "device-editor-deny"
        val contracted = search || scroll || editor
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
        // long: 失败专项会保留预算耗尽或阻断终态；与生产终态定义一致判断执行是否结束，不能要求删掉失败审计才能复验。
        val terminalAgentStates = AgentRunStatus.entries.filter { it.isTerminal }.joinToString(",") { "'${it.name}'" }
        assertZero(db, "SELECT COUNT(*) FROM agent_runs WHERE status NOT IN ($terminalAgentStates)", "已有未结束 Agent Run")
        assertZero(db, "SELECT COUNT(*) FROM workflow_runs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'BLOCKED')", "已有未结束 Workflow Run")
        assertZero(db, "SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'", "已有待审批记录")
        val keyAlias = "xiaoling_api_key"
        fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val originalKeyPresent = keyStore().containsAlias(keyAlias)
        val auditTables = listOf("agent_runs", "agent_steps", "approval_requests", "run_events", "agent_context_snapshots",
            "agent_context_compressions", "agent_tool_calls", "agent_tool_results", "workflow_runs", "workflow_steps")
        val originalAudit = auditTables.associateWith { rows(db, it) }
        val providers = db.providerDao().getAll().toSet()
        val profiles = db.agentProfileDao().list().toSet()
        val keyConsumers = listOf("xiaoling", "xiaoling_mcp").associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).let { it to it.all.toMap() }
        }
        // long: 新装应用没有 Provider 密钥；仅明确契约专项且旧审计与 Provider 密文全空时允许本轮临时创建，不能修补已有数据的丢失密钥。
        check(originalKeyPresent || (contracted && originalAudit.values.all { it.isEmpty() } &&
            providers.all { it.apiKeyIv.isEmpty() && it.apiKeyCiphertext.isEmpty() } &&
            keyConsumers.values.all { (_, values) -> values.isEmpty() })) { "既有 Provider 密钥不存在，且不满足新安装空数据门禁" }
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
        val providerId = "stage$stage-provider-$id"
        val profileId = "stage$stage-profile-$id"
        val conversationId = "conversation-stage$stage-$id"
        val workflowName = "公开工作流验收-${id.take(8)}"
        val roomOwned = mapOf<String, Any>("providers_migrated" to true, "conversations_migrated" to true,
            "selected_provider_id" to providerId, "selected_agent_profile_id" to profileId, "selected_conversation_id" to conversationId)
        val uiOwned = buildMap<String, Any> {
            put("memory_candidates_enabled", false)
            if (device) { put("device_agent_enabled", true); put("device_action_auto_approval_enabled", false) }
        }
        val tools = if (device) listOf("device.snapshot", "device.open_app", "device.back", "device.home", "device.tap_ref", "device.type_text", "device.swipe")
            else listOf("app.current_time", "app.get_info")
        val marker = UUID.randomUUID().toString().replace("-", "").take(8).map { ('a'.code + it.digitToInt(16)).toChar() }.joinToString("")
        // long: 每步最多三调用，保留默认预算；节点引用只能来自本步新快照，不能把前步或历史 ref 带入下一个 Agent Run。
        val actions = if (editor) listOf("device.open_app") else if (roundTrip) listOf("device.open_app", "device.swipe", "device.swipe")
            else if (scroll) listOf("device.open_app", "device.swipe") else listOf("device.open_app", "device.tap_ref", "device.type_text")
        val requiredTools = actions.flatMap { listOf("device.snapshot", it, "device.snapshot") }
        val contractGoal = when {
            editor -> "打开系统设置并通过后置页面观察确认完成"
            roundTrip -> "打开系统设置并向上、向下各滚动一次返回顶部"
            boundary -> "打开系统设置并尝试在顶部向下滚动，不能将无移动判为完成"
            scroll -> "打开系统设置并向上滚动设置列表一次"
            else -> "在系统设置搜索框输入本轮公开测试文本"
        }
        val contract = if (contracted) WorkflowGoalVerificationContract(contractGoal,
            WorkflowGoalVerificationSpec(requiredTools, "com.android.settings", actions.map { listOf("device.snapshot", it, "device.snapshot") })) else null
        val goals = if (search) listOf(
            "本步严格按顺序调用 device.snapshot、device.open_app(package_name=com.android.settings)、device.snapshot，各一次。只打开系统设置首页并确认后结束，不点击、不输入、不调用其他工具。",
            "本步必须按顺序调用 device.snapshot、device.tap_ref、device.snapshot，各一次。根据本步快照点击设置首页的搜索设置入口。文字写着搜索设置的节点可能 actions 为空，此时沿 parent_index 找到最近 actions 包含 tap 的父节点，使用该父节点在本步快照中的 ref；其子树只能包含搜索标签，不选择包含其他设置项目的宽容器。未实际执行 device.tap_ref 前禁止 complete。确认搜索页后结束，不输入、不调用其他工具。",
            // long: 随机公开文本是输入数据而非让模型创作的内容；用唯一 JSON 参数块避免改写字母，仍逐字核对模型实际提出的 text 哈希。
            "本步的输入动作尚未执行，前序步骤只负责打开搜索页。必须先调用 device.snapshot，然后 device.type_text，最后 device.snapshot，各一次。" +
                "向本步快照中的可编辑搜索框输入下面 JSON 的 text 值，原样复制每个字母，不翻译、不重新生成、不加空格或引号。" +
                "snapshot_id 和 ref 均来自本步快照。未实际输入并精确回读之前禁止 complete；只读快照不满足目标，不选择搜索结果、不调用其他工具。\n" +
                "本次已确认的输入参数数据：${JSONObject().put("text", marker)}",
        ) else if (editor) listOf(
            "本步严格按顺序调用 device.snapshot、device.open_app(package_name=com.android.settings)、device.snapshot，各一次。只打开系统设置首页并复查后结束；完成标准明确需要最后一次观察，禁止在动作后直接 complete，不点击、不输入、不滚动、不调用其他工具。",
        ) else if (scroll) listOf(
            "本步严格按顺序调用 device.snapshot、device.open_app(package_name=com.android.settings)、device.snapshot，各一次。只打开系统设置首页并确认后结束，不点击、不滚动、不调用其他工具。",
            // long: 正式系统列表必须由模型选择本步真实 SWIPE ref；不使用半屏 Probe 或坐标注入来绕过共同锚点的生产验证。
            "本步必须按顺序调用 device.snapshot、device.swipe、device.snapshot，各一次。根据本步快照选择 actions 包含 swipe 的设置主列表节点，snapshot_id 和 ref 必须来自本步快照，direction 必须为 ${if (boundary) "down" else "up"}。只滚动一次，未实际滚动并完成后置观察之前禁止 complete；不点击、不输入、不打开应用、不调用其他工具。" +
                if (boundary) "本轮明确请求尝试向下滚动；即使当前已在列表顶端，也提出一次 device.swipe(direction=down)，由真实执行器判定不可滚动，不得把没有移动报告为完成。" else "",
        ) + (if (roundTrip) listOf(
            // long: 返回步骤必须自己重新观察；上一步旧 ref 不能因同名 swipe 而跨 Agent Run 继续使用。
            "本步严格按顺序调用 device.snapshot、device.swipe、device.snapshot，各一次。选择本步快照中 actions 含 swipe 的设置主列表，使用本步 snapshot_id/ref，direction 必须为 down。只向下滚动一次返回先前的顶部内容，未通过后置验证前禁止 complete；不点击、不输入、不打开应用，不执行其他动作。",
        ) else emptyList()) else if (device) listOf("先调用 device.snapshot，再仅调用一次 device.open_app，package_name 必须为 com.android.settings。目标是打开系统设置首页。依据工具后置验证完成后直接结束，不调用其他工具。")
            else listOf("只调用一次 app.current_time 获取当前时间，使用实际结果总结后结束。", "只调用一次 app.get_info 获取应用版本，使用本步实际结果总结后结束。")
        var ownedWorkflowId: String? = null
        var ownedDefinitionSnapshot: Pair<WorkflowEntity, List<WorkflowStepDefinitionEntity>>? = null
        suspend fun captureOwnedDefinition(workflowId: String) = db.withTransaction {
            check(ownedWorkflowId == null || ownedWorkflowId == workflowId) { "页面定义身份发生漂移" }
            ownedWorkflowId = workflowId
            val snapshot = requireNotNull(db.workflowDao().getWorkflow(workflowId)) to db.workflowDao().getWorkflowStepDefinitions(workflowId)
            check(ownedDefinitionSnapshot == null || ownedDefinitionSnapshot == snapshot) { "本轮定义在页面回读前被修改，保留现场" }
            ownedDefinitionSnapshot = snapshot
        }
        var verifiedWorkflowRunId: String? = null
        var actorStopped = true
        var primaryFailure: Throwable? = null
        val keyOwnershipProbe = "公开密钥归属-$id"
        var ownedKeyProbe: EncryptedApiKey? = null
        var ownedKeyCreatedAt: Long? = null
        androidLiveStatus("STAGE${acceptanceStage}_EVIDENCE WORKFLOW_SCOPE businessContentEmpty=true oldAuditRows=${originalAudit.values.sumOf { it.size }} case=$case definitionSeeded=${device && !editor}")
        if (contracted) preferences.forEach { (name, snapshot) ->
            androidLiveStatus("STAGE${stage}_PREF_BASELINE name=$name digest=${androidLivePreferenceDigest(snapshot.second)}")
        }
        try {
            if (!originalKeyPresent) {
                // long: 在写入真实配置前用公开随机探针建立内存归属；恢复时必须同时匹配创建时间与解密结果，不能删除同期替换的密钥。
                check(!keyStore().containsAlias(keyAlias)) { "Provider 密钥被同期创建，拒绝取得清理归属" }
                ownedKeyProbe = ApiKeyCipher().encrypt(keyOwnershipProbe)
                ownedKeyCreatedAt = requireNotNull(keyStore().getCreationDate(keyAlias)).time
            }
            if (device) rebind(context, automation, requireNotNull(authorization))
            if (contracted) {
                // long: 设置任务会复用旧页面或滚动位置；只结束两个公开目标窗口以从根页启动，不清数据或修改系统设置。
                listOf("com.android.settings.intelligence", "com.android.settings").forEach { packageName ->
                    automation.executeShellCommand("am force-stop $packageName").use { descriptor ->
                        java.io.FileInputStream(descriptor.fileDescriptor).use { stream -> check(stream.readBytes().isEmpty()) { "目标窗口重置失败" } }
                    }
                }
                androidLiveStatus("STAGE${stage}_WORKFLOW_SETUP targetTasksReset=true appDataCleared=false")
            }
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
            if (device && !editor) {
                // long: 历史专项继续保留预置定义口径；编辑器专项必须经正式页面确认边界，不能用种子 Run 替代产品入口。
                val seed = workflowRepo.createWorkflowAndManualRun(workflowName, goals.map(::WorkflowStepDefinitionInput), conversationId, "com.android.settings", contract)
                captureOwnedDefinition(seed.first.id)
                workflowRepo.completeRun(seed.second.run.id, WorkflowRunStatus.CANCELLED, errorMessage = "公开定义预置，不执行此 Run")
                androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_SEED workflowId=${seed.first.id} seedRunId=${seed.second.run.id} cancelledWithoutAgent=true")
            }
            runWorkflowPage(stage, case, context, automation, config, workflowRepo, agentRepo, workflowName,
                conversationId, profileId, goals, marker, contract, actions, requiredTools,
                captureDefinition = { captureOwnedDefinition(it) }, recordRun = { verifiedWorkflowRunId = it },
                markActorRunning = { actorStopped = false }, markActorStopped = { actorStopped = true })
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            // long: 恢复失败附加到最初的验收异常，避免 finally 覆盖真实模型、页面或停止阶段的失败证据。
            runRecoveryGuarded {
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
                    androidLiveStatus("STAGE${acceptanceStage}_PREF_VALUES name=$name before=${androidLivePreferenceDigest(original)} after=${androidLivePreferenceDigest(prefs.all)}")
                }
                runCatching { check(providers == db.providerDao().getAll().toSet()); check(profiles == db.agentProfileDao().list().toSet()); check(undoHash() == originalUndo) }
                    .exceptionOrNull()?.let(errors::add)
                runCatching { check(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == authorization) }
                    .exceptionOrNull()?.let(errors::add)
                runCatching { originalAudit.forEach { (table, before) -> val after = rows(db, table)
                    check(before.all { (key, value) -> after[key] == value }) { "旧审计记录发生变化：$table" } } }
                    .exceptionOrNull()?.let(errors::add)
                // long: MCP token 与旧版 Provider 也共用 alias；它们出现同期保存时保留密钥，不能只凭本轮探针就认定没有其他消费者。
                runCatching { keyConsumers.forEach { (name, snapshot) ->
                    check(snapshot.first.all == snapshot.second) { "密钥消费者配置发生同期变化：$name" }
                } }.exceptionOrNull()?.let(errors::add)
                verifiedWorkflowRunId?.let { runId -> runCatching { check(workflowRepo.runDetail(runId) != null) }.exceptionOrNull()?.let(errors::add) }
                if (errors.isNotEmpty()) throw IllegalStateException("Workflow 专项恢复失败").also { error -> errors.forEach(error::addSuppressed) }
                if (!originalKeyPresent) {
                    // long: 只有执行停止、配置集合与全部保护项恢复后才移除本轮密钥；归属不明或恢复失败时保留现场，审计 Run 不携带 Provider 密文。
                    val store = keyStore()
                    if (store.containsAlias(keyAlias)) {
                        val probe = requireNotNull(ownedKeyProbe) { "临时密钥归属未建立，保留现场" }
                        check(store.getCreationDate(keyAlias)?.time == requireNotNull(ownedKeyCreatedAt) &&
                            ApiKeyCipher().decrypt(probe.iv, probe.ciphertext) == keyOwnershipProbe) { "临时密钥被同期替换，保留现场" }
                        store.deleteEntry(keyAlias)
                    } else check(ownedKeyProbe == null) { "本轮临时密钥被同期移除，保留现场" }
                    check(!keyStore().containsAlias(keyAlias))
                } else check(keyStore().containsAlias(keyAlias)) { "原 Provider 密钥已丢失" }
                if (contracted) androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_KEY aliasStateRestored=true originalAliasPresent=$originalKeyPresent temporaryCreated=${ownedKeyProbe != null}")
                androidLiveStatus("STAGE${acceptanceStage}_EVIDENCE WORKFLOW_RESTORE originalProviderAndProfiles=true preferencesEqual=true oldAuditRowsEqual=true memoryUndo=$originalUndo authorizationSelectionEqual=true temporaryDefinitionsRemoved=true runAuditRetained=true")
            }.exceptionOrNull()?.let { failure -> primaryFailure?.addSuppressed(failure) ?: throw failure }
        }
    }

    // long: 原生崩溃会跳过 finally；将有界恢复块移出巨型入口的内联字节码，仍把恢复异常附加到原始失败而不覆盖它。
    private suspend fun runRecoveryGuarded(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (error: Throwable) {
        Result.failure(error)
    }

    // long: 页面执行与配置恢复分别生成较小协程状态机；原生崩溃和提供方异常都必须保留独立恢复证据，不能依赖巨型内联 use 的跳转。
    private suspend fun runWorkflowPage(stage: Int, case: String, context: Context, automation: UiAutomation,
        config: ProviderRequestConfig, workflowRepo: RoomWorkflowRepository, agentRepo: RoomAgentRunRepository,
        workflowName: String, conversationId: String, profileId: String, goals: List<String>, marker: String,
        contract: WorkflowGoalVerificationContract?, actions: List<String>, requiredTools: List<String>,
        captureDefinition: suspend (String) -> Unit, recordRun: (String) -> Unit,
        markActorRunning: () -> Unit, markActorStopped: () -> Unit) {
        val search = case == "device-search"
        val scroll = case.startsWith("device-scroll")
        val roundTrip = case == "device-scroll-return"
        val boundary = case == "device-scroll-boundary"
        val editor = case.startsWith("device-editor-")
        val denied = case == "device-deny" || case == "device-editor-deny"
        val contracted = search || scroll || editor
        val device = case != "create-readonly"
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            markActorRunning()
            var executionFailure: Throwable? = null
            try {
                scenario.awaitState { !it.loadingConversationMessages && it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId }
                // long: 页面入口点击必须发生在宿主取得焦点且键盘收起后；不能让上一窗口的输入法遮住真实底栏触点。
                awaitAppWindow(automation, context.packageName)
                scenario.onActivity { activity -> context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
                withTimeout(10_000) {
                    while (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) delay(100)
                }
                openWorkflowPage()
                if (!device || editor) createWorkflowInEditor(workflowName, goals, contract, editor)
                val ready = scenario.awaitState { !it.loadingWorkflows && it.workflows.any { item -> item.name == workflowName } }
                val definition = ready.workflows.single { it.name == workflowName }
                captureDefinition(definition.id)
                assertEquals(goals, definition.steps.map { it.goal })
                assertEquals(if (device) "com.android.settings" else null, definition.targetAppPackage)
                assertEquals(contract, definition.goalVerificationContract)
                if (editor) {
                    assertTrue(ready.workflowRuns.none { it.run.workflowId == definition.id })
                    // long: 编辑回填从正式 Projection 读取，不调用 VM 保存接口；取消后仍须与已落库的完整定义一致。
                    rule.onNode(hasContentDescription("编辑工作流") and hasAnyAncestor(hasTestTag("workflow-item-${definition.id}"))).performClick()
                    rule.onNodeWithTag("workflow-editor-app").assertTextContains("限定应用：系统设置")
                    rule.onNodeWithTag("workflow-editor-verify").assertIsOn()
                    rule.onNodeWithTag("workflow-editor-final-app").assertIsOn()
                    rule.onNodeWithTag("workflow-editor-completion-goal").assertTextContains(requireNotNull(contract).sourceGoal)
                    rule.onNodeWithTag("workflow-editor-criterion-0").performScrollTo().assertTextContains("完成标准：打开限定应用并复查页面")
                    rule.onNodeWithText("取消").performClick()
                }
                val priorRunIds = ready.workflowRuns.map { it.run.id }.toSet()
                scenario.onActivity { activity -> context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
                rule.onNodeWithTag("workflow-item-${definition.id}").performScrollTo()
                rule.onNode(hasContentDescription("手动运行") and hasAnyAncestor(hasTestTag("workflow-item-${definition.id}"))).assertIsEnabled().performClick()
                val started = scenario.awaitState { it.workflowRuns.any { detail -> detail.run.workflowId == definition.id && detail.run.id !in priorRunIds } }
                val workflowRunId = started.workflowRuns.single { it.run.workflowId == definition.id && it.run.id !in priorRunIds }.run.id
                recordRun(workflowRunId)
                androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_RUN case=$case workflowRunId=$workflowRunId visibleRunCreated=true")
                if (contracted && !editor) {
                    // long: 多次挂起的审批体独立生成协程状态机，避免巨型内联方法在本机 ART 恢复时触发非法指令；审批断言不放宽。
                    for (index in actions.indices) if (actions[index] != "device.swipe")
                        approveSearchAction(context, automation, workflowRepo, agentRepo, workflowRunId, index + 1, actions[index], marker)
                } else if (device) {
                    val pending = awaitPending(workflowRunId, workflowRepo, agentRepo)
                    val agentId = pending.runId
                    androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_CHECKPOINT case=$case phase=pending runId=$agentId approvalId=${pending.id}")
                    // long: 完整 device-control 是生产 Skill 的静态配置约束；测试只批准精确 open_app，任何其他可见审批都失败并在 finally 停止本轮。
                    assertEquals("device.open_app", pending.toolName)
                    assertEquals(mapOf("package_name" to "com.android.settings"), pending.arguments)
                    assertTrue(agentRepo.toolLedger(agentId).results.none { it.toolCallId == pending.toolCallId })
                    val overlay = awaitOverlay(automation)
                    assertTrue(overlay.texts().contains("小灵设备动作审批"))
                    assertTrue(overlay.texts().any { it.contains("device.open_app") })
                    awaitAppWindow(automation, context.packageName)
                    click(overlay, if (!denied) "批准执行" else "拒绝")
                }
                scenario.awaitState(120_000) { !it.sendingMessage && it.runningWorkflowId == null &&
                    it.workflowRuns.any { detail -> detail.run.id == workflowRunId && detail.run.status in setOf(WorkflowRunStatus.COMPLETED, WorkflowRunStatus.FAILED) } }
                val detail = requireNotNull(workflowRepo.runDetail(workflowRunId))
                val expected = if (denied || boundary) WorkflowRunStatus.FAILED else WorkflowRunStatus.COMPLETED
                assertEquals(expected, detail.run.status)
                if (boundary) assertEquals(listOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.FAILED), detail.steps.map { it.status })
                else assertTrue(detail.steps.all { it.status == if (denied) WorkflowStepStatus.FAILED else WorkflowStepStatus.COMPLETED })
                val agents = mutableListOf<AgentRunDetailRecord>()
                for (step in detail.steps) agents.add(requireNotNull(agentRepo.runDetail(requireNotNull(step.agentRunId))))
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
                } else if (boundary) {
                    assertScrollBoundary(detail, agents, context, automation, contract)
                } else if (contracted && !denied) {
                    assertEquals(actions.size, agents.size)
                    agents.forEachIndexed { index, agent ->
                        assertEquals(AgentRunStatus.COMPLETED, agent.snapshot.run.status)
                        assertEquals(listOf("device.snapshot", actions[index], "device.snapshot"), agent.toolLedger.calls.map { it.toolName })
                        assertEquals(3, agent.toolLedger.results.size)
                        assertTrue(agent.toolLedger.results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
                        val call = agent.toolLedger.calls.single { it.toolName == actions[index] }
                        if (actions[index] == "device.swipe") {
                            // long: SAFE 滚动不能被夹具人工审批替代；要求本步零审批并消费生产同窗方向验证结果。
                            assertTrue(agent.approvals.isEmpty())
                            assertScrollEvidence(agent, if (roundTrip && index == 2) "down" else "up")
                        } else {
                            val approval = agent.approvals.single()
                            assertEquals(call.id, approval.toolCallId)
                            assertEquals(ApprovalRequestStatus.APPROVED, approval.status)
                            assertEquals(call.arguments, approval.arguments)
                        }
                        assertEquals(true, agent.toolLedger.results.single { it.toolCallId == call.id }.executorVerified)
                        val input = WorkflowStepSnapshotCodec.decodeInput(detail.steps[index].inputSnapshot)
                        assertEquals(contract, input.goalVerificationContract)
                        val output = requireNotNull(WorkflowStepSnapshotCodec.decodeOutput(detail.steps[index].outputSnapshot))
                        assertEquals(listOf("device.snapshot", actions[index], "device.snapshot"), output.verifiedToolNames)
                        val action = output.deviceActionDecisions.single()
                        assertEquals(WorkflowDeviceActionDecisionStatus.VERIFIED, action.status)
                        // long: Workflow 的持久动作协议保留工具后缀 tap_ref；底层节点 Executor 的 tap 收据不能替代该协议名。
                        assertEquals(actions[index].removePrefix("device."), action.action)
                        assertTrue(DeviceActionPolicy.areAllowedInAppFlow("com.android.settings", action.afterPackageName))
                    }
                    if (search) assertSearchPrivacy(agents.last(), marker)
                    val decision = requireNotNull(detail.run.goalVerificationDecision)
                    assertEquals(WorkflowGoalVerificationStatus.VERIFIED, decision.status)
                    assertEquals(WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED, decision.reason)
                    assertEquals(requiredTools, decision.requiredToolNames)
                    assertEquals(requiredTools, decision.matchedRequiredToolNames)
                    assertEquals(actions.size, decision.completedStepCount); assertEquals(actions.size, decision.totalStepCount)
                    assertTrue(DeviceActionPolicy.areAllowedInAppFlow("com.android.settings", decision.actualFinalPackageName))
                    awaitSettingsWindow(automation)
                    // long: 审批全部结束后才做独立真实窗口回读；不能在 PENDING 时 capture 替换 Runtime 正在使用的引用。
                    val capture = DeviceObservationComponents.controller(context).capture() as? DeviceSnapshotCapture.Success
                    assertNotNull("最终设置窗口读取失败", capture)
                    val snapshot = requireNotNull(capture).snapshot
                    assertTrue(DeviceActionPolicy.areAllowedInAppFlow("com.android.settings", snapshot.packageName))
                    if (search) {
                        assertTrue("搜索框未精确回读公开测试文本", snapshot.nodes.any { it.text == marker && DeviceNodeAction.TYPE_TEXT in it.actions })
                        androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_SEARCH goalStatus=${decision.status} goalReason=${decision.reason} steps=3 toolCalls=9 approvals=3 exactReadBack=true privacyPassed=true workflowRunId=$workflowRunId textSha256=${hash(marker.toByteArray())} textLength=${marker.length} actualPackage=${snapshot.packageName} contractSeeded=true")
                    } else if (roundTrip) {
                        assertTrue("最终设置窗口没有可滚动列表", snapshot.nodes.any { DeviceNodeAction.SWIPE in it.actions })
                        val first = JSONObject(agents.first().toolLedger.results.last().content)
                        val last = JSONObject(agents.last().toolLedger.results.last().content)
                        assertTrue("往返后顶部内容不同", visibleSnapshotContent(first) == visibleSnapshotContent(last))
                        androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_SCROLL_RETURN goalStatus=${decision.status} goalReason=${decision.reason} steps=3 toolCalls=9 approvals=1 directions=up,down executorVerified=true noSwipeApproval=true returnedContentEqual=true workflowRunId=$workflowRunId")
                    } else if (editor) {
                        androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_EDITOR_APPROVE goalStatus=${decision.status} goalReason=${decision.reason} calls=3 approvals=1 executorVerified=true finalPackageChecked=true workflowRunId=$workflowRunId")
                    } else {
                        assertTrue("最终设置窗口没有可滚动列表", snapshot.nodes.any { DeviceNodeAction.SWIPE in it.actions })
                        androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_SCROLL goalStatus=${decision.status} goalReason=${decision.reason} steps=2 toolCalls=6 approvals=1 direction=up executorVerified=true noSwipeApproval=true snapshotContentChanged=true actionSummaryOnly=true workflowRunId=$workflowRunId actualPackage=${snapshot.packageName} contractSeeded=true")
                    }
                } else {
                    val agent = agents.single()
                    assertEquals(listOf("device.snapshot", "device.open_app"), agent.toolLedger.calls.map { it.toolName })
                    assertEquals(if (denied) ApprovalRequestStatus.DENIED else ApprovalRequestStatus.APPROVED, agent.approvals.single().status)
                    assertEquals(if (denied) AgentRunStatus.FAILED else AgentRunStatus.COMPLETED, agent.snapshot.run.status)
                    if (denied) {
                        assertTrue(agent.toolLedger.results.none { it.toolName == "device.open_app" })
                        // long: 页面刷新会清空临时 workflowError；拒绝原因必须从本次持久化 Run 复核，不能把瞬时提示消失误判为拒绝未落库。
                        assertTrue(detail.run.errorMessage.orEmpty().contains("工具未获批准"))
                        awaitAppWindow(automation, context.packageName)
                        if (editor) {
                            assertTrue(detail.run.goalVerificationDecision?.status != WorkflowGoalVerificationStatus.VERIFIED)
                            androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_EDITOR_DENY status=FAILED calls=2 approvals=1 noActionResult=true noPostSnapshot=true goalVerified=false failureAuditRetained=true workflowRunId=$workflowRunId")
                        }
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
                if (contracted && !denied) {
                    (1..if (boundary) 1 else actions.size).forEach { sequence -> rule.onNodeWithTag("workflow-device-action-$workflowRunId-$sequence-0", useUnmergedTree = true).performScrollTo().assertIsDisplayed() }
                    scenario.recreate()
                    scenario.awaitState { !it.loadingWorkflows && it.workflows.any { workflow -> workflow.id == definition.id } }
                    openWorkflowPage()
                    rule.onNodeWithTag("workflow-item-${definition.id}").performScrollTo().performClick()
                    rule.onNodeWithTag("workflow-run-result-$workflowRunId", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    if (boundary) assertTrue(rule.onAllNodesWithTag("workflow-device-action-$workflowRunId-2-0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                    else rule.onNodeWithTag("workflow-device-action-$workflowRunId-${actions.size}-0", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    assertEquals(detail, workflowRepo.runDetail(workflowRunId))
                    androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_RECREATE visibleResult=true ${if (boundary) "visibleFailure" else if (scroll) "visibleSwipeAction" else if (editor) "visibleOpenAction" else "visibleTypeAction"}=true roomGoalEqual=true workflowRunId=$workflowRunId")
                }
                if (denied) {
                    val denied = rule.onAllNodes(hasText("工具未获批准", substring = true) and
                        hasAnyAncestor(hasTestTag("workflow-item-${definition.id}")), useUnmergedTree = true)
                    assertTrue(denied.fetchSemanticsNodes().isNotEmpty())
                    denied[0].performScrollTo().assertIsDisplayed()
                }
                assertEquals(detail, workflowRepo.runDetail(workflowRunId))
                if (editor) {
                    detail.steps.forEach { step ->
                        val input = WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot)
                        assertEquals("com.android.settings", input.targetAppPackage)
                        assertEquals(contract, input.goalVerificationContract)
                    }
                    if (denied) {
                        scenario.recreate()
                        scenario.awaitState { !it.loadingWorkflows && it.workflows.any { item -> item.id == definition.id } }
                        openWorkflowPage()
                        rule.onNodeWithTag("workflow-item-${definition.id}").performScrollTo().performClick()
                        rule.onNodeWithTag("workflow-run-result-$workflowRunId", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                        assertEquals(detail, workflowRepo.runDetail(workflowRunId))
                        // long: Run 行可见不代表拒绝原因仍可读；重建后再次检查持久失败正文，才能报告失败展示已恢复。
                        val rebuiltFailure = rule.onAllNodes(hasText("工具未获批准", substring = true) and
                            hasAnyAncestor(hasTestTag("workflow-item-${definition.id}")), useUnmergedTree = true)
                        assertTrue(rebuiltFailure.fetchSemanticsNodes().isNotEmpty())
                        rebuiltFailure[0].performScrollTo().assertIsDisplayed()
                        androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_RECREATE visibleResult=true visibleFailure=true roomGoalEqual=true workflowRunId=$workflowRunId")
                    }
                    androidLiveStatus("STAGE${stage}_EVIDENCE WORKFLOW_EDITOR targetConfirmed=true contractConfirmed=true roomEqual=true frozenEqual=true editReloadEqual=true definitionSeeded=false workflowRunId=$workflowRunId")
                }
                androidLiveStatus("STAGE${acceptanceStage}_EVIDENCE WORKFLOW case=$case workflowRunId=$workflowRunId agentRunIds=${agents.joinToString(",") { it.snapshot.run.id }} visibleRun=true actualViewModel=true productionUseCase=true editorCreated=${!device || editor} definitionSeeded=${device && !editor} status=$expected steps=${detail.steps.size} roomVerified=true visibleResult=true")
            } catch (error: Throwable) {
                executionFailure = error
                throw error
            } finally {
                // long: 停止超时必须保留正文失败的原因；附加异常留下停止失败，同时让外层拒绝清理尚在执行的配置。
                runCatching {
                    scenario.onActivity { ViewModelProvider(it)[XiaoLingViewModel::class.java].apply { if (uiState.sendingMessage) stopGenerating() } }
                    scenario.awaitState(30_000) { !it.sendingMessage && it.runningWorkflowId == null }
                    markActorStopped()
                }.exceptionOrNull()?.let { failure -> executionFailure?.addSuppressed(failure) ?: throw failure }
            }
        }
    }

    private fun createWorkflowInEditor(name: String, goals: List<String>, contract: WorkflowGoalVerificationContract?, scoped: Boolean) {
        rule.onNodeWithContentDescription("新建工作流").performClick()
        rule.onNodeWithTag("workflow-editor-name").performTextInput(name)
        goals.forEachIndexed { index, goal ->
            if (index > 0) rule.onNodeWithText("添加步骤").performScrollTo().performClick()
            rule.onNodeWithTag("workflow-editor-step-$index").performScrollTo().performTextInput(goal)
        }
        if (scoped) {
            rule.onNodeWithTag("workflow-editor-app").performScrollTo().performClick()
            rule.onNodeWithText("系统设置").performClick()
            rule.onNodeWithTag("workflow-editor-verify").performScrollTo().performClick()
            rule.onNodeWithTag("workflow-editor-completion-goal").performScrollTo().performTextInput(requireNotNull(contract).sourceGoal)
            rule.onNodeWithTag("workflow-editor-final-app").performScrollTo().performClick()
            rule.onNodeWithTag("workflow-editor-criterion-0").performScrollTo().performClick()
            rule.onNodeWithText("打开限定应用并复查页面").performClick()
            rule.onNodeWithTag("workflow-editor-save").assertIsNotEnabled()
            rule.onNodeWithTag("workflow-editor-confirm-boundary").performScrollTo().performClick()
        }
        rule.onNodeWithTag("workflow-editor-save").assertIsEnabled().performClick()
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
    private suspend fun approveSearchAction(context: Context, automation: UiAutomation, workflows: RoomWorkflowRepository,
        agents: RoomAgentRunRepository, workflowRunId: String, sequence: Int, tool: String, marker: String) {
        val pending = awaitPending(workflowRunId, workflows, agents, sequence)
        assertEquals(tool, pending.toolName)
        assertTrue(agents.toolLedger(pending.runId).results.none { it.toolCallId == pending.toolCallId })
        var priorSearchTapVerified = false
        if (sequence == 3) {
            // long: 搜索伴随包的占位符会随 ROM 本地化；只有本 Workflow 上一步确已批准并验证搜索点击，才可用唯一编辑框身份替代字面标签。
            val previous = requireNotNull(workflows.runDetail(workflowRunId)).steps.single { it.sequence == 2 }
            assertEquals(WorkflowStepStatus.COMPLETED, previous.status)
            val agent = requireNotNull(agents.runDetail(requireNotNull(previous.agentRunId)))
            assertEquals(listOf("device.snapshot", "device.tap_ref", "device.snapshot"), agent.toolLedger.calls.map { it.toolName })
            assertEquals(ApprovalRequestStatus.APPROVED, agent.approvals.single().status)
            val result = agent.toolLedger.results.single { it.toolName == "device.tap_ref" }
            assertTrue(result.success && result.verificationStatus == ToolVerificationStatus.PASSED && result.executorVerified == true)
            priorSearchTapVerified = true
        }
        if (sequence == 1) assertEquals(mapOf("package_name" to "com.android.settings"), pending.arguments)
        else assertSearchReference(context, pending, marker, priorSearchTapVerified)
        val overlay = awaitOverlay(automation)
        assertTrue(overlay.texts().contains("小灵设备动作审批"))
        assertTrue(overlay.texts().any { it.contains(tool) })
        if (sequence == 1) awaitAppWindow(automation, context.packageName) else awaitSettingsWindow(automation)
        androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_CHECKPOINT phase=pending sequence=$sequence tool=$tool runId=${pending.runId} approvalId=${pending.id} noResult=true referenceChecked=true")
        click(overlay, "批准执行")
    }
    private fun assertSearchReference(context: Context, pending: ApprovalRequestRecord, marker: String, priorSearchTapVerified: Boolean) {
        val typing = pending.toolName == "device.type_text"
        assertEquals(if (typing) setOf("snapshot_id", "ref", "text_sha256", "text_length") else setOf("snapshot_id", "ref"), pending.arguments.keys)
        if (typing) {
            assertEquals(hash(marker.toByteArray()), pending.arguments["text_sha256"])
            assertEquals(marker.length.toString(), pending.arguments["text_length"])
            assertFalse(pending.toString().contains(marker))
        }
        val snapshotId = requireNotNull(pending.arguments["snapshot_id"])
        val ref = requireNotNull(pending.arguments["ref"])
        val controller = DeviceObservationComponents.controller(context)
        val inspection = controller.inspectReference(snapshotId, ref)
        assertTrue(inspection.matched)
        if (typing) assertTrue(requireNotNull(inspection.target).editable)
        // long: 仅在测试内只读 Runtime 当前快照，不能重新 capture 使待审批 ref 失效；字段漂移即失败，不退化成按坐标或任意节点批准。
        val field = DeviceObservationController::class.java.getDeclaredField("currentSnapshotCapture").apply { isAccessible = true }
        val capture = requireNotNull(field.get(controller) as? DeviceSnapshotCapture.Success)
        assertEquals(snapshotId, capture.snapshot.snapshotId)
        assertTrue(DeviceActionPolicy.areAllowedInAppFlow("com.android.settings", capture.snapshot.packageName))
        val node = capture.snapshot.nodes.single { it.ref == ref }
        assertTrue(node.enabled && !node.redacted)
        assertTrue((if (typing) DeviceNodeAction.TYPE_TEXT else DeviceNodeAction.TAP) in node.actions)
        val byIndex = capture.snapshot.nodes.associateBy { it.index }
        fun withinTarget(candidate: DeviceSnapshotNode): Boolean {
            var current: DeviceSnapshotNode? = candidate
            while (current != null) {
                if (current.index == node.index) return true
                current = current.parentIndex?.let(byIndex::get)
            }
            return false
        }
        val labels = capture.snapshot.nodes.filter(::withinTarget).flatMap { listOfNotNull(it.text, it.description, it.hint) }.filter(String::isNotBlank)
        // long: 只批准搜索入口或搜索框；包含其他设置项的宽容器不能因某个后代写着“搜索”就取得本轮动作许可。
        val allowedLabels = setOf("搜索设置", "搜索", "Search settings", "Search")
        val labelledSearchControl = labels.isNotEmpty() && labels.all { it.trim() in allowedLabels }
        // long: Native 将空输入框的占位符同时暴露为 text/hint；严格绑定已验证搜索流程、固定伴随包与唯一未脱敏编辑框，不批准已有查询或宽容器。
        val nativeSearchPlaceholder = typing && priorSearchTapVerified && capture.snapshot.packageName == "com.android.settings.intelligence" &&
            !node.hint.isNullOrBlank() && node.text == node.hint && node.description.isNullOrBlank() &&
            capture.snapshot.nodes.count { it.enabled && !it.redacted && DeviceNodeAction.TYPE_TEXT in it.actions } == 1 &&
            labels.all { it == node.hint || it.trim() in allowedLabels }
        assertTrue("待审批引用不是独立搜索控件", labelledSearchControl || nativeSearchPlaceholder)
    }
    private fun assertSearchPrivacy(agent: AgentRunDetailRecord, marker: String) {
        val call = agent.toolLedger.calls.single { it.toolName == "device.type_text" }
        val approval = agent.approvals.single()
        assertEquals(setOf("snapshot_id", "ref", "text_sha256", "text_length"), call.arguments.keys)
        assertEquals(hash(marker.toByteArray()), call.arguments["text_sha256"])
        assertEquals(marker.length.toString(), call.arguments["text_length"])
        assertEquals(call.arguments, approval.arguments)
        assertFalse(call.toString().contains(marker)); assertFalse(approval.toString().contains(marker))
        val result = agent.toolLedger.results.single { it.toolCallId == call.id }
        assertFalse(result.content.contains(marker)); assertFalse(result.content.contains("\"nodes\"")); assertFalse(result.content.contains("\"ref\""))
        assertTrue(agent.snapshot.events.filter { it.type in setOf("tool.result", "tool.call.proposed", "tool.call.validated") }.none { it.toString().contains(marker) })
    }

    private fun assertScrollEvidence(agent: AgentRunDetailRecord, direction: String) {
        val call = agent.toolLedger.calls.single { it.toolName == "device.swipe" }
        assertEquals(setOf("snapshot_id", "ref", "direction"), call.arguments.keys)
        assertEquals(direction, call.arguments["direction"])
        val observations = agent.toolLedger.results.filter { it.toolName == "device.snapshot" }.map { JSONObject(it.content) }
        assertEquals(2, observations.size)
        val before = observations.first()
        val after = observations.last()
        assertEquals(call.arguments["snapshot_id"], before.getString("snapshot_id"))
        assertEquals("com.android.settings", before.getString("package"))
        assertEquals(before.getString("package"), after.getString("package"))
        assertEquals(before.getInt("window_id"), after.getInt("window_id"))
        assertTrue(after.getLong("window_generation") > before.getLong("window_generation"))
        val beforeNodes = before.getJSONArray("nodes")
        val target = (0 until beforeNodes.length()).map(beforeNodes::getJSONObject).single { it.optString("ref") == call.arguments["ref"] }
        val targetActions = target.getJSONArray("actions")
        assertTrue((0 until targetActions.length()).any { targetActions.getString(it) == "swipe" })
        // long: 同一 Agent 的真实前后快照须出现内容变化；仅 generation 更新或模型声称已滚动不足以形成独立验收证据。
        assertTrue("滚动后可见内容未变化", visibleSnapshotContent(before) != visibleSnapshotContent(after))
        val result = agent.toolLedger.results.single { it.toolCallId == call.id }
        assertNotNull(WorkflowDeviceActionResultCodec.decode(result.content))
        listOf("viewport", "anchors", "fingerprint", "snapshot_id", "ref", "nodes").forEach { field ->
            assertFalse("滚动结果包含执行期证据：$field", result.content.contains("\"$field\""))
        }
    }

    private fun visibleSnapshotContent(json: JSONObject): Set<String> {
        val nodes = json.getJSONArray("nodes")
        return (0 until nodes.length()).map(nodes::getJSONObject).flatMap { node ->
            listOf("text", "description", "hint").map { node.optString(it) }.filter(String::isNotBlank)
        }.toSet()
    }

    private fun visibleSnapshotPositions(json: JSONObject): Set<List<String>> {
        val nodes = json.getJSONArray("nodes")
        return (0 until nodes.length()).map(nodes::getJSONObject).mapNotNull { node ->
            val content = listOf("text", "description", "hint").map { node.optString(it) }
            if (content.all(String::isBlank)) null else content + node.getJSONArray("bounds").toString()
        }.toSet()
    }

    private suspend fun assertScrollBoundary(detail: WorkflowRunDetail, agents: List<AgentRunDetailRecord>,
        context: Context, automation: UiAutomation, contract: WorkflowGoalVerificationContract?) {
        assertEquals(2, agents.size)
        val opened = agents.first()
        assertEquals(AgentRunStatus.COMPLETED, opened.snapshot.run.status)
        assertEquals(listOf("device.snapshot", "device.open_app", "device.snapshot"), opened.toolLedger.calls.map { it.toolName })
        assertTrue(opened.toolLedger.results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
        assertEquals(ApprovalRequestStatus.APPROVED, opened.approvals.single().status)
        val failed = agents.last()
        assertEquals(AgentRunStatus.FAILED, failed.snapshot.run.status)
        assertTrue(failed.approvals.isEmpty())
        assertEquals(listOf("device.snapshot", "device.swipe"), failed.toolLedger.calls.map { it.toolName })
        val call = failed.toolLedger.calls.last()
        assertEquals(setOf("snapshot_id", "ref", "direction"), call.arguments.keys)
        assertEquals("down", call.arguments["direction"])
        val before = JSONObject(failed.toolLedger.results.first().content)
        assertEquals(2, failed.toolLedger.results.size)
        assertTrue(failed.toolLedger.results.first().success && failed.toolLedger.results.first().verificationStatus == ToolVerificationStatus.PASSED)
        assertEquals("com.android.settings", before.getString("package"))
        assertEquals(call.arguments["snapshot_id"], before.getString("snapshot_id"))
        val nodes = before.getJSONArray("nodes")
        val target = (0 until nodes.length()).map(nodes::getJSONObject).single { it.optString("ref") == call.arguments["ref"] }
        val targetActions = target.getJSONArray("actions")
        assertTrue((0 until targetActions.length()).any { targetActions.getString(it) == "swipe" })
        val result = failed.toolLedger.results.single { it.toolCallId == call.id }
        // long: 顶部没有移动只能保留失败；既接受原生不支持/返回 false，也接受动作已发送但 Executor 拒绝 no-op，其他失败不算边界通过。
        assertTrue((!result.success && (result.content.contains("ACTION_NOT_SUPPORTED") || result.content.contains("ACTION_FAILED"))) ||
            (result.success && result.executorVerified == false && result.verificationStatus == ToolVerificationStatus.FAILED))
        assertTrue(detail.run.goalVerificationDecision?.status != WorkflowGoalVerificationStatus.VERIFIED)
        assertEquals(contract, WorkflowStepSnapshotCodec.decodeInput(detail.steps.last().inputSnapshot).goalVerificationContract)
        assertTrue(WorkflowStepSnapshotCodec.decodeOutput(detail.steps.last().outputSnapshot)?.deviceActionDecisions.orEmpty().isEmpty())
        awaitSettingsWindow(automation)
        val after = requireNotNull(DeviceObservationComponents.controller(context).capture() as? DeviceSnapshotCapture.Success).snapshot
        assertEquals("com.android.settings", after.packageName)
        assertEquals(before.getInt("window_id"), after.windowId)
        val afterJson = JSONObject(DeviceSnapshotCodec.encode(after))
        // long: 未移动必须同时满足内容与其屏幕位置相同；诊断只输出布尔结果，不能让 JUnit 将窗口正文打印出来。
        assertTrue("边界失败后内容或位置发生变化", visibleSnapshotPositions(before) == visibleSnapshotPositions(afterJson))
        androidLiveStatus("STAGE${acceptanceStage}_EVIDENCE WORKFLOW_SCROLL_BOUNDARY status=FAILED direction=down noMovement=true noSwipeApproval=true goalVerified=false postSnapshotNotExecuted=true failureAuditRetained=true workflowRunId=${detail.run.id}")
    }
    private suspend fun awaitSettingsWindow(automation: UiAutomation) = withTimeout(10_000) {
        while (true) {
            val window = automation.windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            val packageName = window?.root?.packageName?.toString()
            if (DeviceActionPolicy.areAllowedInAppFlow("com.android.settings", packageName)) {
                androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_CHECKPOINT phase=window package=$packageName active=${window?.isActive} focused=${window?.isFocused}")
                return@withTimeout
            }
            delay(100)
        }
    }
    private fun openWorkflowPage() {
        rule.onNodeWithTag("bottom_tab_settings").assertIsDisplayed().assertIsEnabled().performClick()
        // long: 底栏点击与设置内容重组不共享同步完成点；只等待已知正式入口，避免在页面尚未切换时把缺节点误判为搜索流程失败。
        try {
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodesWithContentDescription("新建工作流").fetchSemanticsNodes().size == 1 ||
                    rule.onAllNodesWithText("工作流").fetchSemanticsNodes().size == 1
            }
        } catch (error: Throwable) {
            // long: 只报告固定公开入口的数量，定位页面/语义树差异；不把模型配置、正文或完整语义树写入失败日志。
            androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_NAVIGATION workflowMerged=${rule.onAllNodesWithText("工作流").fetchSemanticsNodes().size} workflowUnmerged=${rule.onAllNodesWithText("工作流", useUnmergedTree = true).fetchSemanticsNodes().size} newWorkflow=${rule.onAllNodesWithContentDescription("新建工作流").fetchSemanticsNodes().size} providerEntry=${rule.onAllNodesWithText("模型提供方管理").fetchSemanticsNodes().size} settingsTitle=${rule.onAllNodesWithText("设置").fetchSemanticsNodes().size}")
            throw error
        }
        if (rule.onAllNodesWithContentDescription("新建工作流").fetchSemanticsNodes().isEmpty())
            rule.onNodeWithText("工作流").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithContentDescription("新建工作流").fetchSemanticsNodes().size == 1
        }
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
        androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_STATE loading=${latest?.loadingWorkflows} running=${latest?.runningWorkflowId} sending=${latest?.sendingMessage} error=${latest?.workflowError}")
        error("正式 Workflow 状态超时")
    }
    private fun overlayRoot(automation: UiAutomation): AccessibilityNodeInfo? = automation.windows
        .singleOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() == "XiaoLingDeviceActionApproval" }?.root
    private suspend fun awaitOverlay(automation: UiAutomation) = withTimeout(15_000) {
        while (true) { overlayRoot(automation)?.let { return@withTimeout it }; delay(100) }; error("unreachable")
    }
    private suspend fun awaitPending(workflowRunId: String, workflows: RoomWorkflowRepository, agents: RoomAgentRunRepository, sequence: Int = 1): ApprovalRequestRecord = withTimeout(120_000) {
        // long: Runtime 的 WAITING_APPROVAL 快照先于 Gate 创建审批；等待本 Workflow 步骤实际关联的 Room 请求，不能把 UI 状态当成落库完成。
        while (true) {
            rule.waitForIdle()
            val workflow = requireNotNull(workflows.runDetail(workflowRunId))
            check(workflow.run.status !in setOf(WorkflowRunStatus.COMPLETED, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED, WorkflowRunStatus.BLOCKED)) {
                "本 Workflow 已结束但缺少预期审批：${workflow.run.errorMessage}"
            }
            val step = workflow.steps.single { it.sequence == sequence }
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
                androidLiveStatus("STAGE${acceptanceStage}_WORKFLOW_CHECKPOINT phase=window package=$expected active=${window.isActive} focused=${window.isFocused}")
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
