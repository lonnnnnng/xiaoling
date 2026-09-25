package com.longdev.xiaoling.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.WorkflowDeviceActionResultCodec
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.device.DeviceActionPolicy
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceObservationComponents
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 用请求包与 Redmi 实际包不同的时钟同族验证目标级完成判定，真实动作仍必须走前台审批和后置观察。 */
@RunWith(AndroidJUnit4::class)
class Stage267EquivalentPackageInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    // long: UiAutomation 不接管小灵的 AccessibilityService，避免真实设备动作验收因测试框架默认抑制服务而失去观察能力。
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
            it.serviceInfo = it.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }

    @Test
    fun googleClockTargetVerifiesAospClockImplementation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅显式 stage267RealRun=true 运行真实模型", args.getString(ARG_REAL_RUN) == "true")
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        // long: 必须在第一次读取 Accessibility 健康前建立不抑制服务的 UiAutomation，否则 Android 测试框架会先把小灵服务置为未授权。
        automation.clearCache()
        refreshAccessibilityBindingForInstrumentation()
        val deviceController = DeviceObservationComponents.controller(context)
        automation.clearCache()
        var health = awaitDeviceHealth(deviceController)
        if (health != DeviceAgentHealthState.READY) {
            // long: instrumentation 启动会先停止被测包，系统可能留下已授权但未绑定的服务；通过设置页重新切换一次，恢复真实服务生命周期而不改写安全配置。
            refreshAccessibilityBinding()
            health = awaitDeviceHealth(deviceController)
        }
        assertEquals("请在系统中授权小灵无障碍，并启用设备 Agent（当前=$health）", DeviceAgentHealthState.READY, health)

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
        val profileId = "stage267-equivalent-clock-$now"
        val conversationId = "conversation-$profileId"
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            profiles.upsert(
                AgentProfileRecord(
                    id = profileId,
                    name = "第267阶段等价时钟任务",
                    avatar = "267",
                    providerId = provider.id,
                    model = provider.model,
                    apiMode = ApiMode.RESPONSES,
                    systemPrompt = "只完成当前打开时钟任务。目标应用包名必须严格使用 com.google.android.deskclock；如果该包没有启动入口，应用会按已登记同族回落到 com.android.deskclock。只调用 device.open_app 和必要的 device.snapshot，不执行其他动作。",
                    contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                    allowedToolNames = DEVICE_TOOLS,
                    allowedSkillIds = listOf("device-control"),
                    memoryEnabled = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            assertTrue(profiles.select(profileId))
            database.conversationDao().insertConversations(
                listOf(ConversationEntity(conversationId, "新会话", "", null, null, null, now, now)),
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
                    updatePersonalTaskMode(true)
                    updatePrompt(GOAL)
                }
            }

            clickVisible("发送")
            val plan = requireNotNull(
                awaitState(scenario, "计划生成", 120_000L) { it.pendingPersonalTaskPlan != null }
                    .pendingPersonalTaskPlan,
            )
            println(
                "STAGE267_PLAN target=${plan.targetAppPackage} expected=${plan.goalVerificationSpec?.expectedFinalPackageName} " +
                    "tools=${plan.goalVerificationSpec?.requiredToolNames} steps=${plan.steps}",
            )
            assertEquals(GOAL, plan.sourceGoal)
            assertEquals(EXPECTED_PACKAGE, plan.targetAppPackage)
            assertEquals(EXPECTED_PACKAGE, plan.goalVerificationSpec?.expectedFinalPackageName)
            assertTrue(plan.steps.isNotEmpty())
            assertEquals(
                listOf(DEVICE_OPEN_APP_TOOL_NAME),
                requireNotNull(plan.goalVerificationSpec).requiredToolNames.filter { it != DEVICE_SNAPSHOT_TOOL_NAME },
            )
            assertTrue(
                "确认前不应创建 Workflow",
                workflows.recentRunDetails(20).none { it.run.conversationId == conversationId },
            )

            awaitVisible("确认并执行")
            clickVisible("确认并执行")
            val approvedIds = mutableSetOf<String>()
            val deadline = SystemClock.uptimeMillis() + 240_000L
            var completed = false
            while (SystemClock.uptimeMillis() < deadline) {
                val current = readState(scenario)
                current.personalTaskFailure?.let { error("个人任务失败：${it.message}") }
                val agentDetails = runs.recentRunDetails(20).filter { it.snapshot.run.conversationId == conversationId }
                val pending = agentDetails.flatMap { it.approvals }
                    .singleOrNull { it.status == ApprovalRequestStatus.PENDING }
                if (pending != null && pending.id !in approvedIds) {
                    assertEquals(DEVICE_OPEN_APP_TOOL_NAME, pending.toolName)
                    assertEquals(mapOf("package_name" to EXPECTED_PACKAGE), pending.arguments)
                    awaitVisible("小灵设备动作审批")
                    clickVisible("批准执行")
                    approvedIds += pending.id
                    println("STAGE267_APPROVED tool=${pending.toolName} runId=${pending.runId}")
                }
                if (!current.sendingMessage && current.personalTaskCompletion != null) {
                    completed = true
                    break
                }
                agentDetails.firstOrNull {
                    it.snapshot.run.status.isTerminal && it.snapshot.run.status != AgentRunStatus.COMPLETED
                }?.let { error("Agent 提前终止：${it.snapshot.run.status} ${it.snapshot.run.errorMessage}") }
                delay(150L)
            }
            assertTrue("等价包族时钟任务超时", completed)

            val workflow = workflows.recentRunDetails(20).single { it.run.conversationId == conversationId }
            assertEquals(WorkflowRunStatus.COMPLETED, workflow.run.status)
            assertEquals(WorkflowGoalVerificationStatus.VERIFIED, workflow.run.goalVerificationDecision?.status)
            assertTrue(workflow.steps.all { it.status == WorkflowStepStatus.COMPLETED })
            val details = workflow.steps.sortedBy { it.sequence }
                .map { requireNotNull(runs.runDetail(requireNotNull(it.agentRunId))) }
            val openAppDetail = details.single { detail ->
                detail.toolLedger.calls.any { it.toolName == DEVICE_OPEN_APP_TOOL_NAME }
            }
            val openAppCall = openAppDetail.toolLedger.calls.single { it.toolName == DEVICE_OPEN_APP_TOOL_NAME }
            assertEquals(mapOf("package_name" to EXPECTED_PACKAGE), openAppCall.arguments)
            assertEquals(ToolRisk.REQUIRES_APPROVAL, openAppCall.risk)
            val openAppResult = openAppDetail.toolLedger.results.single { it.toolCallId == openAppCall.id }
            assertTrue(openAppResult.success)
            assertEquals(true, openAppResult.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, openAppResult.verificationStatus)
            assertEquals(ApprovalRequestStatus.APPROVED, openAppDetail.approvals.single { it.toolCallId == openAppCall.id }.status)
            val actionEvidence = requireNotNull(WorkflowDeviceActionResultCodec.decode(openAppResult.content))
            assertEquals(DEVICE_OPEN_APP_TOOL_NAME.removePrefix("device."), actionEvidence.action)
            assertEquals(context.packageName, actionEvidence.beforePackageName)
            assertTrue(actionEvidence.verified)
            assertTrue(
                DeviceActionPolicy.areEquivalentAppPackages(EXPECTED_PACKAGE, actionEvidence.afterPackageName),
            )
            assertEquals(
                actionEvidence.afterPackageName,
                workflow.run.goalVerificationDecision?.actualFinalPackageName,
            )
            assertEquals(EXPECTED_PACKAGE, workflow.run.goalVerificationDecision?.expectedFinalPackageName)
            assertEquals(WorkflowGoalVerificationStatus.VERIFIED, workflow.run.goalVerificationDecision?.status)

            val activePackages = currentRoots().mapNotNull { it.packageName?.toString() }.toSet()
            assertTrue(
                "当前前台未停留在已登记时钟包族：$activePackages",
                activePackages.any { DeviceActionPolicy.areEquivalentAppPackages(EXPECTED_PACKAGE, it) },
            )
            println(
                "STAGE267_COMPLETED workflowRunId=${workflow.run.id} approval=${approvedIds.size} " +
                    "requestedPackage=$EXPECTED_PACKAGE actualPackage=${actionEvidence.afterPackageName} " +
                    "goalDecision=${workflow.run.goalVerificationDecision?.status} equivalentPackage=true",
            )
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
            workflows.recentRunDetails(20)
                .filter { it.run.conversationId == conversationId }
                .forEach { workflows.setEnabled(it.run.workflowId, false) }
            originalProfile?.let { profiles.select(it) }
            stateStore.saveSelectedAgentProfileId(originalProfile.orEmpty())
            stateStore.saveSelectedConversationId(originalConversation.orEmpty())
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profiles.delete(profileId)
            baselineRun?.let { assertEquals("旧 Run 应保持不变", it, runs.runDetail(it.snapshot.run.id)) }
            println("STAGE267_CLEANUP fixtureRemoved=true originalSelectionRestored=true auditPreserved=true")
        }
    }

    private fun readState(scenario: ActivityScenario<MainActivity>): XiaoLingUiState {
        var state = XiaoLingUiState()
        scenario.onActivity { state = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
        return state
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

    private fun refreshAccessibilityBinding() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (findVisible("启用“小灵设备观察”", 2_000L) == null) {
            clickVisible("小灵设备观察")
        }
        val switch = findVisibleNode(2_000L) { node ->
            node.className?.toString() == "android.widget.Switch" &&
                node.viewIdResourceName == "android:id/switch_widget"
        }
        if (switch?.isChecked == true) {
            clickVisible("启用“小灵设备观察”")
            if (findVisible("关闭", 2_000L) != null) clickVisible("关闭")
            SystemClock.sleep(1_000L)
        }
        if (findVisible("启用“小灵设备观察”", 2_000L) != null) {
            clickVisible("启用“小灵设备观察”")
        }
    }

    private fun refreshAccessibilityBindingForInstrumentation() {
        // long: Android Runner 启动目标包时可能暂时关闭已授权服务；测试仅重绑原授权组件，不新增权限或修改用户选择的其他服务。
        listOf(
            "settings put secure accessibility_enabled 1",
            "settings delete secure enabled_accessibility_services",
            "settings put secure enabled_accessibility_services com.longdev.xiaoling/.device.XiaoLingAccessibilityService",
        ).forEach { command ->
            runCatching { automation.executeShellCommand(command).close() }
        }
        SystemClock.sleep(2_000L)
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

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        automation.clearCache()
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)).distinct()
    }

    private fun awaitVisible(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            currentRoots().firstNotNullOfOrNull { root ->
                root.find { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
            }?.let { return it }
            SystemClock.sleep(100L)
        }
        error("缺少可见控件：$text")
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

    private fun AccessibilityNodeInfo.find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        repeat(childCount) { getChild(it)?.find(predicate)?.let { node -> return node } }
        return null
    }

    private companion object {
        const val ARG_REAL_RUN = "stage267RealRun"
        const val EXPECTED_PACKAGE = "com.google.android.deskclock"
        const val GOAL = "打开 Google 时钟（目标包名必须是 com.google.android.deskclock；如果该包不可启动则使用已登记同族实现），只确认当前前台时钟页面，不执行其他设备动作。"
        const val DEVICE_OPEN_APP_TOOL_NAME = "device.open_app"
        const val DEVICE_SNAPSHOT_TOOL_NAME = "device.snapshot"
        val DEVICE_TOOLS = listOf(
            DEVICE_SNAPSHOT_TOOL_NAME,
            DEVICE_OPEN_APP_TOOL_NAME,
            "device.back",
            "device.home",
            "device.tap_ref",
            "device.type_text",
            "device.swipe",
        )
    }
}
