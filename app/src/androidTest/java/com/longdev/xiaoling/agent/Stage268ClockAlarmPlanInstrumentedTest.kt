package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 先只验证一次性闹钟目标能否被真实 Provider 收敛为有限时钟动作计划，不在计划门禁阶段执行任何设备副作用。 */
@RunWith(AndroidJUnit4::class)
class Stage268ClockAlarmPlanInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun clockAlarmGoalProducesBoundedPlanWithoutExecutingDeviceAction() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅显式 stage268PlanOnly=true 运行计划门禁", args.getString(ARG_PLAN_ONLY) == "true")
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)

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
        val profileId = "stage268-clock-alarm-plan-$now"
        val conversationId = "conversation-$profileId"
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            profiles.upsert(
                AgentProfileRecord(
                    id = profileId,
                    name = "第268阶段时钟闹钟计划",
                    avatar = "268",
                    providerId = provider.id,
                    model = provider.model,
                    apiMode = ApiMode.RESPONSES,
                    systemPrompt = "只规划系统时钟中设置一个未来的一次性闹钟。不能设置周期闹钟、不能修改或删除其他闹钟、不能执行后台调度；每一步只做一个可验证的前台动作。",
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
                "STAGE268_PLAN target=${plan.targetAppPackage} expected=${plan.goalVerificationSpec?.expectedFinalPackageName} " +
                    "tools=${plan.goalVerificationSpec?.requiredToolNames} steps=${plan.steps}",
            )
            assertEquals(GOAL, plan.sourceGoal)
            assertEquals(EXPECTED_PACKAGE, plan.targetAppPackage)
            assertEquals(EXPECTED_PACKAGE, plan.goalVerificationSpec?.expectedFinalPackageName)
            assertTrue("计划不能在未确认前创建 Workflow", workflows.recentRunDetails(20).none { it.run.conversationId == conversationId })
            awaitVisible("确认并执行")
            clickVisible("返回修改")
            assertTrue(workflows.recentRunDetails(20).none { it.run.conversationId == conversationId })
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
        }
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

    private fun currentRoots(): List<AccessibilityNodeInfo> =
        (instrumentation.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)).distinct()

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
        const val ARG_PLAN_ONLY = "stage268PlanOnly"
        const val EXPECTED_PACKAGE = "com.google.android.deskclock"
        const val GOAL = "打开 Google 时钟（目标包名必须是 com.google.android.deskclock；如果该包不可启动则使用已登记同族实现），设置一个 10 分钟后响铃的一次性闹钟，完成后停留在当前时钟闹钟页面并读取已启用闹钟的时间与一次性状态。不要设置周期闹钟，不要修改或删除其他闹钟。"
        val DEVICE_TOOLS = listOf(
            "device.snapshot",
            "device.open_app",
            "device.back",
            "device.home",
            "device.tap_ref",
            "device.type_text",
            "device.swipe",
        )
    }
}
