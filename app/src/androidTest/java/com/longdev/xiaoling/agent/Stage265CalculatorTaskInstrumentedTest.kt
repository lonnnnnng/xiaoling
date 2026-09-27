package com.longdev.xiaoling.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceObservationComponents
import com.longdev.xiaoling.device.DeviceSnapshotCodec
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.storage.UiPreferenceStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 测试先用计算器真实节点预置非空算式；正式任务动作仍由 Workflow 执行，验收只操作计划确认和审批控件并独立核对结果。 */
@RunWith(AndroidJUnit4::class)
class Stage265CalculatorTaskInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var accessibilityServicesBeforeInstrumentation: String? = null
    private var accessibilityEnabledBeforeInstrumentation: String? = null
    // long: 默认 UiAutomation 会停用被测无障碍服务；整条链始终复用保留服务的连接，避免验收自身取消设备审批。
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
            it.serviceInfo = it.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
    }

    @After
    fun restoreAccessibilityStateAfterTest() {
        // long: 即使 Provider 配置或前置断言在主 try 之前失败，也必须恢复测试临时重绑的无障碍环境。
        restoreAccessibilityBindingAfterInstrumentation()
    }

    @Test
    fun calculatorTaskPlansAndExecutesWithVisibleApprovals() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅显式 stage265RealRun=true 运行真实模型", args.getString("stage265RealRun") == "true")
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        val planOnly = args.getString("stage265PlanOnly") == "true"
        val autoApprove = args.getString("stage265AutoApprove") == "true"
        val preferences = UiPreferenceStore(context)
        val originalAgentEnabled = preferences.loadDeviceAgentEnabled()
        val originalAutoApproval = preferences.loadDeviceActionAutoApprovalEnabled()
        if (autoApprove) {
            // long: 仅在显式真实验收参数下临时复现用户已授予的设置授权，生产 Gate 仍实时读取同一开关，结束后恢复原偏好。
            preferences.saveDeviceAgentEnabled(true)
            preferences.saveDeviceActionAutoApprovalEnabled(true)
        }
        automation.clearCache()
        if (!planOnly) {
            // long: Runner 启动可能暂时断开无障碍服务；只重绑用户此前已授权的小灵服务，不改写其他服务，随后再读取健康状态。
            refreshAccessibilityBindingForInstrumentation()
            val controller = DeviceObservationComponents.controller(context)
            var health = awaitDeviceHealth(controller)
            if (health != DeviceAgentHealthState.READY) {
                // long: Redmi 可能接受 secure setting 写入但不重新拉起服务；通过系统无障碍页做一次真实关闭/开启，恢复绑定而不伪造 Runtime 连接。
                refreshAccessibilityBindingThroughSettings()
                health = awaitDeviceHealth(controller)
            }
            assertEquals("请在系统中授权小灵无障碍，并启用设备 Agent（当前=$health）", DeviceAgentHealthState.READY, health)
        }
        val providers = ProviderRepository(context).load()
        val provider = requireNotNull(providers.profiles.singleOrNull { it.id == providers.selectedProfileId })
        assertTrue("当前 Provider 凭据不可用", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank())
        assertEquals("模型须与本地 AGENTS 配置一致", "gpt-5.6-luna", provider.model)
        val database = XiaoLingDatabase.getInstance(context)
        val profiles = RoomAgentProfileStore(context)
        val stateStore = RoomStateStore(context)
        val runs = RoomAgentRunRepository(context)
        val workflows = RoomWorkflowRepository(context)
        val originalProfile = stateStore.selectedAgentProfileId()
        val originalConversation = stateStore.selectedConversationId()
        val baselineRun = runs.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage265-calculator-$now"
        val conversationId = "conversation-$profileId"
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            profiles.upsert(
                AgentProfileRecord(
                    id = profileId, name = "第265阶段计算器任务", avatar = "265",
                    providerId = provider.id, model = provider.model, apiMode = ApiMode.RESPONSES,
                    systemPrompt = "只使用 device-control 完成当前已确认步骤，在系统计算器内根据实时节点操作。每步先观察，只完成该步动作并核对实际结果，不提前执行后续步骤。",
                    contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                    allowedToolNames = DEVICE_TOOLS, allowedSkillIds = listOf("device-control"),
                    memoryEnabled = false, createdAt = now, updatedAt = now,
                ),
            )
            assertTrue(profiles.select(profileId))
            database.conversationDao().insertConversations(listOf(ConversationEntity(conversationId, "新会话", "", null, null, null, now, now)))
            stateStore.saveSelectedAgentProfileId(profileId)
            stateStore.saveSelectedConversationId(conversationId)
            if (!planOnly) prepareNonEmptyCalculator()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, "会话加载") { it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId && !it.loadingConversationMessages }
            scenario.onActivity {
                ViewModelProvider(it)[XiaoLingViewModel::class.java].apply {
                    updatePersonalTaskMode(true)
                    updatePrompt(GOAL)
                }
            }
            clickVisible("发送")
            val planned = requireNotNull(awaitState(scenario, "计划生成", 120_000L) { it.pendingPersonalTaskPlan != null }.pendingPersonalTaskPlan)
            println("STAGE265_PLAN_CANDIDATE steps=${planned.steps.size} target=${planned.targetAppPackage} tools=${planned.goalVerificationSpec?.requiredToolNames} goals=${planned.steps}")
            assertEquals(GOAL, planned.sourceGoal)
            assertEquals(PACKAGE, planned.targetAppPackage)
            assertEquals(PACKAGE, planned.goalVerificationSpec?.expectedFinalPackageName)
            assertEquals(null, planned.reminderScheduleLabel)
            assertTrue("设备动作应分成独立步骤：${planned.steps}", planned.steps.size in 6..8)
            val required = requireNotNull(planned.goalVerificationSpec).requiredToolNames.filter { it != "device.snapshot" }
            assertEquals(listOf("device.open_app") + List(5) { "device.tap_ref" }, required)
            assertTrue("确认前不应创建 Workflow", workflows.recentRunDetails(20).none { it.run.conversationId == conversationId })
            awaitVisible("确认并执行")
            screenshot("stage265-plan.png")
            println("STAGE265_PLAN model=${provider.model} steps=${planned.steps.size} target=$PACKAGE tools=$required goals=${planned.steps}")
            if (planOnly) {
                clickVisible("返回修改")
                assertTrue(workflows.recentRunDetails(20).none { it.run.conversationId == conversationId })
                println("STAGE265_PLAN_ONLY confirmed=false deviceActions=0")
                return@runBlocking
            }

            clickVisible("确认并执行")
            val approvedIds = mutableSetOf<String>()
            val deadline = SystemClock.uptimeMillis() + 600_000L
            var completed = false
            while (SystemClock.uptimeMillis() < deadline) {
                val current = readState(scenario)
                current.personalTaskFailure?.let { failure ->
                    val latest = runs.recentRunDetails(20).firstOrNull { it.snapshot.run.conversationId == conversationId }
                    val result = latest?.toolLedger?.results?.lastOrNull()
                    error(
                        "个人任务失败：${failure.message}；最近工具=${result?.toolName} " +
                            "success=${result?.success} executorVerified=${result?.executorVerified} " +
                            "verification=${result?.verificationStatus} content=${result?.content?.take(500)}",
                    )
                }
                val details = runs.recentRunDetails(20).filter { it.snapshot.run.conversationId == conversationId }
                val pending = details.flatMap { it.approvals }.singleOrNull { it.status == ApprovalRequestStatus.PENDING }
                if (pending != null && pending.id !in approvedIds) {
                    assertTrue(pending.toolName in setOf("device.open_app", "device.tap_ref"))
                    if (pending.toolName == "device.open_app") assertEquals(mapOf("package_name" to PACKAGE), pending.arguments)
                    if (autoApprove) {
                        // long: 自动授权由生产 Accessibility 守护窗口完成，测试不点击批准按钮，只等待 Room 决定落盘。
                        println("STAGE265_AUTO_APPROVAL_PENDING tool=${pending.toolName} runId=${pending.runId}")
                    } else {
                        awaitVisible("小灵设备动作审批")
                        screenshot("stage265-approval-${approvedIds.size + 1}.png")
                        clickVisible("批准执行")
                        approvedIds += pending.id
                        println("STAGE265_APPROVED tool=${pending.toolName} runId=${pending.runId}")
                    }
                }
                if (autoApprove) {
                    details.flatMap { it.approvals }
                        .filter { it.status == ApprovalRequestStatus.APPROVED }
                        .forEach { approvedIds += it.id }
                }
                if (!current.sendingMessage && current.personalTaskCompletion != null) {
                    completed = true
                    break
                }
                details.firstOrNull { it.snapshot.run.status.isTerminal && it.snapshot.run.status != AgentRunStatus.COMPLETED }?.let {
                    val evidence = details.flatMap { detail ->
                        detail.toolLedger.results.map { result ->
                            "${result.toolName}:success=${result.success},executorVerified=${result.executorVerified},verification=${result.verificationStatus}"
                        }
                    }.joinToString(";")
                    error("Agent 提前终止：${it.snapshot.run.status} ${it.snapshot.run.errorMessage}；工具证据=$evidence")
                }
                delay(150L)
            }
            assertTrue("计算器任务超时", completed)
            val workflow = workflows.recentRunDetails(20).single { it.run.conversationId == conversationId }
            assertEquals(WorkflowRunStatus.COMPLETED, workflow.run.status)
            assertEquals(WorkflowGoalVerificationStatus.VERIFIED, workflow.run.goalVerificationDecision?.status)
            assertTrue(workflow.steps.all { it.status == WorkflowStepStatus.COMPLETED })
            val details = workflow.steps.sortedBy { it.sequence }.map { requireNotNull(runs.runDetail(requireNotNull(it.agentRunId))) }
            val tappedLabels = mutableListOf<String>()
            var finalSnapshot: JSONObject? = null
            details.forEach { detail ->
                assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
                assertTrue("单步工具预算不应扩大", detail.toolLedger.calls.size <= 4)
                assertTrue(detail.toolLedger.calls.count { it.toolName != "device.snapshot" } <= 1)
                assertTrue(detail.toolLedger.results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
                detail.toolLedger.calls.forEach { call ->
                    val result = detail.toolLedger.results.single { it.toolCallId == call.id }
                    if (call.toolName == "device.tap_ref") {
                        val snapshot = detail.toolLedger.results.filter { it.toolName == "device.snapshot" }.map { JSONObject(it.content) }
                            .single { it.getString("snapshot_id") == call.arguments["snapshot_id"] }
                        assertEquals(PACKAGE, snapshot.getString("package"))
                        val node = snapshot.getJSONArray("nodes").let { nodes ->
                            (0 until nodes.length()).map { nodes.getJSONObject(it) }.single { it.optString("ref") == call.arguments["ref"] }
                        }
                        assertFalse(node.getBoolean("redacted"))
                        val label = node.getString("text")
                        if (label == "=") assertTrue("等号前必须是本次算式", snapshot.hasText("7×8"))
                        tappedLabels += label
                    }
                    if (call.toolName != "device.snapshot") {
                        assertEquals(true, result.executorVerified)
                        val approval = detail.approvals.single { it.toolCallId == call.id }
                        assertEquals(ApprovalRequestStatus.APPROVED, approval.status)
                        if (autoApprove) {
                            assertTrue(
                                "自动授权审批必须记录设置来源：$approval",
                                approval.decisionReason?.contains("设置中授权") == true,
                            )
                        }
                        // long: Workflow 生产动作结果只持久化脱敏摘要；完整 after snapshot 由动作后的独立 device.snapshot ToolResult 提供，避免测试依赖旧版 DeviceActionCodec。
                    } else {
                        finalSnapshot = JSONObject(result.content)
                    }
                }
            }
            assertTrue("只能依次点击本次算式的按键：$tappedLabels", tappedLabels == listOf("7", "×", "8", "=") || tappedLabels == listOf("AC", "7", "×", "8", "="))
            val observed = requireNotNull(finalSnapshot)
            assertEquals(PACKAGE, DeviceSnapshotCodec.decodeSummary(observed.toString())?.packageName)
            assertTrue("动作后快照必须包含实际结果", observed.hasText("56"))
            // long: 工具/包名验证不能证明数字正确；独立读取计算器结果控件，防止模型心算或聊天文字冒充当前 App 结果。
            assertTrue("当前计算器结果控件没有显示56", currentRoots().any { root ->
                root.find { it.isVisibleToUser && it.text?.toString() == "56" && it.viewIdResourceName in setOf("$PACKAGE:id/formula", "$PACKAGE:id/result") } != null
            })
            screenshot("stage265-result.png")
            println("STAGE265_COMPLETED workflowRunId=${workflow.run.id} steps=${details.size} approvals=${approvedIds.size} taps=$tappedLabels formula=7x8 result=56 currentScreenVerified=true")
        } finally {
            scenario?.let { active ->
                try {
                    active.onActivity { ViewModelProvider(it)[XiaoLingViewModel::class.java].stopGenerating() }
                    val stopDeadline = SystemClock.uptimeMillis() + 20_000L
                    while (readState(active).sendingMessage && SystemClock.uptimeMillis() < stopDeadline) delay(100L)
                } finally {
                    active.close()
                }
            }
            restoreAccessibilityBindingAfterInstrumentation()
            workflows.recentRunDetails(20).filter { it.run.conversationId == conversationId }.forEach { workflows.setEnabled(it.run.workflowId, false) }
            originalProfile?.let { profiles.select(it) }
            stateStore.saveSelectedAgentProfileId(originalProfile.orEmpty())
            stateStore.saveSelectedConversationId(originalConversation.orEmpty())
            preferences.saveDeviceActionAutoApprovalEnabled(false)
            preferences.saveDeviceAgentEnabled(originalAgentEnabled)
            if (originalAgentEnabled && originalAutoApproval) {
                preferences.saveDeviceActionAutoApprovalEnabled(true)
            }
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profiles.delete(profileId)
            baselineRun?.let { assertEquals("旧 Run 应保持不变", it, runs.runDetail(it.snapshot.run.id)) }
            println("STAGE265_CLEANUP fixtureRemoved=true originalSelectionRestored=true auditPreserved=true")
        }
    }

    private fun readState(scenario: ActivityScenario<MainActivity>): XiaoLingUiState {
        var state = XiaoLingUiState()
        scenario.onActivity { state = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
        return state
    }

    private suspend fun awaitState(scenario: ActivityScenario<MainActivity>, phase: String, timeout: Long = 20_000L, predicate: (XiaoLingUiState) -> Boolean): XiaoLingUiState {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            val state = readState(scenario)
            if (predicate(state)) return state
            state.personalTaskFailure?.let { error("$phase：${it.message}") }
            delay(100L)
        }
        error("$phase 超时")
    }

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        automation.clearCache()
        // long: Redmi 的 UiAutomation 可能保留计算器切页前的节点快照；逐根 refresh 后再查找，避免把当前可点击按键误判为不可点击。
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .distinct()
            .onEach { it.refresh() }
    }

    private fun refreshAccessibilityBindingForInstrumentation() {
        // long: Android Runner 进入被测包时可能会停止已授权服务；保留用户已有服务，只把小灵组件放回原列表，避免验收破坏其他无障碍能力。
        accessibilityServicesBeforeInstrumentation = readSecureSetting("enabled_accessibility_services")
        accessibilityEnabledBeforeInstrumentation = readSecureSetting("accessibility_enabled")
        val xiaolingComponent = "com.longdev.xiaoling/.device.XiaoLingAccessibilityService"
        val preservedServices = accessibilityServicesBeforeInstrumentation.orEmpty()
            .split(':')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "null" }
            .toMutableList()
        if (xiaolingComponent !in preservedServices) preservedServices += xiaolingComponent
        val reboundServices = preservedServices.distinct().joinToString(":")
        listOf(
            "settings put secure accessibility_enabled 1",
            "settings delete secure enabled_accessibility_services",
            "settings put secure enabled_accessibility_services ${shellQuote(reboundServices)}",
        ).forEach { command ->
            runCatching { automation.executeShellCommand(command).close() }
        }
        SystemClock.sleep(2_000L)
    }

    private fun refreshAccessibilityBindingThroughSettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (findVisible("小灵设备观察", 2_000L) != null) {
            clickVisible("小灵设备观察")
        }
        // long: Redmi 首次启用服务显示的是系统权限确认页，不是带“启用”文案的开关；必须点击系统“允许”才能触发 onServiceConnected。
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
        } else if (switch != null) {
            clickNodeOrAncestor(switch, "开启小灵无障碍服务")
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
            currentRoots().firstNotNullOfOrNull { root -> root.find { predicate(it) } }?.let { return it }
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun restoreAccessibilityBindingAfterInstrumentation() {
        val originalServices = accessibilityServicesBeforeInstrumentation ?: return
        // long: 测试只临时重绑服务；收尾时恢复进入测试前的无障碍列表和开关，避免验收改变用户设备环境。
        buildList {
            add("settings delete secure enabled_accessibility_services")
            originalServices.takeUnless { it == "null" }.orEmpty().takeIf(String::isNotBlank)?.let { services ->
                add("settings put secure enabled_accessibility_services ${shellQuote(services)}")
            }
            add("settings put secure accessibility_enabled ${accessibilityEnabledBeforeInstrumentation.takeUnless { it == "null" }.orEmpty().ifBlank { "0" }}")
        }.forEach { command ->
            runCatching { automation.executeShellCommand(command).close() }
        }
        accessibilityServicesBeforeInstrumentation = null
        accessibilityEnabledBeforeInstrumentation = null
    }

    private fun readSecureSetting(name: String): String = runCatching {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("settings get secure $name"))
            .bufferedReader()
            .use { it.readText().trim() }
    }.getOrDefault("")

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private fun prepareNonEmptyCalculator() {
        val launchIntent = requireNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        // long: 本轮目标明确要求清空键每次都点击；先用测试夹具留下非空算式，才能把后续清空判定与真实页面变化绑定。
        clickVisibleNode("数字7按钮") { node -> node.viewIdResourceName == "$PACKAGE:id/digit_7" }
        val dirty = currentRoots().any { root ->
            root.find {
                it.isVisibleToUser && it.viewIdResourceName in setOf("$PACKAGE:id/formula", "$PACKAGE:id/result") &&
                    !it.text.isNullOrBlank()
            } != null
        }
        assertTrue("计算器前置算式没有变为非空，无法验收清空动作", dirty)
        println("STAGE265_FIXTURE calculatorDirty=true action=click_visible_digit_7")
    }

    private fun awaitDeviceHealth(controller: com.longdev.xiaoling.device.DeviceObservationController): DeviceAgentHealthState {
        var health = controller.health()
        repeat(60) {
            if (health == DeviceAgentHealthState.READY) return health
            SystemClock.sleep(250L)
            health = controller.health()
        }
        return health
    }

    private fun awaitVisible(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            currentRoots().firstNotNullOfOrNull { root -> root.find { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) } }?.let { return it }
            SystemClock.sleep(100L)
        }
        error("缺少可见控件：$text")
    }

    private fun clickVisible(text: String) {
        var node: AccessibilityNodeInfo? = awaitVisible(text)
        repeat(6) {
            val current = requireNotNull(node)
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) {
                assertTrue("控件点击失败：$text", current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            node = current.parent
        }
        error("控件不可点击：$text")
    }

    private fun clickVisibleNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            currentRoots().firstNotNullOfOrNull { root ->
                root.find { it.isVisibleToUser && predicate(it) }
            }?.let { node ->
                clickNodeOrAncestor(node, "控件不可点击：$description")
                return
            }
            SystemClock.sleep(100L)
        }
        error("缺少可见控件：$description")
    }

    private fun AccessibilityNodeInfo.find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        repeat(childCount) { getChild(it)?.find(predicate)?.let { node -> return node } }
        return null
    }

    private fun JSONObject.hasText(expected: String): Boolean = getJSONArray("nodes").let { nodes ->
        (0 until nodes.length()).any { index -> nodes.getJSONObject(index).let { !it.getBoolean("redacted") && it.optString("text") == expected } }
    }

    private fun screenshot(name: String) {
        val path = "/data/local/tmp/$name"
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p $path")).use { it.readBytes() }
    }

    private companion object {
        const val PACKAGE = "com.android.calculator2"
        const val GOAL = "打开系统计算器（com.android.calculator2），先始终点击一次清空键确保当前算式为空，然后依次点击7、乘法运算符、8和等号计算7乘8。完成后停留在计算器并读取实际屏幕结果。"
        val DEVICE_TOOLS = listOf("device.snapshot", "device.open_app", "device.back", "device.home", "device.tap_ref", "device.type_text", "device.swipe")
    }
}
