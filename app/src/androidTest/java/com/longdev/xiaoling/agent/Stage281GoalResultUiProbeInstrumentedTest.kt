package com.longdev.xiaoling.agent

import android.app.UiAutomation
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
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.StoredConversationMessage
import com.longdev.xiaoling.storage.StoredMessageMeta
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 只验证已持久化的目标级结果能否在真实 Activity 的 Compose 语义树中被 UiAutomation 读取，
 * 不调用 Provider、无障碍服务或任何设备动作，避免把展示问题和真实 Run 链路混在同一次验收里。
 */
@RunWith(AndroidJUnit4::class)
class Stage281GoalResultUiProbeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test
    fun persistedGoalResultIsVisibleAfterActivityLaunch() = runBlocking {
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        val database = XiaoLingDatabase.getInstance(context)
        val stateStore = RoomStateStore(context)
        val originalConversationId = stateStore.selectedConversationId()
        val now = System.currentTimeMillis()
        val conversationId = "conversation-stage281-ui-probe-$now"
        val messageId = "message-stage281-ui-probe-$now"
        val runId = "run-stage281-ui-probe-$now"
        val contextSnapshot = VerifiedAgentContext(
            runId = runId,
            toolName = "device.open_app",
            arguments = mapOf("package_name" to "com.android.calculator2"),
            success = true,
            verificationStatus = AgentVerificationStatus.VERIFIED,
            rawResult = "{\"package\":\"com.android.calculator2\",\"captured_at\":$now,\"node_count\":1,\"redacted_node_count\":0,\"truncated\":false}",
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "device.open_app",
                    arguments = mapOf("package_name" to "com.android.calculator2"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = "{\"package\":\"com.android.calculator2\",\"captured_at\":$now,\"node_count\":1,\"redacted_node_count\":0,\"truncated\":false}",
                ),
            ),
        )
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            database.conversationDao().insertConversations(
                listOf(
                    ConversationEntity(
                        id = conversationId,
                        title = "第281阶段 UI 探针",
                        summary = "",
                        summaryUntilMessageId = null,
                        summaryUpdatedAt = null,
                        summaryModel = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
            MessageRepository(database).insert(
                listOf(
                    conversationId to StoredConversationMessage(
                        id = messageId,
                        role = "assistant",
                        text = "已完成设备动作。",
                        createdAt = now,
                        origin = MessageOrigin.AGENT_RESULT.name,
                        verifiedAgentContext = VerifiedAgentContextCodec.encode(contextSnapshot),
                        meta = StoredMessageMeta(
                            providerId = null,
                            providerName = null,
                            model = null,
                            apiMode = null,
                            streaming = false,
                            requestUrl = null,
                            firstTokenLatencyMs = null,
                            latencyMs = null,
                            promptTokens = null,
                            completionTokens = null,
                            totalTokens = null,
                            finishReason = "stop",
                            errorKind = null,
                            errorMessage = null,
                        ),
                    ),
                ),
            )
            stateStore.saveSelectedConversationId(conversationId)

            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, conversationId, runId)
            // long: 真实设备动作结束后会重建宿主；探针也覆盖这一生命周期边界，确保目标卡片不是只在首次组合时存在。
            scenario.recreate()
            awaitState(scenario, conversationId, runId)
            automation.clearCache()
            val goalCard = awaitVisible("目标级结果")
            assertNotNull("目标级结果必须能被 UiAutomation 读取", goalCard)
            val refreshButton = awaitVisibleAfterScrolling("重新读取当前事实")
            assertNotNull("当前事实刷新入口必须能被 UiAutomation 读取", refreshButton)
            println(
                "STAGE281_UI_PROBE_PASSED conversationId=$conversationId runId=$runId " +
                    "goalCardContent=${goalCard?.contentDescription} refreshContent=${refreshButton?.contentDescription}",
            )
        } finally {
            scenario?.close()
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            originalConversationId?.let(stateStore::saveSelectedConversationId)
        }
    }

    private suspend fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        conversationId: String,
        runId: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var loaded = false
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[XiaoLingViewModel::class.java].uiState
                loaded = state.selectedConversationId == conversationId &&
                    !state.loadingConversationMessages &&
                    state.chatMessages.any { message -> message.verifiedAgentContext?.runId == runId }
            }
            if (loaded) return
            SystemClock.sleep(100L)
        }
        error("第281阶段 UI 探针未加载临时目标消息")
    }

    private fun awaitVisible(text: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            automation.clearCache()
            val roots = automation.windows.mapNotNull { window -> window.root } +
                listOfNotNull(automation.rootInActiveWindow)
            roots.firstNotNullOfOrNull { root ->
                root.find { node ->
                    node.isVisibleToUser &&
                        (node.text?.toString() == text || node.contentDescription?.toString() == text)
                }
            }?.let { return it }
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun awaitVisibleAfterScrolling(text: String): AccessibilityNodeInfo? {
        val displayMetrics = context.resources.displayMetrics
        val x = displayMetrics.widthPixels / 2
        val topY = (displayMetrics.heightPixels * 0.28f).toInt()
        val bottomY = (displayMetrics.heightPixels * 0.78f).toInt()
        repeat(8) {
            awaitVisible(text, 500L)?.let { return it }
            // long: Redmi 可能报告语义滚动已接受但实际没有移动 LazyColumn；两种滚动都执行，确保屏外按钮确实有机会进入语义树。
            scrollAccessibility(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            automation.executeShellCommand("input swipe $x $topY $x $bottomY 280").close()
            SystemClock.sleep(350L)
        }
        repeat(8) {
            awaitVisible(text, 500L)?.let { return it }
            scrollAccessibility(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            automation.executeShellCommand("input swipe $x $bottomY $x $topY 280").close()
            SystemClock.sleep(350L)
        }
        return awaitVisible(text)
    }

    private fun awaitVisible(text: String, timeout: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            automation.clearCache()
            val roots = automation.windows.mapNotNull { window -> window.root } +
                listOfNotNull(automation.rootInActiveWindow)
            roots.firstNotNullOfOrNull { root ->
                root.find { node ->
                    node.isVisibleToUser &&
                        (node.text?.toString() == text || node.contentDescription?.toString() == text)
                }
            }?.let { return it }
            SystemClock.sleep(100L)
        }
        return null
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

    private fun AccessibilityNodeInfo.find(
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        repeat(childCount) { index ->
            getChild(index)?.find(predicate)?.let { node -> return node }
        }
        return null
    }
}
