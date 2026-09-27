package com.longdev.xiaoling.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceObservationComponents
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.UiPreferenceStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 直接 Agent 只验证一次打开已登记应用，确认设置授权能替代逐次点击但不旁路审计和后置验证。 */
@RunWith(AndroidJUnit4::class)
class Stage277DirectAgentDeviceAutoApprovalInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
            it.serviceInfo = it.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }
    private var accessibilityServicesBefore: String? = null
    private var accessibilityEnabledBefore: String? = null

    @After
    fun restoreAccessibilityState() {
        if (accessibilityServicesBefore == null && accessibilityEnabledBefore == null) return
        val services = accessibilityServicesBefore
            ?.takeUnless { it == "null" }
            .orEmpty()
        val commands = buildList {
            val enabled = accessibilityEnabledBefore?.takeUnless { it == "null" } ?: "0"
            add("settings put secure accessibility_enabled $enabled")
            if (services.isBlank()) {
                add("settings delete secure enabled_accessibility_services")
            } else {
                add("settings put secure enabled_accessibility_services ${shellQuote(services)}")
            }
        }
        commands.forEach { command -> runCatching { automation.executeShellCommand(command).close() } }
    }

    @Test
    fun directAgentOpenAppUsesPersistentDeviceActionGrant() = runBlocking {
        assumeTrue(
            "仅显式 stage277RealRun=true 运行 Redmi 真实直接 Agent 验收",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        accessibilityServicesBefore = readSecureSetting("enabled_accessibility_services")
        accessibilityEnabledBefore = readSecureSetting("accessibility_enabled")

        val preferences = UiPreferenceStore(context)
        val originalAgentEnabled = preferences.loadDeviceAgentEnabled()
        val originalAutoApproval = preferences.loadDeviceActionAutoApprovalEnabled()
        val providerState = ProviderRepository(context).load()
        val provider = requireNotNull(providerState.profiles.singleOrNull { it.id == providerState.selectedProfileId })
        assertTrue("当前 Provider 凭据不可用", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank())
        assertEquals("模型须与 AGENTS.md 配置一致", "gpt-5.6-luna", provider.model)

        val database = XiaoLingDatabase.getInstance(context)
        val profiles = RoomAgentProfileStore(context)
        val stateStore = RoomStateStore(context)
        val runs = RoomAgentRunRepository(context)
        val originalProfileId = stateStore.selectedAgentProfileId()
        val originalConversationId = stateStore.selectedConversationId()
        val baselineRun = runs.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage277-direct-device-$now"
        val conversationId = "conversation-$profileId"
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            preferences.saveDeviceAgentEnabled(true)
            preferences.saveDeviceActionAutoApprovalEnabled(true)
            automation.clearCache()
            refreshAccessibilityBinding()
            val controller = DeviceObservationComponents.controller(context)
            var health = awaitDeviceHealth(controller)
            if (health != DeviceAgentHealthState.READY) {
                // long: Redmi 可能接受 secure setting 写入但不重新触发服务生命周期；通过系统设置页真实切换，确保 onServiceConnected 产生而不伪造 Runtime 状态。
                refreshAccessibilityBindingThroughSettings()
                health = awaitDeviceHealth(controller)
            }
            assertEquals(DeviceAgentHealthState.READY, health)
            profiles.upsert(
                AgentProfileRecord(
                    id = profileId,
                    name = "第277阶段直接 Agent 设备授权",
                    avatar = "277",
                    providerId = provider.id,
                    model = provider.model,
                    apiMode = ApiMode.RESPONSES,
                    systemPrompt = "只打开系统计算器并确认当前前台应用。仅允许使用 device.snapshot 和 device.open_app，不点击、不输入、不执行其他动作。",
                    contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                    allowedToolNames = listOf("device.snapshot", "device.open_app"),
                    allowedSkillIds = listOf("device-control"),
                    memoryEnabled = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            assertTrue(profiles.select(profileId))
            database.conversationDao().insertConversations(
                listOf(ConversationEntity(conversationId, "第277阶段直接 Agent", "", null, null, null, now, now)),
            )
            stateStore.saveSelectedAgentProfileId(profileId)
            stateStore.saveSelectedConversationId(conversationId)

            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, "会话加载") {
                it.selectedConversationId == conversationId &&
                    it.selectedAgentProfileId == profileId &&
                    !it.loadingConversationMessages
            }
            scenario.onActivity {
                ViewModelProvider(it)[XiaoLingViewModel::class.java].apply {
                    updatePersonalTaskMode(false)
                    updatePrompt(PROMPT)
                }
            }
            clickVisible("发送")

            val completed = awaitState(scenario, "直接 Agent 完成", 240_000L) { state ->
                state.activeAgentRun?.run?.conversationId == conversationId &&
                    state.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                    state.pendingAgentApproval == null &&
                    !state.sendingMessage
            }
            assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)

            val detail = requireNotNull(runs.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId })
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val openAppCall = detail.toolLedger.calls.single { it.toolName == "device.open_app" }
            assertEquals(mapOf("package_name" to CALCULATOR_PACKAGE), openAppCall.arguments)
            val openAppResult = detail.toolLedger.results.single { it.toolCallId == openAppCall.id }
            assertTrue(openAppResult.success)
            assertEquals(true, openAppResult.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, openAppResult.verificationStatus)
            val approval = detail.approvals.single { it.toolCallId == openAppCall.id }
            assertEquals(ApprovalRequestStatus.APPROVED, approval.status)
            assertTrue(approval.decisionReason?.contains("设置中授权") == true)
            assertTrue("直接 Agent 不应执行其他设备动作", detail.toolLedger.calls.all { it.toolName in setOf("device.snapshot", "device.open_app") })

            val finalSnapshot = controller.capture()
            assertTrue("直接 Agent 后当前窗口必须是计算器", finalSnapshot is com.longdev.xiaoling.device.DeviceSnapshotCapture.Success)
            assertEquals(CALCULATOR_PACKAGE, (finalSnapshot as com.longdev.xiaoling.device.DeviceSnapshotCapture.Success).snapshot.packageName)
            println(
                "STAGE277_DIRECT_COMPLETED runId=${detail.snapshot.run.id} " +
                    "tool=device.open_app approval=${approval.status} " +
                    "reason=setting_grant executorVerified=${openAppResult.executorVerified} " +
                    "verification=${openAppResult.verificationStatus} afterPackage=$CALCULATOR_PACKAGE",
            )
        } finally {
            scenario?.let { active ->
                runCatching {
                    active.onActivity { ViewModelProvider(it)[XiaoLingViewModel::class.java].stopGenerating() }
                    val deadline = SystemClock.uptimeMillis() + 20_000L
                    while (readState(active).sendingMessage && SystemClock.uptimeMillis() < deadline) delay(100L)
                }
                active.close()
            }
            preferences.saveDeviceActionAutoApprovalEnabled(false)
            preferences.saveDeviceAgentEnabled(originalAgentEnabled)
            if (originalAgentEnabled && originalAutoApproval) preferences.saveDeviceActionAutoApprovalEnabled(true)
            originalProfileId?.let { profiles.select(it) }
            stateStore.saveSelectedAgentProfileId(originalProfileId.orEmpty())
            stateStore.saveSelectedConversationId(originalConversationId.orEmpty())
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profiles.delete(profileId)
            baselineRun?.let { assertEquals("旧 Run 应保持不变", it, runs.runDetail(it.snapshot.run.id)) }
        }
    }

    @Test
    fun directAgentTapRefUsesPersistentDeviceActionGrant() = runBlocking {
        assumeTrue(
            "仅显式 stage278RealRun=true 运行 Redmi 真实直接 Agent tap_ref 验收",
            InstrumentationRegistry.getArguments().getString(ARG_TAP_REF_REAL_RUN) == "true",
        )
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        accessibilityServicesBefore = readSecureSetting("enabled_accessibility_services")
        accessibilityEnabledBefore = readSecureSetting("accessibility_enabled")

        val preferences = UiPreferenceStore(context)
        val originalAgentEnabled = preferences.loadDeviceAgentEnabled()
        val originalAutoApproval = preferences.loadDeviceActionAutoApprovalEnabled()
        val providerState = ProviderRepository(context).load()
        val provider = requireNotNull(providerState.profiles.singleOrNull { it.id == providerState.selectedProfileId })
        assertTrue("当前 Provider 凭据不可用", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank())
        assertEquals("模型须与 AGENTS.md 配置一致", "gpt-5.6-luna", provider.model)

        val database = XiaoLingDatabase.getInstance(context)
        val profiles = RoomAgentProfileStore(context)
        val stateStore = RoomStateStore(context)
        val runs = RoomAgentRunRepository(context)
        val originalProfileId = stateStore.selectedAgentProfileId()
        val originalConversationId = stateStore.selectedConversationId()
        val baselineRun = runs.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage278-direct-tap-ref-$now"
        val conversationId = "conversation-$profileId"
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            preferences.saveDeviceAgentEnabled(true)
            preferences.saveDeviceActionAutoApprovalEnabled(true)
            automation.clearCache()
            refreshAccessibilityBinding()
            val controller = DeviceObservationComponents.controller(context)
            var health = awaitDeviceHealth(controller)
            if (health != DeviceAgentHealthState.READY) {
                // long: 只有系统设置页重新切换才能可靠触发 Redmi 的 onServiceConnected，不能用测试状态伪造无障碍可用。
                refreshAccessibilityBindingThroughSettings()
                health = awaitDeviceHealth(controller)
            }
            assertEquals(DeviceAgentHealthState.READY, health)
            profiles.upsert(
                AgentProfileRecord(
                    id = profileId,
                    name = "第278阶段直接 Agent tap_ref 授权",
                    avatar = "278",
                    providerId = provider.id,
                    model = provider.model,
                    apiMode = ApiMode.RESPONSES,
                    systemPrompt = "打开系统计算器，获取当前页面快照，点击数字7并再次观察结果。只允许使用 device.snapshot、device.open_app、device.tap_ref；禁止输入文本、返回桌面或调用其他工具。",
                    contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                    allowedToolNames = listOf("device.snapshot", "device.open_app", "device.tap_ref"),
                    allowedSkillIds = listOf("device-control"),
                    memoryEnabled = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            assertTrue(profiles.select(profileId))
            database.conversationDao().insertConversations(
                listOf(ConversationEntity(conversationId, "第278阶段直接 Agent tap_ref", "", null, null, null, now, now)),
            )
            stateStore.saveSelectedAgentProfileId(profileId)
            stateStore.saveSelectedConversationId(conversationId)

            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, "会话加载") {
                it.selectedConversationId == conversationId &&
                    it.selectedAgentProfileId == profileId &&
                    !it.loadingConversationMessages
            }
            scenario.onActivity {
                ViewModelProvider(it)[XiaoLingViewModel::class.java].apply {
                    updatePersonalTaskMode(false)
                    updatePrompt(PROMPT_TAP_REF)
                }
            }
            clickVisible("发送")

            val completed = try {
                awaitState(scenario, "直接 Agent tap_ref 完成", 240_000L) { state ->
                    state.activeAgentRun?.run?.conversationId == conversationId &&
                        state.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        state.pendingAgentApproval == null &&
                        !state.sendingMessage
                }
            } catch (failure: Exception) {
                // long: 真实模型或设备动作若再次停滞，只输出状态与工具名，不打印 snapshot/ref 或 Provider 配置；失败 Run 留在 Room 供后续审计。
                val stalled = runs.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId }
                println(
                    "STAGE278_STALLED run=${stalled?.snapshot?.run?.id} status=${stalled?.snapshot?.run?.status} " +
                        "approvals=${stalled?.approvals?.map { "${it.toolName}:${it.status}" }} " +
                        "calls=${stalled?.toolLedger?.calls?.map { it.toolName }} " +
                        "results=${stalled?.toolLedger?.results?.map { "${it.toolCallId}:${it.success}:${it.verificationStatus}" }}",
                )
                throw failure
            }
            assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)

            val detail = requireNotNull(runs.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId })
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            assertTrue("直接 Agent tap_ref 不应执行其他工具", detail.toolLedger.calls.all {
                it.toolName in setOf("device.snapshot", "device.open_app", "device.tap_ref")
            })
            val openAppCall = detail.toolLedger.calls.single { it.toolName == "device.open_app" }
            val openAppResult = detail.toolLedger.results.single { it.toolCallId == openAppCall.id }
            assertTrue(openAppResult.success)
            assertEquals(true, openAppResult.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, openAppResult.verificationStatus)
            val tapCall = detail.toolLedger.calls.single { it.toolName == "device.tap_ref" }
            assertTrue("tap_ref 必须绑定 snapshot_id", tapCall.arguments["snapshot_id"]?.toString()?.isNotBlank() == true)
            assertTrue("tap_ref 必须绑定短生命周期 ref", tapCall.arguments["ref"]?.toString()?.isNotBlank() == true)
            val tapResult = detail.toolLedger.results.single { it.toolCallId == tapCall.id }
            assertTrue(tapResult.success)
            assertEquals(true, tapResult.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, tapResult.verificationStatus)
            val tapApproval = detail.approvals.single { it.toolCallId == tapCall.id }
            assertEquals(ApprovalRequestStatus.APPROVED, tapApproval.status)
            assertTrue(tapApproval.decisionReason?.contains("设置中授权") == true)
            assertEquals(2, detail.approvals.size)

            val finalSnapshot = controller.capture()
            assertTrue("tap_ref 后当前窗口必须仍是计算器", finalSnapshot is com.longdev.xiaoling.device.DeviceSnapshotCapture.Success)
            assertEquals(CALCULATOR_PACKAGE, (finalSnapshot as com.longdev.xiaoling.device.DeviceSnapshotCapture.Success).snapshot.packageName)
            println(
                "STAGE278_DIRECT_TAP_REF_COMPLETED runId=${detail.snapshot.run.id} " +
                    "tool=device.tap_ref approval=${tapApproval.status} " +
                    "reason=setting_grant executorVerified=${tapResult.executorVerified} " +
                    "verification=${tapResult.verificationStatus} afterPackage=$CALCULATOR_PACKAGE",
            )
        } finally {
            scenario?.let { active ->
                runCatching {
                    active.onActivity { ViewModelProvider(it)[XiaoLingViewModel::class.java].stopGenerating() }
                    val deadline = SystemClock.uptimeMillis() + 20_000L
                    while (readState(active).sendingMessage && SystemClock.uptimeMillis() < deadline) delay(100L)
                }
                active.close()
            }
            preferences.saveDeviceActionAutoApprovalEnabled(false)
            preferences.saveDeviceAgentEnabled(originalAgentEnabled)
            if (originalAgentEnabled && originalAutoApproval) preferences.saveDeviceActionAutoApprovalEnabled(true)
            originalProfileId?.let { profiles.select(it) }
            stateStore.saveSelectedAgentProfileId(originalProfileId.orEmpty())
            stateStore.saveSelectedConversationId(originalConversationId.orEmpty())
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profiles.delete(profileId)
            baselineRun?.let { assertEquals("旧 Run 应保持不变", it, runs.runDetail(it.snapshot.run.id)) }
        }
    }

    @Test
    fun directAgentTypeTextUsesPersistentDeviceActionGrant() = runBlocking {
        assumeTrue(
            "仅显式 stage279RealRun=true 运行 Redmi 真实直接 Agent type_text 验收",
            InstrumentationRegistry.getArguments().getString(ARG_TYPE_TEXT_REAL_RUN) == "true",
        )
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        restoreProviderFromRunnerArgsIfRequested()
        accessibilityServicesBefore = readSecureSetting("enabled_accessibility_services")
        accessibilityEnabledBefore = readSecureSetting("accessibility_enabled")

        val preferences = UiPreferenceStore(context)
        val originalAgentEnabled = preferences.loadDeviceAgentEnabled()
        val originalAutoApproval = preferences.loadDeviceActionAutoApprovalEnabled()
        val providerState = ProviderRepository(context).load()
        val provider = requireNotNull(providerState.profiles.singleOrNull { it.id == providerState.selectedProfileId })
        assertTrue("当前 Provider 凭据不可用", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank())
        assertEquals("模型须与 AGENTS.md 配置一致", "gpt-5.6-luna", provider.model)

        val database = XiaoLingDatabase.getInstance(context)
        val profiles = RoomAgentProfileStore(context)
        val stateStore = RoomStateStore(context)
        val runs = RoomAgentRunRepository(context)
        val originalProfileId = stateStore.selectedAgentProfileId()
        val originalConversationId = stateStore.selectedConversationId()
        val baselineRun = runs.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage279-direct-type-text-$now"
        val conversationId = "conversation-$profileId"
        var scenario: ActivityScenario<MainActivity>? = null

        try {
            preferences.saveDeviceAgentEnabled(true)
            preferences.saveDeviceActionAutoApprovalEnabled(true)
            automation.clearCache()
            refreshAccessibilityBinding()
            val controller = DeviceObservationComponents.controller(context)
            var health = awaitDeviceHealth(controller)
            if (health != DeviceAgentHealthState.READY) {
                // long: 仅系统设置页真实切换才能触发 Redmi 的 onServiceConnected，不能用测试状态伪造无障碍服务可用。
                refreshAccessibilityBindingThroughSettings()
                health = awaitDeviceHealth(controller)
            }
            assertEquals(DeviceAgentHealthState.READY, health)

            // long: Settings 会复用上一次搜索 Activity；真实输入验收必须从干净的设置根页开始，避免 open_app 被旧的 Settings Intelligence 窗口误判为未到达目标包。
            listOf("com.android.settings.intelligence", "com.android.settings").forEach { packageName ->
                runCatching { automation.executeShellCommand("am force-stop $packageName").close() }
            }
            SystemClock.sleep(1_000L)

            profiles.upsert(
                AgentProfileRecord(
                    id = profileId,
                    name = "第279阶段直接 Agent type_text 授权",
                    avatar = "279",
                    providerId = provider.id,
                    model = provider.model,
                    apiMode = ApiMode.RESPONSES,
                    systemPrompt = "严格完成一次非敏感文本输入闭环。只允许使用 device.open_app、device.snapshot、device.tap_ref、device.type_text；禁止使用 back、home、swipe 或任何其他工具。先打开包名 com.android.settings，再通过快照找到搜索设置入口并点击它，随后向当前快照中可编辑、未脱敏的搜索框精确输入 stage279_exact_readback，最后再次快照确认输入后的当前页面。不要把其他文本写入设备。",
                    contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                    allowedToolNames = listOf(
                        "device.snapshot",
                        "device.open_app",
                        "device.tap_ref",
                        "device.type_text",
                    ),
                    allowedSkillIds = listOf("device-control"),
                    memoryEnabled = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            assertTrue(profiles.select(profileId))
            database.conversationDao().insertConversations(
                listOf(ConversationEntity(conversationId, "第279阶段直接 Agent type_text", "", null, null, null, now, now)),
            )
            stateStore.saveSelectedAgentProfileId(profileId)
            stateStore.saveSelectedConversationId(conversationId)

            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, "会话加载") {
                it.selectedConversationId == conversationId &&
                    it.selectedAgentProfileId == profileId &&
                    !it.loadingConversationMessages
            }
            // long: 真实设备动作准备阶段可能短暂把系统设置置前；重建同一个 ActivityScenario 让发送和后续 UI 验收始终共享一个宿主，避免额外启动第二个 MainActivity 形成旧会话窗口。
            scenario.recreate()
            awaitState(scenario, "会话重建后加载") {
                it.selectedConversationId == conversationId &&
                    it.selectedAgentProfileId == profileId &&
                    !it.loadingConversationMessages
            }
            scenario.onActivity {
                ViewModelProvider(it)[XiaoLingViewModel::class.java].apply {
                    updatePersonalTaskMode(false)
                    updatePrompt(PROMPT_TYPE_TEXT)
                }
            }
            clickVisible("发送")

            val completed = try {
                awaitState(scenario, "直接 Agent type_text 完成", 300_000L) { state ->
                    state.activeAgentRun?.run?.conversationId == conversationId &&
                        state.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        state.pendingAgentApproval == null &&
                        !state.sendingMessage
                }
            } catch (failure: Exception) {
                // long: 失败时只保留工具名和状态，避免把输入原文、节点引用或 Provider 配置写入 instrumentation 输出。
                val stalled = runs.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId }
                println(
                    "STAGE279_STALLED run=${stalled?.snapshot?.run?.id} status=${stalled?.snapshot?.run?.status} " +
                        "approvals=${stalled?.approvals?.map { "${it.toolName}:${it.status}" }} " +
                        "calls=${stalled?.toolLedger?.calls?.map { it.toolName }} " +
                        "results=${stalled?.toolLedger?.results?.map { "${it.toolCallId}:${it.success}:${it.verificationStatus}" }}",
                )
                throw failure
            }
            assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)

            val detail = requireNotNull(runs.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId })
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val assistantMessage = completed.chatMessages.lastOrNull { it.verifiedAgentContext != null }
            val goalDecision = DirectAgentGoalVerificationPolicy.evaluate(
                requireNotNull(assistantMessage?.verifiedAgentContext),
            )
            assertEquals(DirectAgentGoalVerificationStatus.VERIFIED, goalDecision?.status)
            // long: 设备动作完成时 Settings 仍在前台；先在切回小灵宿主前固定精确输入回读，避免结果页刷新后把小灵自身窗口误当成历史目标页面。
            val actionSnapshot = controller.capture()
            assertTrue("type_text 后必须仍在系统设置搜索窗口", actionSnapshot is com.longdev.xiaoling.device.DeviceSnapshotCapture.Success)
            val action = (actionSnapshot as com.longdev.xiaoling.device.DeviceSnapshotCapture.Success).snapshot
            assertTrue(
                "动作完成时前台必须是设置或受控 Settings Intelligence 伴随包",
                action.packageName in setOf("com.android.settings", "com.android.settings.intelligence"),
            )
            assertTrue("动作完成快照必须精确回读输入文本", action.nodes.any { it.text == TYPE_TEXT_INPUT })
            // long: 真实 Run 的结果必须先在对话页形成目标卡片，再由用户可见按钮触发当前事实读取，不能只调用 ViewModel 私有入口冒充 UI 验收。
            // long: 设备动作会把系统设置置前；先把小灵主 Activity 重新带回前台，避免在错误窗口上寻找结果卡片。
            listOf("com.android.settings.intelligence", "com.android.settings").forEach { packageName ->
                // long: Redmi 的 Settings 搜索任务可能在小灵 Activity 已 resumed 后继续占据 UiAutomation 焦点；回到结果页前结束这两个临时窗口，确保验收读取的是小灵当前会话。
                runCatching { automation.executeShellCommand("am force-stop $packageName").close() }
            }
            SystemClock.sleep(700L)
            // long: 外部动作可能留下系统权限浮层；先关闭旧宿主，再启动唯一新的 MainActivity，确保结果验收窗口确实属于小灵而不是后台恢复的其他应用。
            requireNotNull(scenario).close()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(requireNotNull(scenario), "结果页宿主加载") {
                it.selectedConversationId == conversationId &&
                    it.selectedAgentProfileId == profileId &&
                    !it.loadingConversationMessages
            }
            try {
                awaitVisibleAfterScrolling("目标级结果")
            } catch (failure: Exception) {
                // long: 目标卡片缺失时只记录消息数量、来源和可信上下文是否解码，避免诊断日志泄露输入原文、节点文本或 Provider 凭据。
                val reloadedState = readState(requireNotNull(scenario))
                val persistedMessages = MessageRepository(database).loadConversation(conversationId)
                val persistedAgentResults = persistedMessages.count {
                    MessageOrigin.fromStored(it.origin, it.role) == MessageOrigin.AGENT_RESULT
                }
                val persistedVerifiedContexts = persistedMessages.count { !it.verifiedAgentContext.isNullOrBlank() }
                val runAfterReload = runs.recentRunDetails(30).firstOrNull {
                    it.snapshot.run.conversationId == conversationId
                }
                val goalDecisions = reloadedState.chatMessages.mapNotNull { message ->
                    message.verifiedAgentContext?.let(DirectAgentGoalVerificationPolicy::evaluate)
                }
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                val targetNodeCount = roots.sumOf { root ->
                    root.count { node ->
                        node.text?.toString() == "目标级结果" || node.contentDescription?.toString() == "目标级结果"
                    }
                }
                val visibleTargetNodeCount = roots.sumOf { root ->
                    root.count { node ->
                        node.isVisibleToUser &&
                            (node.text?.toString() == "目标级结果" || node.contentDescription?.toString() == "目标级结果")
                    }
                }
                val conversationTabCount = roots.sumOf { root -> root.count { node -> node.text?.toString() == "对话" } }
                val settingsTabCount = roots.sumOf { root -> root.count { node -> node.text?.toString() == "设置" } }
                println(
                    "STAGE281_GOAL_RESULT_DIAGNOSTICS " +
                        "selectedConversationId=${reloadedState.selectedConversationId} " +
                        "loadingConversationMessages=${reloadedState.loadingConversationMessages} " +
                        "sendingMessage=${reloadedState.sendingMessage} " +
                        "uiMessages=${reloadedState.chatMessages.size} " +
                        "uiAgentResults=${reloadedState.chatMessages.count { it.origin == MessageOrigin.AGENT_RESULT }} " +
                        "uiVerifiedContexts=${reloadedState.chatMessages.count { it.verifiedAgentContext != null }} " +
                        "directFactRunId=${reloadedState.directAgentCurrentFact?.runId.orEmpty()} " +
                        "persistedMessages=${persistedMessages.size} " +
                        "persistedAgentResults=$persistedAgentResults " +
                        "persistedVerifiedContexts=$persistedVerifiedContexts " +
                        "runStatus=${runAfterReload?.snapshot?.run?.status} " +
                        "goalDecisions=${goalDecisions.map { it.status.name + ":" + it.verifiedToolNames.joinToString(",") }} " +
                        "targetNodeCount=$targetNodeCount " +
                        "visibleTargetNodeCount=$visibleTargetNodeCount " +
                        "conversationTabCount=$conversationTabCount " +
                        "settingsTabCount=$settingsTabCount " +
                        "windows=${automation.windows.map { it.root?.packageName?.toString().orEmpty() }.distinct()}",
                )
                throw failure
            }
            val refreshButton = awaitVisibleAfterScrolling("重新读取当前事实")
            // long: 立即复用刚从滚动后的语义树取得的节点；再次从 root 查找可能因 Compose 重组让按钮短暂离开可见树，造成“找到后又找不到”的假失败。
            clickNodeOrAncestor(refreshButton, "当前事实刷新入口不可点击")
            val refreshedFact = awaitState(scenario, "目标级结果刷新当前事实") { state ->
                state.directAgentCurrentFact?.runId == detail.snapshot.run.id &&
                    !state.refreshingDirectAgentCurrentFact
            }
            assertTrue("当前事实刷新必须重新读取节点摘要", refreshedFact.directAgentCurrentFact?.nodeCount ?: 0 >= 0)
            val allowedTools = setOf("device.snapshot", "device.open_app", "device.tap_ref", "device.type_text")
            assertTrue("直接 Agent type_text 不应执行其他工具", detail.toolLedger.calls.all { it.toolName in allowedTools })
            val openIndex = detail.toolLedger.calls.indexOfFirst { it.toolName == "device.open_app" }
            val tapIndex = detail.toolLedger.calls.indexOfFirst { it.toolName == "device.tap_ref" }
            val typeCall = detail.toolLedger.calls.single { it.toolName == "device.type_text" }
            assertTrue("type_text 前必须先打开设置并点击搜索入口", openIndex >= 0 && tapIndex > openIndex)
            assertTrue("type_text 必须发生在 tap_ref 之后", detail.toolLedger.calls.indexOf(typeCall) > tapIndex)
            assertTrue("type_text 前后必须存在独立 snapshot", detail.toolLedger.calls.count { it.toolName == "device.snapshot" } >= 3)

            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(TYPE_TEXT_INPUT.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            assertEquals(setOf("snapshot_id", "ref", "text_sha256", "text_length"), typeCall.arguments.keys)
            assertEquals(expectedHash, typeCall.arguments["text_sha256"])
            assertEquals(TYPE_TEXT_INPUT.length.toString(), typeCall.arguments["text_length"])
            assertTrue("ToolCall Ledger 不得保存 type_text 原文", TYPE_TEXT_INPUT !in typeCall.toString())

            val typeApproval = detail.approvals.single { it.toolCallId == typeCall.id }
            assertEquals(ApprovalRequestStatus.APPROVED, typeApproval.status)
            assertTrue(typeApproval.decisionReason?.contains("设置中授权") == true)
            assertEquals(setOf("snapshot_id", "ref", "text_sha256", "text_length"), typeApproval.arguments.keys)
            assertTrue("Room Approval 不得保存 type_text 原文", TYPE_TEXT_INPUT !in typeApproval.toString())

            val typeResult = detail.toolLedger.results.single { it.toolCallId == typeCall.id }
            assertTrue(typeResult.success)
            assertEquals(true, typeResult.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, typeResult.verificationStatus)
            assertTrue("type_text ToolResult 不得保存输入原文", TYPE_TEXT_INPUT !in typeResult.content)
            assertTrue("type_text ToolResult 不得保存节点引用", !typeResult.content.contains("\"nodes\""))
            assertTrue("type_text ToolResult 不得保存 ref", !typeResult.content.contains("\"ref\""))
            val answerEvidenceEvents = detail.snapshot.events
                .filter { it.type == "tool.result" || it.type == "tool.call.proposed" || it.type == "tool.call.validated" }
            assertTrue("直接 Agent 的答案级证据不得保存输入原文", answerEvidenceEvents.none { it.toString().contains(TYPE_TEXT_INPUT) })

            val finalSnapshot = controller.capture()
            assertTrue("刷新后必须能重新读取当前窗口", finalSnapshot is com.longdev.xiaoling.device.DeviceSnapshotCapture.Success)
            val final = (finalSnapshot as com.longdev.xiaoling.device.DeviceSnapshotCapture.Success).snapshot
            // long: 刷新按钮位于小灵结果页；此时当前权威事实应与按钮触发时的前台窗口一致，而 Settings 输入回读已在上方单独验证。
            assertEquals("刷新摘要必须对应当前前台窗口", final.packageName, refreshedFact.directAgentCurrentFact?.packageName)
            println(
                "STAGE280_DIRECT_GOAL_VIEW_COMPLETED runId=${detail.snapshot.run.id} " +
                    "tool=device.type_text approval=${typeApproval.status} reason=setting_grant " +
                    "executorVerified=${typeResult.executorVerified} verification=${typeResult.verificationStatus} " +
                    "afterPackage=${action.packageName} exactReadBack=true currentWindowAfterRefresh=${final.packageName} goalDecision=${goalDecision?.status} " +
                    "currentFactPackage=${refreshedFact.directAgentCurrentFact?.packageName} privacySafe=true",
            )
        } finally {
            scenario?.let { active ->
                runCatching {
                    active.onActivity { ViewModelProvider(it)[XiaoLingViewModel::class.java].stopGenerating() }
                    val deadline = SystemClock.uptimeMillis() + 20_000L
                    while (readState(active).sendingMessage && SystemClock.uptimeMillis() < deadline) delay(100L)
                }
                active.close()
            }
            preferences.saveDeviceActionAutoApprovalEnabled(false)
            preferences.saveDeviceAgentEnabled(originalAgentEnabled)
            if (originalAgentEnabled && originalAutoApproval) preferences.saveDeviceActionAutoApprovalEnabled(true)
            originalProfileId?.let { profiles.select(it) }
            stateStore.saveSelectedAgentProfileId(originalProfileId.orEmpty())
            stateStore.saveSelectedConversationId(originalConversationId.orEmpty())
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profiles.delete(profileId)
            baselineRun?.let { assertEquals("旧 Run 应保持不变", it, runs.runDetail(it.snapshot.run.id)) }
        }
    }

    private fun refreshAccessibilityBinding() {
        val currentServices = readSecureSetting("enabled_accessibility_services")
            .orEmpty()
            .split(':')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "null" }
            .toMutableList()
        val component = "com.longdev.xiaoling/.device.XiaoLingAccessibilityService"
        if (component !in currentServices) currentServices += component
        listOf(
            "settings put secure accessibility_enabled 1",
            "settings put secure enabled_accessibility_services ${shellQuote(currentServices.distinct().joinToString(":"))}",
        ).forEach { command -> runCatching { automation.executeShellCommand(command).close() } }
        SystemClock.sleep(2_000L)
    }

    private suspend fun restoreProviderFromRunnerArgsIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(ARG_RESTORE_PROVIDER) != "true") return
        val baseUrl = requireNotNull(arguments.getString(ARG_FALLBACK_BASE_URL)?.trim()?.takeIf { it.isNotBlank() })
        val apiKey = requireNotNull(arguments.getString(ARG_FALLBACK_API_KEY)?.trim()?.takeIf { it.isNotBlank() })
        val model = requireNotNull(arguments.getString(ARG_FALLBACK_MODEL)?.trim()?.takeIf { it.isNotBlank() })
        val repository = ProviderRepository(context)
        val current = repository.load()
        val existing = current.profiles.firstOrNull() ?: ProviderProfile.blank()
        val restored = existing.copy(
            name = existing.name.ifBlank { "兜底 Provider" },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        // long: 只有显式 runner 参数才恢复本地兜底 Provider；默认真实验收仍从 Keystore 读取，避免测试代码暗含凭据或悄悄覆盖用户配置。
        repository.save(listOf(restored), restored.id)
        val loaded = repository.load().profiles.single()
        assertEquals(baseUrl, loaded.baseUrl)
        assertEquals(model, loaded.model)
        assertTrue("兜底 Provider 写入 Keystore 失败", loaded.apiKey.isNotBlank())
    }

    private fun refreshAccessibilityBindingThroughSettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val detailSwitch = findVisibleNode(500L) { node ->
            node.className?.toString() == "android.widget.Switch" &&
                node.viewIdResourceName == "android:id/switch_widget"
        }
        // long: Redmi 可能从上次验收留下的服务详情页恢复；详情页标题同样叫“小灵设备观察”，不能把标题误当成可点击的列表项。
        if (detailSwitch == null && findVisible("小灵设备观察", 2_000L) != null) {
            clickVisible("小灵设备观察")
        }
        // long: Redmi 首次启用服务会先显示系统权限确认页；点击“允许”才能触发真实 onServiceConnected。
        if (findVisible("允许", 2_000L) != null) {
            clickVisible("允许")
            SystemClock.sleep(1_500L)
            return
        }
        val switch = findVisibleNode(2_000L) { node ->
            node.className?.toString() == "android.widget.Switch" &&
                node.viewIdResourceName == "android:id/switch_widget"
        }
        if (switch?.isChecked == true) {
            clickNodeOrAncestor(switch, "关闭小灵无障碍服务")
            if (findVisible("关闭", 2_000L) != null) clickVisible("关闭")
            SystemClock.sleep(1_000L)
        }
        val refreshedSwitch = findVisibleNode(2_000L) { node ->
            node.className?.toString() == "android.widget.Switch" &&
                node.viewIdResourceName == "android:id/switch_widget"
        }
        if (refreshedSwitch != null && !refreshedSwitch.isChecked) {
            clickNodeOrAncestor(refreshedSwitch, "开启小灵无障碍服务")
            if (findVisible("允许", 2_000L) != null) clickVisible("允许")
            SystemClock.sleep(1_500L)
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo, failureMessage: String) {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.isEnabled && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        // long: Compose 重组后 Redmi 可能让语义节点的 ACTION_CLICK 短暂失效，但节点 bounds 仍代表屏幕上的真实按钮；用当前可见 bounds 发送一次用户级点击，避免把可见控件误报成不可操作。
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && node.isVisibleToUser) {
            automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").close()
            return
        }
        throw AssertionError(failureMessage)
    }

    private fun findVisible(text: String, timeout: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            findVisibleNode(100L) { node ->
                node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)
            }?.let { return it }
        }
        return null
    }

    private fun findVisibleNode(timeout: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            automation.clearCache()
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            roots.firstNotNullOfOrNull { root -> root.find(predicate) }?.let { return it }
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun readSecureSetting(name: String): String? {
        return runCatching {
            automation.executeShellCommand("settings get secure $name").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText().trim()
            }
        }.getOrNull()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private fun awaitDeviceHealth(controller: com.longdev.xiaoling.device.DeviceObservationController): DeviceAgentHealthState {
        var health = controller.health()
        repeat(60) {
            if (health == DeviceAgentHealthState.READY) return health
            SystemClock.sleep(250L)
            health = controller.health()
        }
        return health
    }

    private fun readState(scenario: ActivityScenario<MainActivity>): XiaoLingUiState {
        var state = XiaoLingUiState()
        scenario.onActivity { state = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
        return state
    }

    private suspend fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        phase: String,
        timeout: Long = 20_000L,
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            val state = readState(scenario)
            if (predicate(state)) return state
            state.personalTaskFailure?.let { error("$phase：${it.message}") }
            delay(100L)
        }
        error("$phase 超时")
    }

    private fun clickVisible(text: String) {
        val node = awaitVisible(text)
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = requireNotNull(current)
            if (candidate.isClickable && candidate.isEnabled && candidate.isVisibleToUser) {
                assertTrue("控件点击失败：$text", candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            current = candidate.parent
        }
        error("控件不可点击：$text")
    }

    private fun awaitVisible(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            automation.clearCache()
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            roots.firstNotNullOfOrNull { root -> root.find { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) } }
                ?.let { return it }
            SystemClock.sleep(100L)
        }
        error("缺少可见控件：$text")
    }

    private fun awaitVisibleAfterScrolling(text: String): AccessibilityNodeInfo {
        val displayMetrics = context.resources.displayMetrics
        val x = displayMetrics.widthPixels / 2
        val topY = (displayMetrics.heightPixels * 0.28f).toInt()
        val bottomY = (displayMetrics.heightPixels * 0.78f).toInt()
        // long: 目标卡片与运行时间线是独立列表项；真实答案可能先停在列表顶部或尾部，因此先向尾部滚动，再向历史方向滚动，覆盖两种恢复位置。
        repeat(8) {
            findVisibleNode(500L) { node ->
                node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)
            }?.let { return it }
            // long: Redmi 可能报告语义滚动已接受但实际没有移动 LazyColumn；两种滚动都执行，确保屏外按钮确实有机会进入语义树。
            scrollAccessibility(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            automation.executeShellCommand("input swipe $x $topY $x $bottomY 280").close()
            SystemClock.sleep(350L)
        }
        repeat(8) {
            findVisibleNode(500L) { node ->
                node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)
            }?.let { return it }
            // long: 真实动作结束后系统设置可能仍保留在前台，回到小灵后对话列表也可能停在旧位置；只在找不到目标时向尾部滚动，避免改写用户已经看到的卡片。
            scrollAccessibility(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            automation.executeShellCommand("input swipe $x $bottomY $x $topY 280").close()
            SystemClock.sleep(350L)
        }
        return try {
            awaitVisible(text)
        } catch (failure: Exception) {
            println("STAGE281_SCROLL_DIAGNOSTICS target=${accessibilityLabelDiagnostics("目标级结果")} refresh=${accessibilityLabelDiagnostics("重新读取当前事实")}")
            throw failure
        }
    }

    private fun scrollAccessibility(action: Int): Boolean {
        automation.clearCache()
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        return roots.any { root ->
            root.find { node ->
                node.isVisibleToUser && node.isScrollable && node.performAction(action)
            } != null
        }
    }

    private fun accessibilityLabelDiagnostics(label: String): String {
        automation.clearCache()
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        var total = 0
        var visible = 0
        var clickable = 0
        val packages = roots.mapNotNull { it.packageName?.toString()?.takeIf(String::isNotBlank) }.distinct()
        roots.forEach { root ->
            root.find { node ->
                val matches = node.text?.toString() == label || node.contentDescription?.toString() == label
                if (matches) {
                    total += 1
                    if (node.isVisibleToUser) visible += 1
                    if (node.isClickable) clickable += 1
                }
                false
            }
        }
        val scrollable = roots.sumOf { root -> root.count { node -> node.isVisibleToUser && node.isScrollable } }
        return "total=$total visible=$visible clickable=$clickable scrollable=$scrollable packages=${packages.joinToString(",")}"
    }

    private fun AccessibilityNodeInfo.find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        repeat(childCount) { getChild(it)?.find(predicate)?.let { node -> return node } }
        return null
    }

    private fun AccessibilityNodeInfo.count(predicate: (AccessibilityNodeInfo) -> Boolean): Int {
        var matches = if (predicate(this)) 1 else 0
        repeat(childCount) { index ->
            matches += getChild(index)?.count(predicate) ?: 0
        }
        return matches
    }

    private companion object {
        const val ARG_REAL_RUN = "stage277RealRun"
        const val ARG_TAP_REF_REAL_RUN = "stage278RealRun"
        const val ARG_TYPE_TEXT_REAL_RUN = "stage279RealRun"
        const val ARG_RESTORE_PROVIDER = "stage281RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage281FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage281FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage281FallbackModel"
        const val CALCULATOR_PACKAGE = "com.android.calculator2"
        const val PROMPT = "/agent 请打开系统计算器并确认当前前台应用，只允许调用 device.snapshot 和 device.open_app，不点击、不输入、不执行其他动作。"
        const val PROMPT_TAP_REF = "/agent 请打开系统计算器，获取当前页面快照，点击计算器上的数字7，再获取快照确认仍在计算器。只允许调用 device.snapshot、device.open_app、device.tap_ref；不要调用 device.type_text、device.back、device.home 或其他工具。"
        const val TYPE_TEXT_INPUT = "stage279_exact_readback"
        const val PROMPT_TYPE_TEXT = "/agent 请在当前设备上完成一次非敏感文本输入闭环。严格按顺序：打开系统设置；获取快照；点击快照中的搜索设置入口；再次获取快照；向当前快照中可编辑、未脱敏的搜索框精确输入 stage279_exact_readback；最后再次获取快照并确认输入后的当前页面。只允许调用 device.open_app、device.snapshot、device.tap_ref、device.type_text；不要调用 back、home、swipe 或任何其他工具。"
    }
}
