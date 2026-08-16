package com.longdev.xiaoling.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 第 260 阶段验证通知只读能力从真实自然语言进入当前 listener，并在通知移除后通过答案入口拒绝历史快照。
 */
@RunWith(AndroidJUnit4::class)
class Stage260NotificationReadInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageNotificationReadShowsCurrentDetailAndFailsClosedAfterRemoval() = runBlocking {
        assumeTrue("第260阶段真实模型验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val reader = com.longdev.xiaoling.notification.AndroidNotificationReader(context)
        assumeTrue("请先在系统通知访问设置中允许小灵", reader.accessGranted())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assumeTrue(
                "请先允许小灵发送测试通知",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }

        restoreProviderFromRunnerArgsIfRequested()
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assumeTrue("Redmi 当前 Provider 配置不完整", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank())

        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val now = System.currentTimeMillis()
        val marker = "stage260_notification_${now}_${UUID.randomUUID().toString().take(6)}"
        val profileId = "stage260-notification-$now"
        val conversationId = "conversation-stage260-notification-$now"
        val channelId = "stage260-notification-channel"
        val notificationId = marker.hashCode()
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第260阶段通知读取验收",
            avatar = "260",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = """
                For this Stage260 request, use only the notification-overview skill.
                Call exactly notifications.list and then notifications.get.
                Use limit 10 for notifications.list. Find the one notification whose title contains "$marker".
                Pass its notification_id unchanged to notifications.get. Do not call any other tool.
            """.trimIndent(),
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notifications.list", "notifications.get"),
            allowedSkillIds = listOf("notification-overview"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第260阶段临时 Profile", profileStore.select(profileId))
        database.conversationDao().insertConversations(
            listOf(
                ConversationEntity(
                    id = conversationId,
                    title = "新会话",
                    summary = "",
                    summaryUntilMessageId = null,
                    summaryUpdatedAt = null,
                    summaryModel = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        roomState.saveSelectedAgentProfileId(profileId)
        roomState.saveSelectedConversationId(conversationId)
        manager.createNotificationChannel(NotificationChannel(channelId, "第260阶段通知读取", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            notificationId,
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(marker)
                .setContentText("项目评审将在 14:00 开始")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build(),
        )
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            // long: instrumentation 与 NotificationListenerService 可能由不同 ClassLoader 持有静态快照；这里不把测试进程的 connected/list 视为产品状态，改由真实 Run 的工具回执建立稳定身份。
            delay(1_000L)
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario) { it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId && !it.loadingConversationMessages }
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt("/agent 请读取当前通知 $marker 并返回详情")
            }
            clickVisibleNode(text = "发送", timeoutMs = 15_000L)
            val completed = awaitState(scenario, timeoutMs = 240_000L) { state ->
                state.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                    state.chatMessages.flatMap { it.effectiveParts() }.filterIsInstance<MessagePart.Tool>().any { it.toolName == "notifications.get" }
            }
            val toolParts = completed.chatMessages.flatMap { it.effectiveParts() }.filterIsInstance<MessagePart.Tool>()
            assertEquals(listOf("notifications.list", "notifications.get"), toolParts.map(MessagePart.Tool::toolName))
            assertTrue(toolParts.all { it.success })
            assertEquals(MessageToolVerificationStatus.READABLE_ONLY, toolParts.last().verificationStatus)
            val listedNotificationId = Regex("id=(notification-[0-9a-f]{64})")
                .find(toolParts.first().result)
                ?.groupValues
                ?.get(1)
            assertNotNull("notifications.list 未返回稳定 notification_id", listedNotificationId)
            assertEquals(listedNotificationId, toolParts.last().arguments["notification_id"])

            scenario.recreate()
            awaitState(scenario) { state ->
                !state.loadingConversationMessages && state.chatMessages.flatMap { it.effectiveParts() }
                    .filterIsInstance<MessagePart.Tool>().any { it.toolName == "notifications.get" }
            }
            clickVisibleNode(text = "查看通知", timeoutMs = 20_000L, scrollForward = true)
            assertTrue("当前通知详情未显示", awaitVisibleText("通知详情", 20_000L))
            assertTrue("当前通知标题未显示", awaitVisibleText(marker, 10_000L))

            manager.cancel(notificationId)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            clickVisibleNode(text = "对话", timeoutMs = 10_000L)
            clickVisibleNode(text = "查看通知", timeoutMs = 20_000L, scrollForward = true)
            assertTrue("移除后的通知入口未 fail-closed", awaitVisibleText("当前通知已消失或不可读取", 15_000L))
        } finally {
            scenario?.close()
            manager.cancel(notificationId)
            manager.deleteNotificationChannel(channelId)
            profileStore.select(originalProfileId ?: "")
            roomState.saveSelectedConversationId(originalConversationId ?: "")
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profileStore.delete(profileId)
        }
    }

    private suspend fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 20_000L,
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(latest)) return latest
            latest.activeAgentRun?.run?.status?.takeIf { it.isTerminal && it != AgentRunStatus.COMPLETED }?.let {
                throw AssertionError("Stage260 Run 提前终止：${latest.activeAgentRun?.run?.status} ${latest.activeAgentRun?.run?.errorMessage}")
            }
            delay(100L)
        }
        throw AssertionError("等待 Stage260 状态超时：${latest.activeAgentRun?.run?.status}")
    }

    private fun clickVisibleNode(text: String, timeoutMs: Long, scrollForward: Boolean = false) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            root?.findVisibleText(text)?.let { node ->
                if (node.clickSelfOrAncestor()) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    return
                }
            }
            if (scrollForward) root?.scrollForward()
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("没有找到可点击节点：$text")
    }

    private suspend fun waitUntil(message: String, timeoutMs: Long = 15_000L, predicate: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            delay(100L)
        }
        throw AssertionError(message)
    }

    private suspend fun restoreProviderFromRunnerArgsIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(ARG_RESTORE_PROVIDER) != "true") return
        val baseUrl = requireNotNull(arguments.getString(ARG_FALLBACK_BASE_URL)?.trim()?.takeIf { it.isNotBlank() })
        val apiKey = requireNotNull(arguments.getString(ARG_FALLBACK_API_KEY)?.trim()?.takeIf { it.isNotBlank() })
        val model = requireNotNull(arguments.getString(ARG_FALLBACK_MODEL)?.trim()?.takeIf { it.isNotBlank() })
        val repository = ProviderRepository(context)
        val current = repository.load()
        val existing = current.profiles.firstOrNull() ?: com.longdev.xiaoling.model.ProviderProfile.blank()
        val restored = existing.copy(
            name = existing.name.ifBlank { "兜底 Provider" },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        // long: 兜底凭据只通过 instrumentation 参数进入设备 Keystore；测试源码、日志与文档不保存密钥原文。
        repository.save(listOf(restored), restored.id)
        val loaded = repository.load().profiles.single()
        assertEquals(baseUrl, loaded.baseUrl)
        assertEquals(model, loaded.model)
        assertTrue(loaded.apiKey.isNotBlank())
    }

    private fun AccessibilityNodeInfo.findVisibleText(expected: String): AccessibilityNodeInfo? {
        if (isVisibleToUser && (text?.toString() == expected || contentDescription?.toString() == expected)) return this
        repeat(childCount) { index -> getChild(index)?.findVisibleText(expected)?.let { return it } }
        return null
    }

    private fun AccessibilityNodeInfo.clickSelfOrAncestor(): Boolean {
        var current: AccessibilityNodeInfo? = this
        repeat(5) {
            val node = current ?: return false
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = node.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.scrollForward(): Boolean {
        if (isScrollable && performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        repeat(childCount) { index -> if (getChild(index)?.scrollForward() == true) return true }
        return false
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
        if (isVisibleToUser && (text?.toString()?.contains(expected) == true || contentDescription?.toString()?.contains(expected) == true)) return true
        repeat(childCount) { index -> if (getChild(index)?.containsText(expected) == true) return true }
        return false
    }

    private fun awaitVisibleText(expected: String, timeoutMs: Long): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            if (root?.containsText(expected) == true) return true
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private companion object {
        const val ARG_RESTORE_PROVIDER = "stage260RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage260FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage260FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage260FallbackModel"
    }
}
