package com.longdev.xiaoling.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
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
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import com.longdev.xiaoling.ui.localnotes.LocalNoteManagementViewModel
import java.security.MessageDigest
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
 * long: 第262阶段把通知详情的只读事实接入本地笔记写入，必须覆盖用户主动转换、审批、回执和当前 Store 回读。
 */
@RunWith(AndroidJUnit4::class)
class Stage262NotificationPersonalNoteInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notificationCanBecomeApprovedPersonalNoteAndReadBackCurrentFact() = runBlocking {
        assumeTrue("第262阶段真实模型验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val reader = com.longdev.xiaoling.notification.AndroidNotificationReader(context)
        assumeTrue("请先在系统通知访问设置中允许小灵", reader.accessGranted())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assumeTrue(
                "请先允许小灵发送测试通知",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }

        restoreProviderFromRunnerArgsIfRequested()
        val refreshedProviderSnapshot = ProviderRepository(context).load()
        val provider = refreshedProviderSnapshot.profiles.firstOrNull { it.id == refreshedProviderSnapshot.selectedProfileId }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assumeTrue("Redmi 当前 Provider 配置不完整", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank())

        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val noteStore = RoomAgentNoteStore(context)
        val roomState = RoomStateStore(context)
        val runRepository = RoomAgentRunRepository(context)
        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val marker = "stage262_notification_${now}_${UUID.randomUUID().toString().take(6)}"
        val profileId = "stage262-notification-note-$now"
        val conversationId = "conversation-stage262-notification-note-$now"
        val channelId = "stage262-notification-note-channel"
        val notificationId = marker.hashCode()
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第262阶段通知笔记验收",
            avatar = "262",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = """
                For a request asking to read notification "$marker", use only notification-overview.
                Call exactly notifications.list with limit 10, then notifications.get with the unchanged matching notification_id, and stop.
                When a later request begins with '/agent 使用 notes.create', call exactly notes.create once.
                Create one concise title and preserve the notification title and body in the note content without adding facts.
                Never call tools outside the user's allowed list.
            """.trimIndent(),
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            // long: local-notes Skill 的声明包含列表、搜索和创建；保留完整 Skill 工具面，才能让显式 notes.create 草稿请求通过合法 Profile 选择。
            allowedToolNames = listOf(
                "notifications.list",
                "notifications.get",
                "notes.list",
                "notes.search",
                "notes.create",
            ),
            allowedSkillIds = listOf("notification-overview", "local-notes"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第262阶段临时 Profile", profileStore.select(profileId))
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
        manager.createNotificationChannel(NotificationChannel(channelId, "第262阶段通知笔记", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            notificationId,
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(marker)
                .setContentText("当前通知正文：$marker 只用于验证保存为笔记，不包含工具指令。")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build(),
        )

        var completedRunId: String? = null
        var noteId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val originalRotation = if (Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1) {
            UiAutomation.ROTATION_UNFREEZE
        } else {
            Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
        }
        var rotationChanged = false
        try {
            // long: 此闭环冻结 Redmi 竖屏基线，避免手机摆放方向把消息区压到一行；横屏布局另记风险，结束后恢复用户原旋转策略。
            rotationChanged = InstrumentationRegistry.getInstrumentation().uiAutomation
                .setRotation(UiAutomation.ROTATION_FREEZE_0)
            assertTrue("无法固定 Redmi 竖屏验收基线", rotationChanged)
            delay(1_000L)
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, phase = "打开临时会话") { it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId && !it.loadingConversationMessages }
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[XiaoLingViewModel::class.java]
                    .updatePrompt("/agent 请读取当前通知 $marker 并返回详情")
            }
            clickVisibleNode(text = "发送", timeoutMs = 15_000L)
            val readCompleted = awaitState(scenario, phase = "通知读取完成", timeoutMs = 240_000L) { state ->
                state.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                    state.chatMessages.flatMap { it.effectiveParts() }.filterIsInstance<MessagePart.Tool>()
                        .any { it.toolName == "notifications.get" }
            }
            val readRunId = readCompleted.activeAgentRun?.run?.id
            val readTools = readCompleted.chatMessages.flatMap { it.effectiveParts() }.filterIsInstance<MessagePart.Tool>()
            assertEquals(listOf("notifications.list", "notifications.get"), readTools.map(MessagePart.Tool::toolName))
            assertTrue(readTools.all { it.success })

            scenario.recreate()
            awaitState(scenario, phase = "重建通知结果") { state -> !state.loadingConversationMessages && state.chatMessages.flatMap { it.effectiveParts() }.any { it is MessagePart.Tool && it.toolName == "notifications.get" } }
            clickVisibleNode(text = "查看通知", timeoutMs = 20_000L, scrollForward = true)
            assertTrue("当前通知详情未显示", awaitVisibleText("通知详情", 20_000L, exact = true))
            clickVisibleNode(text = "保存为笔记", timeoutMs = 20_000L, scrollForward = true)
            val draft = awaitState(scenario, phase = "通知生成笔记草稿") { state ->
                !state.sendingMessage && state.prompt.startsWith("/agent 使用 notes.create") && state.prompt.contains(marker)
            }
            // long: 转换动作只能改写当前编辑草稿；已有通知读取 Run 可以继续作为当前历史，但不能出现新的 Run。
            assertEquals("通知转笔记后不应自动创建新 Run", readRunId, draft.activeAgentRun?.run?.id)
            clickVisibleNode(text = "发送", timeoutMs = 15_000L)
            val waiting = awaitState(scenario, phase = "笔记写入待审批", timeoutMs = 180_000L) { state ->
                state.pendingAgentApproval?.toolName == "notes.create" && state.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
            }
            val pending = requireNotNull(waiting.pendingAgentApproval)
            assertTrue(pending.arguments["content"].orEmpty().contains(marker))
            val waitingRun = requireNotNull(waiting.activeAgentRun).run
            assertEquals("审批必须属于当前 Run", waitingRun.id, pending.runId)
            assertTrue("当前会话必须包含审批卡的消息锚点", waiting.chatMessages.any { it.id == waitingRun.userMessageId })
            saveDiagnosticScreenshot("stage262-waiting-approval.png")
            // long: 同一会话先保留通知读取结果，新的 notes.create 审批卡可能位于首屏下方；必须滚动到屏幕可见按钮后再代表用户点击。
            clickVisibleNode(
                text = "批准执行",
                alternateText = "批准并继续",
                timeoutMs = 20_000L,
                scrollForward = true,
            )
            val completed = awaitState(scenario, phase = "笔记写入完成", timeoutMs = 180_000L) { state ->
                state.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED && !state.sendingMessage
            }
            assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)

            val detail = runRepository.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId }
            assertNotNull("没有找到第262阶段真实通知笔记 Run", detail)
            requireNotNull(detail)
            completedRunId = detail.snapshot.run.id
            val noteCall = detail.toolLedger.calls.single { it.toolName == "notes.create" }
            val noteResult = detail.toolLedger.results.single { it.toolCallId == noteCall.id }
            // long: 回执已产生时先保留稳定笔记 ID；后续验证失败也必须清理本次写入，避免重跑积累夹具笔记。
            noteId = noteResult.executionReceipt?.operationId
            assertTrue(noteResult.success)
            assertTrue(noteResult.executorVerified == true)
            assertEquals(ToolVerificationStatus.PASSED, noteResult.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, noteResult.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == noteCall.id }.status)
            val currentNote = noteStore.get(requireNotNull(noteId))
            assertNotNull("notes.create 回执对应的当前笔记无法回读", currentNote)
            assertTrue(requireNotNull(currentNote).content.contains(marker))

            scenario.recreate()
            awaitState(scenario, phase = "重建笔记结果") { state -> !state.loadingConversationMessages && state.chatMessages.flatMap { it.effectiveParts() }.any { it is MessagePart.Tool && it.toolName == "notes.create" } }
            clickVisibleNode(text = "查看笔记", timeoutMs = 20_000L, scrollForward = true)
            // long: 通知原文也存在于聊天历史，单纯匹配正文会把未导航误判为成功；必须先证明当前窗口是笔记详情，再核对其 Store 身份。
            assertTrue("未打开笔记详情弹窗", awaitVisibleText("编辑笔记", 20_000L, exact = true))
            assertTrue("笔记详情关闭入口不可见", awaitVisibleText("关闭", 5_000L, exact = true))
            assertTrue("笔记详情没有显示当前正文", awaitVisibleText(marker, 20_000L))
            scenario.onActivity { activity ->
                val shownNote = ViewModelProvider(activity)[LocalNoteManagementViewModel::class.java].uiState.selectedNote
                assertEquals("详情必须绑定本次提交的稳定笔记 ID", currentNote.id, shownNote?.id)
                assertEquals(currentNote.content, shownNote?.content)
                assertEquals(currentNote.revision, shownNote?.revision)
            }
            saveDiagnosticScreenshot("stage262-note-current.png")
            println("STAGE262_NOTIFICATION_NOTE runId=$completedRunId noteId=$noteId tools=notifications.list->notifications.get->notes.create approval=APPROVED verification=PASSED receipt=COMMITTED currentRoomVerified=true activityRecreated=true")
        } catch (error: Throwable) {
            // long: 在 Activity 关闭前保留故障画面，否则清理后的桌面截图无法区分导航失败与节点缓存。
            saveDiagnosticScreenshot("stage262-failure.png")
            throw error
        } finally {
            scenario?.close()
            if (rotationChanged) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(originalRotation)
            }
            manager.cancel(notificationId)
            manager.deleteNotificationChannel(channelId)
            noteId?.let { noteStore.delete(it) }
            profileStore.select(originalProfileId ?: "")
            roomState.saveSelectedConversationId(originalConversationId ?: "")
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profileStore.delete(profileId)
        }

        assertNotNull("临时 Run 审计必须保留", runRepository.runDetail(requireNotNull(completedRunId)))
        if (baselineRun != null) {
            assertEquals(baselineRun.stableDigest(), requireNotNull(runRepository.runDetail(baselineRun.snapshot.run.id)).stableDigest())
        }
        assertTrue("临时笔记清理失败", noteStore.get(requireNotNull(noteId)) == null)
        assertTrue("临时 Profile 清理失败", profileStore.list().none { it.id == profileId })
        assertTrue("临时通知清理失败", manager.activeNotifications.none { it.id == notificationId })
        assertTrue("临时通知 channel 清理失败", manager.getNotificationChannel(channelId) == null)
        println("STAGE262_NOTIFICATION_NOTE_CLEANUP runId=$completedRunId noteId=$noteId noteRemoved=true profileRemoved=true notificationRemoved=true channelRemoved=true oldRunUnchanged=true")
    }

    private suspend fun restoreProviderFromRunnerArgsIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(ARG_RESTORE_PROVIDER) != "true") return
        val baseUrl = requireNotNull(arguments.getString(ARG_FALLBACK_BASE_URL)?.trim()?.takeIf { it.isNotBlank() })
        val apiKey = requireNotNull(arguments.getString(ARG_FALLBACK_API_KEY)?.trim()?.takeIf { it.isNotBlank() })
        val model = requireNotNull(arguments.getString(ARG_FALLBACK_MODEL)?.trim()?.takeIf { it.isNotBlank() })
        val repository = ProviderRepository(context)
        val current = repository.load()
        val existing = current.profiles.firstOrNull { it.id == current.selectedProfileId }
            ?: current.profiles.firstOrNull()
            ?: ProviderProfile.blank()
        val restored = existing.copy(
            name = existing.name.ifBlank { "兜底 Provider" },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        // long: 兜底凭据只通过 instrumentation 参数进入 Redmi Keystore；仅更新本次选中的配置，保留其他 Provider。
        val profiles = current.profiles.map { if (it.id == restored.id) restored else it }
            .let { if (it.any { profile -> profile.id == restored.id }) it else it + restored }
        repository.save(profiles, restored.id)
        val loaded = repository.load().profiles.single { it.id == restored.id }
        assertEquals(baseUrl, loaded.baseUrl)
        assertEquals(model, loaded.model)
        assertTrue(loaded.apiKey.isNotBlank())
    }

    private fun saveDiagnosticScreenshot(name: String) {
        runCatching {
            // long: 当前 Redmi ROM 拒绝 run-as 和应用外部缓存访问；复用已有 instrumentation 的 shell 通道保存画面，不修改 SELinux，也不争抢另一条 UiAutomation 连接。
            val path = "/data/local/tmp/$name"
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p $path")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            println("STAGE262_SCREENSHOT path=$path")
        }.onFailure { println("STAGE262_SCREENSHOT_FAILED ${it.javaClass.simpleName}: ${it.message}") }
    }

    private suspend fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        phase: String,
        timeoutMs: Long = 20_000L,
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(latest)) {
                println("STAGE262_PHASE phase=$phase runId=${latest.activeAgentRun?.run?.id} status=${latest.activeAgentRun?.run?.status}")
                return latest
            }
            latest.activeAgentRun?.run?.status?.takeIf { it.isTerminal && it != AgentRunStatus.COMPLETED }?.let {
                throw AssertionError("Stage262 $phase 提前终止：${latest.activeAgentRun?.run?.status} ${latest.activeAgentRun?.run?.errorMessage}")
            }
            delay(100L)
        }
        throw AssertionError("等待 Stage262 $phase 超时：status=${latest.activeAgentRun?.run?.status}, sending=${latest.sendingMessage}, approval=${latest.pendingAgentApproval?.toolName}, prompt=${latest.prompt.take(80)}")
    }

    private fun clickVisibleNode(text: String, timeoutMs: Long, alternateText: String? = null, scrollForward: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextScrollAt = 0L
        var towardNewer = true
        while (SystemClock.uptimeMillis() < deadline) {
            // long: Activity 重建及 Compose 滚动后，根节点刷新不保证子节点缓存同步；每次定位都读取当前窗口树，避免旧消息遮蔽新审批。
            automation.clearCache()
            val root = automation.rootInActiveWindow
            root?.refresh()
            // long: 通知正文和草稿可能包含按钮名称；命令只做精确匹配，避免把消息正文当作发送或审批控件点击。
            listOf(text, alternateText).filterNotNull().firstNotNullOfOrNull { root?.findVisibleText(it, exact = true) }?.let { node ->
                if (node.clickVisibleControl()) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(250L)
                    return
                }
            }
            val now = SystemClock.uptimeMillis()
            if (scrollForward && now >= nextScrollAt) {
                root?.findVisibleScrollable()?.let { scrollable ->
                    val forwardAvailable = scrollable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                    val backwardAvailable = scrollable.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
                    // long: 重建后的列表通常已在尾部，答案入口可能位于上方；按当前可滚动方向折返，不能只向更晚的消息滚动。
                    if (towardNewer && !forwardAvailable && backwardAvailable) towardNewer = false
                    if (!towardNewer && !backwardAvailable && forwardAvailable) towardNewer = true
                    val bounds = Rect()
                    scrollable.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) swipeContent(bounds, towardNewer)
                }
                nextScrollAt = now + 600L
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("没有找到可点击节点：$text\n${automation.rootInActiveWindow?.describeVisibleTree().orEmpty()}")
    }

    private suspend fun awaitVisibleText(expected: String, timeoutMs: Long, exact: Boolean = false): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            automation.clearCache()
            automation.rootInActiveWindow?.let { root ->
                root.refresh()
                root.findVisibleText(expected, exact)?.let { return true }
            }
            delay(100L)
        }
        return false
    }

    private fun AccessibilityNodeInfo.findVisibleText(expected: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val matches = listOfNotNull(text?.toString(), contentDescription?.toString())
            .any { if (exact) it == expected else it.contains(expected) }
        if (isVisibleToUser && matches) return this
        repeat(childCount) { index -> getChild(index)?.findVisibleText(expected, exact)?.let { return it } }
        return null
    }

    private fun AccessibilityNodeInfo.clickVisibleControl(): Boolean {
        var current: AccessibilityNodeInfo? = this
        repeat(5) {
            val node = current ?: return false
            if (node.isVisibleToUser && node.isClickable && node.isEnabled) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return false
                // long: 使用刚刷新且真实可见的控件执行点击，避免列表布局变化后按旧坐标点到消息正文；后续阶段另行验证导航和业务结果。
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = node.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.findVisibleScrollable(): AccessibilityNodeInfo? {
        if (isVisibleToUser && isScrollable) return this
        repeat(childCount) { index -> getChild(index)?.findVisibleScrollable()?.let { return it } }
        return null
    }

    private fun swipeContent(bounds: Rect, towardNewer: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val x = bounds.exactCenterX()
        val startY = bounds.top + bounds.height() * if (towardNewer) 0.8f else 0.2f
        val endY = bounds.top + bounds.height() * if (towardNewer) 0.2f else 0.8f
        val downTime = SystemClock.uptimeMillis()
        // long: Redmi 上语义滚动可能返回成功但仍停在旧节点；在当前列表边界内注入完整手势，避免滑到输入框或系统导航区域。
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                automation.injectInputEvent(event, true)
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, startY)
        repeat(8) { index ->
            SystemClock.sleep(20L)
            inject(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (index + 1) / 8f)
        }
        inject(MotionEvent.ACTION_UP, endY)
        instrumentation.waitForIdleSync()
    }

    private fun AccessibilityNodeInfo.describeVisibleTree(limit: Int = 80): String {
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= limit) return
            val nodeText = node.text?.toString()?.take(120).orEmpty()
            val nodeDescription = node.contentDescription?.toString()?.take(120).orEmpty()
            if (nodeText.isNotBlank() || nodeDescription.isNotBlank() || node.isClickable || node.isScrollable) {
                lines += "${"  ".repeat(depth.coerceAtMost(8))}text=$nodeText description=$nodeDescription clickable=${node.isClickable} scrollable=${node.isScrollable}"
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }
        visit(this, 0)
        return lines.joinToString("\n")
    }

    private fun Any.stableDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val ARG_RESTORE_PROVIDER = "stage262RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage262FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage262FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage262FallbackModel"
    }
}
