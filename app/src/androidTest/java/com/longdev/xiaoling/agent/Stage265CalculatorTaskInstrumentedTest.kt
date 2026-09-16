package com.longdev.xiaoling.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 指定计算器任务必须由正式 Workflow 执行动作，验收只操作计划确认和审批控件，并独立核对当前计算结果。 */
@RunWith(AndroidJUnit4::class)
class Stage265CalculatorTaskInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    // long: 默认 UiAutomation 会停用被测无障碍服务；整条链始终复用保留服务的连接，避免验收自身取消设备审批。
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
            it.serviceInfo = it.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
    }

    @Test
    fun calculatorTaskPlansAndExecutesWithVisibleApprovals() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅显式 stage265RealRun=true 运行真实模型", args.getString("stage265RealRun") == "true")
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        val planOnly = args.getString("stage265PlanOnly") == "true"
        automation.clearCache()
        if (!planOnly) {
            assertEquals("请在系统中授权小灵无障碍，并启用设备 Agent", DeviceAgentHealthState.READY, DeviceObservationComponents.controller(context).health())
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
                current.personalTaskFailure?.let { error("个人任务失败：${it.message}") }
                val details = runs.recentRunDetails(20).filter { it.snapshot.run.conversationId == conversationId }
                val pending = details.flatMap { it.approvals }.singleOrNull { it.status == ApprovalRequestStatus.PENDING }
                if (pending != null && pending.id !in approvedIds) {
                    assertTrue(pending.toolName in setOf("device.open_app", "device.tap_ref"))
                    if (pending.toolName == "device.open_app") assertEquals(mapOf("package_name" to PACKAGE), pending.arguments)
                    awaitVisible("小灵设备动作审批")
                    screenshot("stage265-approval-${approvedIds.size + 1}.png")
                    clickVisible("批准执行")
                    approvedIds += pending.id
                    println("STAGE265_APPROVED tool=${pending.toolName} runId=${pending.runId}")
                }
                if (!current.sendingMessage && current.personalTaskCompletion != null) {
                    completed = true
                    break
                }
                details.firstOrNull { it.snapshot.run.status.isTerminal && it.snapshot.run.status != AgentRunStatus.COMPLETED }?.let {
                    error("Agent 提前终止：${it.snapshot.run.status} ${it.snapshot.run.errorMessage}")
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
                        assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
                        finalSnapshot = JSONObject(result.content).getJSONObject("after_snapshot")
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
            workflows.recentRunDetails(20).filter { it.run.conversationId == conversationId }.forEach { workflows.setEnabled(it.run.workflowId, false) }
            originalProfile?.let { profiles.select(it) }
            stateStore.saveSelectedAgentProfileId(originalProfile.orEmpty())
            stateStore.saveSelectedConversationId(originalConversation.orEmpty())
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
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)).distinct()
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
