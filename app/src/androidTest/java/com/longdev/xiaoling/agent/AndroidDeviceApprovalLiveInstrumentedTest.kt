package com.longdev.xiaoling.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.device.*
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomWorkflowDeviceActionApprovalPersistence
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** long: 生产人工浮层与真实模型联合，数据放 UUID Room；不把本步骤 Runtime 夸大为正式 Workflow 页面或 ViewModel 验收。 */
class AndroidDeviceApprovalLiveInstrumentedTest {
    @Test fun realModelUsesProductionDeviceApprovalOverlayOnPublicProbe() = runBlocking {
        requireAndroidLiveScope(293)
        val automation = requireNotNull(InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val config = androidLiveProvider(293)
        val args = InstrumentationRegistry.getArguments()
        val mode = requireNotNull(args.getString("stage293Action"))
        val decision = requireNotNull(args.getString("stage293Decision"))
        require(mode in setOf("tap", "type") && decision in setOf("approve", "deny", "window-change"))
        require(mode == "tap" || decision == "approve")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        val roomPrefs = context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE)
        val originalRoom = roomPrefs.all.toMap()
        val undo = File(context.filesDir, "agent-memory-delete-undo.json")
        fun undoHash() = undo.takeIf { it.exists() }?.readBytes()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        } ?: "absent"
        val originalUndoHash = undoHash()
        val file = File(context.applicationInfo.dataDir, "shared_prefs/xiaoling_ui.xml")
        val existed = file.exists()
        val owned = mapOf("device_agent_enabled" to true, "device_action_auto_approval_enabled" to false)
        val authorization = requireNotNull(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        val native = AndroidDeviceAccessibilityGateway(context)
        val nonce = UUID.randomUUID().toString()
        val alphabet = "春夏秋冬山河日月星云天地花草风雨"
        val marker = UUID.randomUUID().toString().filter { it != '-' }.take(8).map { alphabet[it.digitToInt(16)] }.joinToString("")
        val performed = AtomicInteger()
        suspend fun ownsProbe() = withContext(Dispatchers.Main.immediate) { DevicePrivacyProbeActivity.ownsResumedPublicProbe(nonce) }
        val gateway = object : DeviceAccessibilityGateway by native {
            override suspend fun captureRawWindow(): RawDeviceWindow? {
                if (!ownsProbe()) return null
                return native.captureRawWindow()?.takeIf { it.packageName == context.packageName && ownsProbe() }
            }
            override suspend fun performNodeAction(expectedWindowGeneration: Long, nodePath: List<Int>, expectedFingerprint: String,
                action: DeviceNodeAction, text: String?, direction: DeviceScrollDirection?): RawDeviceActionResult {
                // long: 可见审批来自生产 Service；验收动作仍绑定本轮 nonce 和公开节点，禁止批准后误操作同包的正式页面。
                check(ownsProbe()) { "公开 Probe 前台已撤销" }
                var node = requireNotNull(captureRawWindow()).root
                nodePath.forEach { node = node.children[it] }
                check(if (mode == "tap") action == DeviceNodeAction.TAP && node.text == "测试按钮"
                    else action == DeviceNodeAction.TYPE_TEXT && node.editable && text == marker)
                performed.incrementAndGet()
                return native.performNodeAction(expectedWindowGeneration, nodePath, expectedFingerprint, action, text, direction)
            }
        }
        val controller = DeviceObservationController({ prefs.getBoolean("device_agent_enabled", false) }, gateway,
            actionPolicy = DeviceActionPolicy(setOf(context.packageName)))
        try {
            rebind(context, automation, native, authorization)
            val editor = prefs.edit(); owned.forEach { (key, value) -> editor.putBoolean(key, value) }; check(editor.commit())
            val intent = Intent(context, DevicePrivacyProbeActivity::class.java).putExtra(DevicePrivacyProbeActivity.EXTRA_LIVE_NONCE, nonce)
            if (mode == "type") intent.putExtra(DevicePrivacyProbeActivity.EXTRA_MODE, DevicePrivacyProbeActivity.MODE_TYPE_TEXT)
            ActivityScenario.launch<DevicePrivacyProbeActivity>(intent).use {
                withTimeout(10_000) { while (!ownsProbe() || controller.capture() !is DeviceSnapshotCapture.Success) delay(100) }
                val probe = withContext(Dispatchers.Main.immediate) { requireNotNull(DevicePrivacyProbeActivity.currentResumedPublicProbe(nonce)) }
                assertProbe(probe, nonce, mode, "ready", changed = false, checkpoint = "initial")
                AndroidLiveFixture(controller).use { fixture ->
                    val conversation = "conversation-stage293-${UUID.randomUUID()}"
                    val action = if (mode == "tap") "device.tap_ref" else "device.type_text"
                    val target = if (mode == "tap") "点击当前快照中名为测试按钮的 TAP ref" else
                        "向当前快照的普通编辑框 TYPE_TEXT ref 输入公开文字，text 必须逐字等于 JSON 字符串 ${JSONObject.quote(marker)}"
                    val goal = "只先调用 device.snapshot，再$target；只执行一次 $action，完成后直接结束。不打开其他页面，不增加或省略字符。"
                    val workflow = RoomWorkflowRepository(context, fixture.db).createWorkflowAndManualRun("公开浮层验收",
                        listOf(WorkflowStepDefinitionInput(goal)), conversation, targetAppPackage = context.packageName).second
                    val registry = XiaoLingToolRegistry(SystemAgentClock(), RoomAgentConversationStore(context, fixture.db),
                        noteStore = fixture.notes, memoryStore = fixture.memory, knowledgeStore = fixture.knowledge, deviceController = controller)
                    val gate = WorkflowDeviceActionApprovalGate(conversation,
                        if (mode == "tap") "点击公开验收按钮一次" else "在公开输入框输入八个字", context.packageName,
                        fallback = object : ApprovalGate {
                            override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition) =
                                ApprovalDecision(false, "本专项只允许生产设备审批浮层")
                        }, persistence = RoomWorkflowDeviceActionApprovalPersistence(fixture.repo), overlayRequester = DeviceAccessibilityRuntime,
                        autoApproveEnabled = { prefs.getBoolean("device_action_auto_approval_enabled", false) })
                    val runtime = MinimalAgentRuntime(fixture.repo, ProfileScopedToolRegistry(registry, setOf("device.snapshot", action)),
                        OpenAiAgentLlm(OpenAiCompatibleClient(), config, "用简洁中文总结已验证的结果。"), gate,
                        options = AgentRuntimeOptions(maxToolCalls = 3, runTimeoutMs = 180_000, modelStepTimeoutMs = 90_000))
                    // long: 拒绝与窗口撤销会让生产 Runtime 落 FAILED 后抛出；把异常收集为结果，不能先取消父测试而跳过零动作断言。
                    val task = async(Dispatchers.Default) { runCatching { runtime.run(conversation, "message-${UUID.randomUUID()}", goal,
                        invocationSource = AgentInvocationSource.WORKFLOW, memoryRecallEnabled = false,
                        workflowDeviceActionContext = WorkflowDeviceActionRunContext(workflow.run.id, workflow.steps.single().id, goal, context.packageName)) } }
                    try {
                        val pending = withTimeout(120_000) {
                            while (true) {
                                fixture.repo.recentRunDetails(2).singleOrNull()?.approvals?.singleOrNull { it.status == ApprovalRequestStatus.PENDING }?.let { return@withTimeout it }
                                check(!task.isCompleted) { "真实模型未进入设备审批，Run 已结束" }; delay(100)
                            }
                            error("unreachable")
                        }
                        assertEquals(action, pending.toolName)
                        assertTrue(fixture.repo.toolLedger(pending.runId).results.none { it.toolCallId == pending.toolCallId })
                        assertEquals(0, performed.get())
                        assertProbe(probe, nonce, mode, "ready", changed = false, checkpoint = "pending")
                        val overlay = awaitOverlay(automation)
                        val texts = overlay.texts()
                        assertTrue("生产审批标题不可见", "小灵设备动作审批" in texts)
                        assertTrue(texts.any { it.contains(action) })
                        if (mode == "type") {
                            assertFalse(texts.any { it.contains(marker) })
                            assertTrue(texts.any { it.contains("输入 8 个字符，内容不展示") })
                            assertEquals(sha256(marker), pending.arguments["text_sha256"])
                            assertEquals("8", pending.arguments["text_length"])
                        }
                        assertTrue(ownsProbe())
                        // long: 浮层已可见时再核实零执行，避免把展示前的空账本当成用户点击前仍无动作的证明。
                        assertEquals(0, performed.get())
                        assertTrue(fixture.repo.toolLedger(pending.runId).results.none { it.toolCallId == pending.toolCallId })
                        val outcome = if (decision == "window-change") {
                            // long: 切到另一个公开 Probe 产生真实窗口变化；既不读用户页面，也不人工改 Room 状态模拟撤销。
                            ActivityScenario.launch<DevicePrivacyProbeActivity>(Intent(context, DevicePrivacyProbeActivity::class.java)
                                .putExtra(DevicePrivacyProbeActivity.EXTRA_LIVE_NONCE, UUID.randomUUID().toString())).use { task.await() }
                        } else { click(overlay, if (decision == "approve") "批准执行" else "拒绝"); task.await() }
                        val detail = requireNotNull(fixture.repo.runDetail(pending.runId))
                        val approval = detail.approvals.single()
                        val expected = when (decision) { "approve" -> ApprovalRequestStatus.APPROVED; "deny" -> ApprovalRequestStatus.DENIED; else -> ApprovalRequestStatus.CANCELLED }
                        assertEquals(expected, approval.status)
                        assertEquals(pending.id, approval.id)
                        assertEquals(pending.toolCallId, approval.toolCallId)
                        if (decision == "approve") {
                            outcome.getOrThrow()
                            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
                            assertEquals(listOf("device.snapshot", action), detail.toolLedger.calls.map { it.toolName })
                            val result = detail.toolLedger.results.single { it.toolCallId == pending.toolCallId }
                            assertTrue(result.success); assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
                            assertEquals(true, result.executorVerified); assertEquals(1, performed.get())
                            assertProbe(probe, nonce, mode, marker, changed = true, checkpoint = "approved")
                        } else {
                            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
                            assertEquals("工具未获批准：${approval.decisionReason}", outcome.exceptionOrNull()?.message)
                            assertEquals(AgentRunStatus.FAILED, detail.snapshot.run.status)
                            assertTrue(detail.toolLedger.results.none { it.toolCallId == pending.toolCallId })
                            assertEquals(0, performed.get()); assertProbe(probe, nonce, mode, "ready", changed = false, requireForeground = decision != "window-change", checkpoint = decision)
                            if (decision == "window-change") assertTrue(approval.decisionReason.orEmpty().let { "页面" in it || "窗口" in it })
                        }
                        // long: 用户目标含公开输入文字，不能把它当成工具泄露；审批参数与工具结果则必须只保留输入长度和指纹。
                        assertFalse(detail.toolLedger.toString().contains(marker))
                        assertFalse(detail.approvals.toString().contains(marker))
                        withTimeout(10_000) { while (overlayRoot(automation) != null) delay(100) }
                        androidLiveStatus("STAGE293_EVIDENCE OVERLAY action=$mode decision=$decision runId=${pending.runId} productionOverlay=true visibleControl=true zeroBeforeDecision=true status=${detail.snapshot.run.status} approval=$expected performed=${performed.get()} roomVerified=true inputHidden=true")
                    } finally { task.cancelAndJoin() }
                }
            }
        } finally {
            controller.clearReferences()
            // long: 只恢复仍属于本轮的两个偏好键；同期用户变化不被旧快照覆盖，其他恢复也继续尝试并汇总错误。
            val errors = mutableListOf<Throwable>()
            owned.forEach { (key, value) -> runCatching {
                check(prefs.all[key] == value || prefs.all[key] == original[key]) { "偏好已被其他操作修改：$key" }
                val editor = prefs.edit(); if (key in original) editor.putBoolean(key, original[key] as Boolean) else editor.remove(key)
                check(editor.commit())
            }.exceptionOrNull()?.let(errors::add) }
            runCatching { check(prefs.all == original); if (!existed && prefs.all.isEmpty()) check(context.deleteSharedPreferences("xiaoling_ui")) }.exceptionOrNull()?.let(errors::add)
            runCatching { check(roomPrefs.all == originalRoom) { "正式选择偏好发生变化" }; check(undoHash() == originalUndoHash) { "正式 Memory undo 发生变化" } }.exceptionOrNull()?.let(errors::add)
            runCatching { check(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == authorization) }.exceptionOrNull()?.let(errors::add)
            if (errors.isNotEmpty()) throw IllegalStateException("专项恢复失败").also { error -> errors.forEach(error::addSuppressed) }
            androidLiveStatus("STAGE293_EVIDENCE OVERLAY_RESTORE preferencesEqual=true values=${androidLivePreferenceDigest(original)} roomValues=${androidLivePreferenceDigest(originalRoom)} memoryUndo=$originalUndoHash authorizationSelectionEqual=true isolatedRoomRetained=true")
        }
    }

    private suspend fun assertProbe(probe: DevicePrivacyProbeActivity, nonce: String, mode: String, value: String,
        changed: Boolean, requireForeground: Boolean = true, checkpoint: String) = withContext(Dispatchers.Main.immediate) {
        // long: 浮层显示期间不能用活动 Accessibility 根替代底层页面；固定 nonce 的真实 View 回读既不改引用代次，也不会误读同组件其他实例。
        check(probe.intent.getStringExtra(DevicePrivacyProbeActivity.EXTRA_LIVE_NONCE) == nonce)
        if (requireForeground) check(DevicePrivacyProbeActivity.currentResumedPublicProbe(nonce) === probe)
        val layout = probe.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
        if (mode == "type") assertEquals(value, (layout.getChildAt(1) as EditText).text.toString())
        else {
            androidLiveStatus("STAGE293_PROBE_AUDIT checkpoint=$checkpoint nonce=$nonce ${probe.publicClickAudit(nonce)}")
            assertEquals(if (changed) "动作已完成" else "等待测试动作", (layout.getChildAt(1) as TextView).text.toString())
        }
    }
    private fun overlayRoot(automation: UiAutomation): AccessibilityNodeInfo? = automation.windows
        .singleOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() == "XiaoLingDeviceActionApproval" }?.root
    private suspend fun awaitOverlay(automation: UiAutomation): AccessibilityNodeInfo = withTimeout(15_000) {
        while (true) { overlayRoot(automation)?.let { return@withTimeout it }; delay(100) }; error("unreachable")
    }
    private fun AccessibilityNodeInfo.texts(): List<String> = buildList {
        text?.toString()?.let(::add); for (i in 0 until childCount) getChild(i)?.let { addAll(it.texts()) }
    }
    private fun click(root: AccessibilityNodeInfo, text: String) {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == text && node.isClickable && node.isEnabled) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { match -> return match } }
            return null
        }
        check(requireNotNull(find(root)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "生产审批按钮点击失败" }
    }
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun rebind(context: Context, automation: UiAutomation, native: AndroidDeviceAccessibilityGateway, original: String) {
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val expected = ComponentName(context, XiaoLingAccessibilityService::class.java)
        val parts = original.split(':'); check(parts.any { ComponentName.unflattenFromString(it) == expected }) { "用户未授权，拒绝自动开启" }
        if (!native.isServiceConnected()) {
            val reduced = parts.filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":")
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.getString(context.contentResolver, key) == original); check(Settings.Secure.putString(context.contentResolver, key, reduced))
                try { delay(300) } finally { check(Settings.Secure.getString(context.contentResolver, key) == reduced); check(Settings.Secure.putString(context.contentResolver, key, original)) }
            } finally { automation.dropShellPermissionIdentity() }
        }
        withTimeout(15_000) { while (!native.isServiceAuthorized() || !native.isServiceConnected()) delay(100) }
    }
}
