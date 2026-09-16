package com.longdev.xiaoling.agent

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 改期闭环必须穿过真实前台审批和系统队列，内存 Room 的成功不能代替用户可见结果。 */
@RunWith(AndroidJUnit4::class)
class Stage264TaskRescheduleInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageRescheduleRequiresVisibleApprovalAndReadsCurrentTaskAfterRecreation() = runBlocking {
        assumeTrue("第264阶段真实模型验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val provider = selectedProviderOrFallback()
        val database = XiaoLingDatabase.getInstance(context)
        val repository = RoomWorkflowRepository(context)
        val scheduler = WorkManagerScheduledTaskScheduler(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val runRepository = RoomAgentRunRepository(context)
        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val marker = "stage264-$now"
        val taskName = "喝水提醒-$marker"
        val stepGoal = "读取当前时间并提醒喝水 $marker"
        val profileId = "profile-$marker"
        val conversationId = "conversation-$marker"
        val zone = ZoneId.systemDefault()
        val requestedTime = Instant.ofEpochMilli(now).plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        val requestedText = requestedTime.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第264阶段一次性提醒改期验收",
            avatar = "264",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只处理用户明确提出的一次性提醒改期，使用 task-reschedule Skill；工具结果是当前事实，不能猜测原时间或指纹。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.current_time", "tasks.list", "tasks.inspect", "tasks.reschedule"),
            allowedSkillIds = listOf("task-reschedule"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        var workflowId: String? = null
        var completedRunId: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalRotation = if (Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1) {
            UiAutomation.ROTATION_UNFREEZE
        } else {
            Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
        }
        var rotationChanged = false
        try {
            // long: 夹具安排在半小时后，新时间在两小时后，避免网络等待期间到期；所有系统工作都在 finally 按本次 Workflow 收口。
            val fixture = repository.createWorkflowAndOneTimeScheduledTask(taskName, listOf(WorkflowStepDefinitionInput(stepGoal)), 30)
            workflowId = fixture.first.id
            val initialWorkId = scheduler.enqueue(fixture.second)
            val initialTask = repository.attachWorkRequest(fixture.second.id, initialWorkId)
            assertEquals(WorkInfo.State.ENQUEUED, workState(initialWorkId))
            val history = repository.createManualRun(fixture.first.id, "history-$marker")
            repository.completeRun(history.run.id, WorkflowRunStatus.COMPLETED, result = "历史提醒已完成")
            val historyBefore = repository.runDetail(history.run.id)

            profileStore.upsert(profile)
            assertTrue("无法选择本次临时 Profile", profileStore.select(profileId))
            database.conversationDao().insertConversations(
                listOf(ConversationEntity(conversationId, "新会话", "", null, null, null, now, now)),
            )
            roomState.saveSelectedAgentProfileId(profileId)
            roomState.saveSelectedConversationId(conversationId)
            rotationChanged = automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            assertTrue("无法固定 Redmi 竖屏验收基线", rotationChanged)
            delay(1_000L)
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            awaitState(scenario, "临时会话") {
                it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId && !it.loadingConversationMessages
            }
            scenario.onActivity {
                ViewModelProvider(it)[XiaoLingViewModel::class.java]
                    .updatePrompt("/agent 请把一次性提醒“$taskName”改到 $requestedText（${zone.id}），旧运行记录保持不变。")
            }
            clickVisibleNode("发送")
            val waiting = awaitState(scenario, "改期审批", 240_000L) {
                it.pendingAgentApproval?.toolName == TASK_RESCHEDULE_TOOL_NAME && it.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
            }
            val approval = requireNotNull(waiting.pendingAgentApproval)
            val request = requireNotNull(AgentTaskRescheduleRequest.parse(approval.arguments))
            assertEquals(taskName, request.name)
            assertEquals(initialTask.plannedAt, request.expectedPlannedAtMillis)
            assertEquals(requestedTime.toEpochMilli(), request.plannedAtMillis)
            assertEquals(zone.id, request.timeZone)
            assertEquals(repository.oneTimeScheduleForReschedule(taskName)?.token, request.expectedScheduleToken)
            assertEquals("批准前不得修改原实例", initialTask, repository.getScheduledTask(initialTask.id))
            assertEquals(1, repository.listScheduledTasks().count { it.workflowId == workflowId })
            assertEquals(WorkInfo.State.ENQUEUED, workState(initialWorkId))

            // long: 先定位可见审批控件，再逐项核对同一屏幕上的完整时间；不能直接调用 ViewModel 的批准方法跳过用户界面。
            awaitVisibleNode("批准执行", alternateText = "批准并继续", scroll = true)
            assertVisible("任务：$taskName")
            assertVisible("原时间：${request.expectedPlannedAt}")
            assertVisible("新时间：${request.plannedAt}")
            assertVisible("时区：${request.timeZone}")
            saveScreenshot("stage264-approval.png")
            println("STAGE264_APPROVAL model=${provider.model} oldAt=${request.expectedPlannedAt} newAt=${request.plannedAt} zone=${request.timeZone} originalUnchanged=true")
            clickVisibleNode("批准执行", alternateText = "批准并继续")
            val completed = awaitState(scenario, "改期完成", 180_000L) {
                it.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED && !it.sendingMessage
            }
            completedRunId = requireNotNull(completed.activeAgentRun).run.id
            val detail = requireNotNull(runRepository.runDetail(completedRunId))
            assertEquals(listOf("app.current_time", "tasks.list", "tasks.inspect", "tasks.reschedule"), detail.toolLedger.calls.map { it.toolName })
            assertTrue(detail.toolLedger.results.all { it.success })
            val call = detail.toolLedger.calls.single { it.toolName == TASK_RESCHEDULE_TOOL_NAME }
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertTrue(result.executorVerified == true)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(request.resultText(), result.content)
            val newTask = repository.listScheduledTasks().single { it.workflowId == workflowId && it.status == ScheduledTaskStatus.SCHEDULED }
            val oldTask = requireNotNull(repository.getScheduledTask(initialTask.id))
            assertEquals(newTask.id, result.executionReceipt?.operationId)
            assertEquals(ScheduledTaskStatus.CANCELLED, oldTask.status)
            assertEquals(initialTask.plannedAt, oldTask.plannedAt)
            assertEquals(requestedTime.toEpochMilli(), newTask.plannedAt)
            assertFalse(initialTask.id == newTask.id)
            assertEquals(WorkInfo.State.CANCELLED, workState(initialWorkId))
            assertEquals(WorkInfo.State.ENQUEUED, workState(requireNotNull(newTask.workRequestId)))
            assertEquals("改期不得改写历史 Workflow Run", historyBefore, repository.runDetail(history.run.id))
            assertTrue("完成答案未展示当前改期事实", completed.chatMessages.any { it.role == "assistant" && it.text == result.content })

            scenario.recreate()
            awaitState(scenario, "重建改期答案") {
                !it.loadingConversationMessages && it.chatMessages.flatMap { message -> message.effectiveParts() }
                    .any { part -> part is MessagePart.Tool && part.toolName == TASK_RESCHEDULE_TOOL_NAME }
            }
            // long: 新会话包含 inspect 和 reschedule 两张工具卡；只点击改期结果卡里的入口，避免前一张查看任务掩盖改期导航缺失。
            clickVisibleNode("查看任务", scroll = true, requiredToolName = TASK_RESCHEDULE_TOOL_NAME)
            assertVisible("工作流", exact = true)
            assertVisible(taskName, exact = true)
            assertVisible("步骤定义", exact = true)
            assertVisible("1. $stepGoal", exact = true)
            assertVisible("调度实例", exact = true)
            val currentTimeLabel = requestedTime.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            assertVisible("一次 · $currentTimeLabel · 等待系统调度", exact = true)
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[XiaoLingViewModel::class.java].uiState
                assertEquals(fixture.first.id, state.workflows.single { it.name == taskName }.id)
                assertEquals(newTask, state.scheduledTasks.single { it.id == newTask.id })
                assertEquals(oldTask, state.scheduledTasks.single { it.id == oldTask.id })
            }
            saveScreenshot("stage264-current-task.png")
            println("STAGE264_RESCHEDULE runId=$completedRunId workflowId=$workflowId oldTaskId=${oldTask.id} newTaskId=${newTask.id} tools=app.current_time->tasks.list->tasks.inspect->tasks.reschedule approval=APPROVED verification=PASSED receipt=COMMITTED oldWork=CANCELLED newWork=ENQUEUED oldRunUnchanged=true currentRoomVerified=true activityRecreated=true navigationVerified=true")
        } catch (error: Throwable) {
            saveScreenshot("stage264-failure.png")
            throw error
        } finally {
            scenario?.close()
            if (rotationChanged) automation.setRotation(originalRotation)
            workflowId?.let { id ->
                repository.listScheduledTasks().filter { it.workflowId == id }.forEach { task ->
                    scheduler.cancel(task.id)
                    repository.cancelScheduledTask(task.id)
                    task.workRequestId?.let { assertEquals(WorkInfo.State.CANCELLED, workState(it)) }
                }
                repository.setEnabled(id, false)
            }
            profileStore.select(originalProfileId ?: "")
            roomState.saveSelectedConversationId(originalConversationId ?: "")
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            profileStore.delete(profileId)
            println("STAGE264_CLEANUP workflowId=$workflowId pendingWorkCancelled=true workflowDisabled=true temporaryConversationRemoved=true temporaryProfileRemoved=true")
        }
        assertNotNull("保留本次 Run 审计", runRepository.runDetail(requireNotNull(completedRunId)))
        baselineRun?.let { assertEquals("原有 Agent Run 不变", it, runRepository.runDetail(it.snapshot.run.id)) }
        assertTrue(profileStore.list().none { it.id == profileId })
    }

    private suspend fun selectedProviderOrFallback(): ProviderProfile {
        val repository = ProviderRepository(context)
        val current = repository.load()
        current.profiles.firstOrNull { it.id == current.selectedProfileId }
            ?.takeIf { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.model.isNotBlank() }
            ?.let { return it }
        val args = InstrumentationRegistry.getArguments()
        val fallback = ProviderProfile.blank().copy(
            name = "兜底 Provider",
            baseUrl = requireNotNull(args.getString("stage264FallbackBaseUrl")),
            apiKey = requireNotNull(args.getString("stage264FallbackApiKey")),
            model = requireNotNull(args.getString("stage264FallbackModel")),
            availableModels = listOf(requireNotNull(args.getString("stage264FallbackModel"))),
            enabledModels = listOf(requireNotNull(args.getString("stage264FallbackModel"))),
        )
        // long: 首次安装可能没有 Provider；兜底只从运行参数进入 Keystore，保留已有其他配置且不把凭据写入测试源码或日志。
        repository.save(current.profiles + fallback, fallback.id)
        return repository.load().profiles.single { it.id == fallback.id }
    }

    private fun workState(id: String): WorkInfo.State? = WorkManager.getInstance(context)
        .getWorkInfoById(UUID.fromString(id)).get(10, TimeUnit.SECONDS)?.state

    private suspend fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        phase: String,
        timeoutMs: Long = 20_000L,
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(latest)) {
                println("STAGE264_PHASE phase=$phase runId=${latest.activeAgentRun?.run?.id} status=${latest.activeAgentRun?.run?.status}")
                return latest
            }
            latest.activeAgentRun?.run?.takeIf { it.status.isTerminal && it.status != AgentRunStatus.COMPLETED }?.let {
                error("Stage264 $phase 提前终止：${it.status} ${it.errorMessage}")
            }
            delay(100L)
        }
        error("Stage264 $phase 超时：status=${latest.activeAgentRun?.run?.status} approval=${latest.pendingAgentApproval?.toolName}")
    }

    private fun clickVisibleNode(text: String, alternateText: String? = null, scroll: Boolean = false, requiredToolName: String? = null) {
        val node = awaitVisibleNode(text, alternateText, scroll, requiredToolName = requiredToolName)
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val control = current ?: error("可见节点没有点击控件：$text")
            if (control.isVisibleToUser && control.isClickable && control.isEnabled) {
                assertTrue("可见控件点击失败：$text", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            current = control.parent
        }
        error("可见节点没有点击控件：$text")
    }

    private fun assertVisible(text: String, exact: Boolean = false) {
        awaitVisibleNode(text, exact = exact)
    }

    private fun awaitVisibleNode(
        text: String,
        alternateText: String? = null,
        scroll: Boolean = false,
        exact: Boolean = true,
        requiredToolName: String? = null,
    ): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 20_000L
        var nextScrollAt = 0L
        var towardNewer = true
        while (SystemClock.uptimeMillis() < deadline) {
            // long: Compose 列表与 Activity 重建会留下旧子节点缓存；每次都重新读取当前窗口，不能点击已经离屏的历史节点。
            automation.clearCache()
            val root = automation.rootInActiveWindow
            root?.refresh()
            root?.findNode { node ->
                val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                node.isVisibleToUser && listOfNotNull(text, alternateText).any { expected ->
                    values.any { if (exact) it == expected else it.contains(expected) }
                } && (requiredToolName == null || node.belongsToToolResult(requiredToolName))
            }?.let { return it }
            if (scroll && SystemClock.uptimeMillis() >= nextScrollAt) {
                root?.findNode { it.isVisibleToUser && it.isScrollable }?.let { list ->
                    val forward = list.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                    val backward = list.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
                    if (towardNewer && !forward && backward) towardNewer = false
                    if (!towardNewer && !backward && forward) towardNewer = true
                    val bounds = Rect()
                    list.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) swipeContent(bounds, towardNewer)
                }
                nextScrollAt = SystemClock.uptimeMillis() + 600L
            }
            SystemClock.sleep(100L)
        }
        error("没有找到当前可见节点：$text tool=$requiredToolName")
    }

    private fun AccessibilityNodeInfo.findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        repeat(childCount) { getChild(it)?.findNode(predicate)?.let { found -> return found } }
        return null
    }

    private fun AccessibilityNodeInfo.belongsToToolResult(toolName: String): Boolean {
        val target = this
        var current = parent
        // long: 同一消息可能承载多条工具结果；按树顺序确认按钮前最近的工具标题，不能仅因祖先包含改期正文就接受 inspect 的入口。
        repeat(5) {
            val node = current ?: return false
            if (node.isScrollable) return false
            var lastToolHeading: String? = null
            var reachedTarget = false
            fun visit(child: AccessibilityNodeInfo) {
                if (reachedTarget) return
                if (child == target) {
                    reachedTarget = true
                    return
                }
                child.text?.toString()?.takeIf { it.startsWith("工具 · ") }?.let { lastToolHeading = it }
                repeat(child.childCount) { index -> child.getChild(index)?.let(::visit) }
            }
            visit(node)
            if (reachedTarget && lastToolHeading != null) return lastToolHeading == "工具 · $toolName"
            current = node.parent
        }
        return false
    }

    private fun swipeContent(bounds: Rect, towardNewer: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val x = bounds.exactCenterX()
        val start = bounds.top + bounds.height() * if (towardNewer) 0.8f else 0.2f
        val end = bounds.top + bounds.height() * if (towardNewer) 0.2f else 0.8f
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, start)
        repeat(8) {
            SystemClock.sleep(40L)
            inject(MotionEvent.ACTION_MOVE, start + (end - start) * (it + 1) / 8f)
        }
        inject(MotionEvent.ACTION_UP, end)
    }

    private fun saveScreenshot(name: String) {
        runCatching {
            val path = "/data/local/tmp/$name"
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p $path")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            println("STAGE264_SCREENSHOT path=$path")
        }.onFailure { println("STAGE264_SCREENSHOT_FAILED ${it.javaClass.simpleName}") }
    }
}
