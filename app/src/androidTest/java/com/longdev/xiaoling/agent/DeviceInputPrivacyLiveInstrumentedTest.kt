package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.device.*
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.File
import java.security.MessageDigest

/** long: 随机公开 Probe 输入与本机后续观察分别取证；没有用户窗口、自然进程死亡或跨 Run 隐私承诺。 */
class DeviceInputPrivacyLiveInstrumentedTest {
    @Test fun realModelInputKeepsRepeatedConcatenatedAndRecoveredObservationsPrivate() = runBlocking {
        requireAndroidLiveScope(299)
        val automation = requireNotNull(InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        val config = androidLiveProvider(299)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val originalUiFilePresent = File(context.applicationInfo.dataDir, "shared_prefs/xiaoling_ui.xml").exists()
        val keys = listOf("device_agent_enabled", "device_action_auto_approval_enabled")
        check(preferences.edit().putBoolean(keys[0], true).putBoolean(keys[1], false).commit())
        val native = AndroidDeviceAccessibilityGateway(context)
        val nonce = UUID.randomUUID().toString()
        val symbols = "春夏秋冬山河日月星云天地花草风雨"
        val marker = UUID.randomUUID().toString().filter { it != '-' }.take(8).map { symbols[it.digitToInt(16)] }.joinToString("")
        var nativeInputs = 0
        suspend fun ownsProbe() = withContext(Dispatchers.Main.immediate) { DevicePrivacyProbeActivity.ownsResumedPublicProbe(nonce) }
        val gateway = object : DeviceAccessibilityGateway by native {
            override suspend fun captureRawWindow(): RawDeviceWindow? = if (ownsProbe())
                native.captureRawWindow()?.takeIf { it.packageName == context.packageName && ownsProbe() } else null
            override suspend fun performNodeAction(expectedWindowGeneration: Long, nodePath: List<Int>, expectedFingerprint: String,
                action: DeviceNodeAction, text: String?, direction: DeviceScrollDirection?): RawDeviceActionResult {
                check(ownsProbe() && action == DeviceNodeAction.TYPE_TEXT && text == marker) { "仅允许本轮公开输入" }
                nativeInputs++
                return native.performNodeAction(expectedWindowGeneration, nodePath, expectedFingerprint, action, text, direction)
            }
        }
        val controller = DeviceObservationController({ preferences.getBoolean(keys[0], false) }, gateway,
            actionPolicy = DeviceActionPolicy(setOf(context.packageName)))
        try {
            AndroidDeviceLiveInstrumentedTest().rebindPreviouslyAuthorizedService(context, automation, native, 299)
            val intent = Intent(context, DevicePrivacyProbeActivity::class.java)
                .putExtra(DevicePrivacyProbeActivity.EXTRA_LIVE_NONCE, nonce)
                .putExtra(DevicePrivacyProbeActivity.EXTRA_MODE, DevicePrivacyProbeActivity.MODE_TYPE_TEXT)
            ActivityScenario.launch<DevicePrivacyProbeActivity>(intent).use { scenario ->
                withTimeout(10_000) { while (controller.capture() !is DeviceSnapshotCapture.Success) delay(100) }
                AndroidLiveFixture(controller).use { fixture ->
                    val repo = fixture.repo
                    val delegate = XiaoLingToolRegistry(SystemAgentClock(), RoomAgentConversationStore(context, fixture.db),
                        noteStore = fixture.notes, memoryStore = fixture.memory, knowledgeStore = fixture.knowledge, deviceController = controller)
                    var typed = false
                    var protectedExtraSnapshots = 0
                    val registry = object : ToolRegistry by delegate, AgentRunContextAwareToolRegistry by delegate,
                        AgentToolExecutionLifecycleAwareToolRegistry by delegate, AgentRunLifecycleAwareToolRegistry by delegate {
                        override suspend fun execute(call: ToolCall): ToolExecutionResult {
                            val result = delegate.execute(call)
                            if (call.name == "device.type_text" && result.success) {
                                typed = true
                                // long: 先由生产 Controller 精确验证编辑框，再让公开夹具生成拼接与局部回显；这不是额外的模型输入动作。
                                scenario.onActivity { activity ->
                                    val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                                    assertEquals(marker, (root.getChildAt(1) as EditText).text.toString())
                                    root.addView(TextView(activity).apply { this.text = "前缀 $marker 后缀"; contentDescription = marker.take(3) })
                                }
                            }
                            if (call.name == "device.snapshot" && typed) {
                                assertPrivateSnapshot(result)
                                repeat(2) {
                                    assertPrivateSnapshot(delegate.execute(ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE)))
                                    protectedExtraSnapshots++
                                }
                            }
                            return result
                        }
                    }
                    var approvals = 0
                    val gate = object : ApprovalGate {
                        override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                            check(toolCall.name == "device.type_text" && toolCall.arguments["text"] == marker)
                            val request = repo.createApprovalRequest("c", runId, toolCall, definition)
                            check(repo.toolLedger(runId).results.none { it.toolCallId == toolCall.id })
                            repo.decideApprovalRequest(request.id, ApprovalRequestStatus.APPROVED, "仅批准本轮公开文字")
                            approvals++
                            return ApprovalDecision(true, "逐次确认本轮公开文字")
                        }
                    }
                    val names = listOf("device.snapshot", "device.type_text", "device.snapshot")
                    val goal = "严格按顺序执行：device.snapshot；用本轮真实 snapshot_id 和编辑框 ref 调用 device.type_text，text 逐字等于 JSON 字符串 ${org.json.JSONObject.quote(marker)}；再次 device.snapshot；然后 complete。恰好三次调用，一次输入，不执行其他动作。"
                    val summary = MinimalAgentRuntime(repo, ProfileScopedToolRegistry(registry, names.toSet()),
                        OpenAiAgentLlm(OpenAiCompatibleClient(), config, "只返回展示配置 JSON。"), gate,
                        options = AgentRuntimeOptions(maxToolCalls = 4, runTimeoutMs = 210_000, modelStepTimeoutMs = 90_000))
                        .run("c", "m", goal, memoryRecallEnabled = false)
                    fixture.assertTools(summary.runId, names)
                    assertEquals(1, approvals)
                    assertEquals(1, nativeInputs)
                    assertEquals(2, protectedExtraSnapshots)
                    val ledger = repo.toolLedger(summary.runId)
                    assertFalse(ledger.toString().contains(marker))
                    assertPrivateSnapshot(ToolExecutionResult(true, ledger.results.last().content))
                    val raw = requireNotNull((controller.capture() as? DeviceSnapshotCapture.Success)?.snapshot)
                    assertTrue(raw.nodes.any { it.text == marker })
                    assertTrue(raw.nodes.any { it.text == "前缀 $marker 后缀" })
                    fixture.reopen()
                    assertEquals(ledger, fixture.repo.toolLedger(summary.runId))
                    val detail = requireNotNull(fixture.repo.runDetail(summary.runId))
                    assertTrue(DeviceTypeTextSnapshotPrivacyProjection.wasAttempted(detail))
                    val recovered = XiaoLingToolRegistry(SystemAgentClock(), RoomAgentConversationStore(context, fixture.db),
                        noteStore = fixture.notes, memoryStore = fixture.memory, knowledgeStore = fixture.knowledge, deviceController = controller)
                    recovered.bindRunContext(AgentToolExecutionContext("c", "m", summary.runId, "本机回读", protectDeviceDisplayText =
                        DeviceTypeTextSnapshotPrivacyProjection.wasAttempted(detail)))
                    repeat(2) { assertPrivateSnapshot(recovered.execute(ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE))) }
                    recovered.onRunFinished(summary.runId)
                    androidLiveStatus("STAGE299_EVIDENCE INPUT_PRIVACY realModel=true realAccessibility=true publicProbeOnly=true calls=3 approvals=1 nativeInputs=1 exactReadBack=true concatenatedRaw=true repeatedSnapshots=2 recoveredSnapshots=2 roomReopened=true auditPrivate=true runId=${summary.runId}")
                }
            }
        } finally {
            controller.clearReferences()
            val editor = preferences.edit()
            keys.forEach { key -> if (key in original) editor.putBoolean(key, original[key] as Boolean) else editor.remove(key) }
            check(editor.commit())
            assertEquals(original, preferences.all)
            // long: 原偏好文件不存在时，两次临时开关写入留下的空 XML 也应撤销；只能在本轮已恢复空值且原文件缺失时清理。
            if (!originalUiFilePresent && preferences.all.isEmpty()) check(context.deleteSharedPreferences("xiaoling_ui"))
            androidLiveStatus("STAGE299_EVIDENCE INPUT_PRIVACY_RESTORE preferencesEqual=true controlledRecoveryOnly=true")
        }
    }

    @Test fun restoreNewEmptyUiPreferenceFileAfterAcceptance() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("stage299RestoreEmptyUi") == "true" && args.getString("stage299BaselineUiFileAbsent") == "true")
        check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.applicationInfo.dataDir, "shared_prefs/xiaoling_ui.xml")
        val expected = requireNotNull(args.getString("stage299ExpectedEmptyUiFileSha256"))
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        // long: 仅处理本轮基线原先缺失、当前仍为空且字节未漂移的临时文件；任何同期偏好写入都拒绝清理。
        check(expected.matches(Regex("[0-9a-f]{64}")) && expected == actual)
        check(context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE).all.isEmpty())
        check(androidLivePreferenceDigest(context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).all) ==
            args.getString("stage299ExpectedRoomDigest"))
        check(context.deleteSharedPreferences("xiaoling_ui") && !file.exists())
        androidLiveStatus("STAGE299_EVIDENCE EMPTY_UI_RESTORE originalFileAbsent=true emptyValues=true exactFileMatched=true originalRoomValues=true")
    }

    private fun assertPrivateSnapshot(result: ToolExecutionResult) {
        assertTrue(result.success)
        val json = org.json.JSONObject(result.content)
        assertFalse(json.has("window_title"))
        val nodes = json.getJSONArray("nodes")
        assertTrue(nodes.length() > 0)
        var hasRef = false
        repeat(nodes.length()) { index ->
            val node = nodes.getJSONObject(index)
            for (key in listOf("text", "description", "hint")) assertFalse("显示文字不得回到工具输出：$key", node.has(key))
            hasRef = hasRef || node.has("ref")
        }
        assertTrue("节点身份应保留", hasRef)
    }
}
