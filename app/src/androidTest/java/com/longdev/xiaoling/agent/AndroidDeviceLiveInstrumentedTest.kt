package com.longdev.xiaoling.agent

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** long: 系统无障碍须由用户事先开启；公开宿主页之外的原始窗口不进入模型，点击/输入仍逐次审批。 */
class AndroidDeviceLiveInstrumentedTest {
    @Test fun realPlannerObservesAndActsOnlyOnPublicNativeProbe() = runBlocking {
        // long: instrumentation 会重启宿主进程；先保留系统服务，再等用户已授权的服务重新绑定，不能把瞬时空列表当成撤权。
        requireAndroidLiveScope()
        val automation = requireNotNull(InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        val config = androidLiveProvider()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mode = requireNotNull(InstrumentationRegistry.getArguments().getString("stage292DeviceAction"))
        require(mode in setOf("tap", "type", "scroll"))
        val preferences = context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val file = File(context.applicationInfo.dataDir, "shared_prefs/xiaoling_ui.xml")
        val originalBytes = file.takeIf { it.exists() }?.readBytes()
        val keys = listOf("device_agent_enabled", "device_action_auto_approval_enabled")
        check(preferences.edit().putBoolean(keys[0], true).putBoolean(keys[1], false).commit())
        val native = AndroidDeviceAccessibilityGateway(context)
        val nonce = UUID.randomUUID().toString()
        suspend fun ownsProbe() = withContext(Dispatchers.Main.immediate) { DevicePrivacyProbeActivity.ownsResumedPublicProbe(nonce) }
        val gateway = object : DeviceAccessibilityGateway by native {
            override suspend fun captureRawWindow(): RawDeviceWindow? {
                if (!ownsProbe()) return null
                return native.captureRawWindow()?.takeIf { it.packageName == "com.longdev.xiaoling" && ownsProbe() }
            }
            override suspend fun performNodeAction(expectedWindowGeneration: Long, nodePath: List<Int>, expectedFingerprint: String,
                action: DeviceNodeAction, text: String?, direction: DeviceScrollDirection?): RawDeviceActionResult {
                check(ownsProbe()) { "公开 Probe 前台已撤销，拒绝设备动作" }
                val window = requireNotNull(captureRawWindow())
                var node = window.root
                nodePath.forEach { index -> node = node.children[index] }
                check(when (action) {
                    DeviceNodeAction.TAP -> mode == "tap" && node.text == "测试按钮"
                    DeviceNodeAction.TYPE_TEXT -> mode == "type" && node.editable
                    DeviceNodeAction.SWIPE -> mode == "scroll" && node.scrollable
                }) { "设备动作目标不是本轮公开验收节点" }
                return native.performNodeAction(expectedWindowGeneration, nodePath, expectedFingerprint, action, text, direction)
            }
        }
        val controller = DeviceObservationController({ preferences.getBoolean(keys[0], false) }, gateway,
            actionPolicy = DeviceActionPolicy(setOf("com.longdev.xiaoling")))
        try {
            rebindPreviouslyAuthorizedService(context, automation, native)
            val intent = Intent(context, DevicePrivacyProbeActivity::class.java).putExtra(DevicePrivacyProbeActivity.EXTRA_LIVE_NONCE, nonce)
            if (mode == "type") intent.putExtra(DevicePrivacyProbeActivity.EXTRA_MODE, DevicePrivacyProbeActivity.MODE_TYPE_TEXT)
            if (mode == "scroll") intent.putExtra(DevicePrivacyProbeActivity.EXTRA_MODE, DevicePrivacyProbeActivity.MODE_SCROLL)
            ActivityScenario.launch<DevicePrivacyProbeActivity>(intent).use { scenario ->
                withTimeout(15_000) { while (!native.isServiceAuthorized() || !native.isServiceConnected()) delay(100) }
                assertTrue("用户已授权的系统无障碍服务未恢复连接", native.isServiceAuthorized())
                withTimeout(10_000) {
                    while (controller.capture() !is DeviceSnapshotCapture.Success) delay(100)
                }
                AndroidLiveFixture(controller).use { f ->
                    // long: 数字 UUID 会命中手机号/身份串保护；公开 marker 改用随机汉字，保持生产敏感文本拒绝规则。
                    val symbols = "春夏秋冬山河日月星云天地花草风雨"
                    val marker = UUID.randomUUID().toString().filter { it != '-' }.take(8).map { symbols[it.digitToInt(16)] }.joinToString("")
                    val action = when (mode) { "tap" -> "device.tap_ref"; "type" -> "device.type_text"; else -> "device.swipe" }
                    val target = when (mode) {
                        "tap" -> "点击当前快照中名为测试按钮的 TAP ref"
                        "type" -> "向当前快照的普通编辑框 TYPE_TEXT ref 输入公开文字；参数 text 必须逐字等于 JSON 字符串 ${org.json.JSONObject.quote(marker)}，不增加或省略字符"
                        else -> "对当前快照的可滚动 SWIPE ref 使用 direction=up 上滑一次以读取下面的条目"
                    }
                    val result = f.run(config, listOf("device.snapshot", action),
                        "先调用 device.snapshot 观察公开验收页，再用 $action $target，使用本轮真实 snapshot_id/ref，然后 complete。两个工具各调用一次，不打开应用、不点击其他节点、不执行额外动作。") { call ->
                        if (mode == "type") androidLiveStatus("STAGE292_INPUT_PROPOSAL toolMatches=${call.name == action} keys=${call.arguments.keys.sorted().joinToString(",")} expectedLength=${marker.length} actualLength=${call.arguments["text"]?.length} exact=${call.arguments["text"] == marker}")
                        call.name == action && (mode != "type" || call.arguments["text"] == marker)
                    }
                    f.assertTools(result.runId, listOf("device.snapshot", action))
                    assertEquals(if (mode == "scroll") 0 else 1, f.approvals)
                    val ledger = f.repo.toolLedger(result.runId)
                    assertEquals(true, ledger.results.last().executorVerified)
                    if (mode == "tap") scenario.onActivity { activity ->
                        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                        assertEquals("动作已完成", (root.getChildAt(1) as TextView).text.toString())
                    }
                    if (mode == "type") {
                        scenario.onActivity { activity ->
                            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                            assertEquals(marker, (root.getChildAt(1) as EditText).text.toString())
                        }
                        assertFalse(ledger.toString().contains(marker))
                        val actionId = ledger.calls.last().id
                        assertTrue(f.repo.contextTranscript(result.runId).filter { actionId in it.input.verifiedToolCallIds }
                            .all { !it.input.toString().contains(marker) })
                    }
                    if (mode == "scroll") scenario.onActivity { activity ->
                        val scroll = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView
                        assertTrue("公开滚动页实际位置没有变化", scroll.scrollY > 0)
                    }
                    androidLiveStatus("STAGE292_EVIDENCE DEVICE action=$mode runId=${result.runId} realAccessibility=true realModel=true publicProbeOnly=true approvals=${f.approvals} executorVerified=true roomPassed=true")
                }
            }
        } finally {
            controller.clearReferences()
            val editor = preferences.edit()
            keys.forEach { key -> if (key in original) editor.putBoolean(key, original[key] as Boolean) else editor.remove(key) }
            check(editor.commit())
            assertEquals(original, preferences.all)
            // long: 只撤销本轮两个开关；原文件不直接写回，避免覆盖同期其他偏好或绕开 SharedPreferences 内存状态。
            if (originalBytes == null && preferences.all.isEmpty()) check(context.deleteSharedPreferences("xiaoling_ui"))
        }
    }

    private suspend fun rebindPreviouslyAuthorizedService(context: Context, automation: android.app.UiAutomation,
        native: AndroidDeviceAccessibilityGateway) {
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val original = requireNotNull(Settings.Secure.getString(context.contentResolver, key)) { "系统未授权，拒绝自动开启" }
        val expected = ComponentName(context, XiaoLingAccessibilityService::class.java)
        val components = original.split(':')
        check(components.any { ComponentName.unflattenFromString(it) == expected }) { "系统未授权，拒绝自动开启" }
        if (!native.isServiceConnected()) {
            // long: Runner 重启宿主把已授权服务留在 crashed 集合；只临时重绑原组件，其他服务与原字符串保持，绝不新增权限。
            val withoutOwn = components.filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":")
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.getString(context.contentResolver, key) == original) { "系统授权选择已变化" }
                check(Settings.Secure.putString(context.contentResolver, key, withoutOwn))
                try { delay(300) } finally {
                    check(Settings.Secure.getString(context.contentResolver, key) == withoutOwn) { "重绑期间授权选择已变化，拒绝覆盖" }
                    check(Settings.Secure.putString(context.contentResolver, key, original))
                }
            } finally { automation.dropShellPermissionIdentity() }
            androidLiveStatus("STAGE292_EVIDENCE DEVICE_REBIND previouslyAuthorized=true otherServicesPreserved=true originalSelectionRestored=true controlledInstrumentation=true")
        }
        withTimeout(15_000) { while (!native.isServiceAuthorized() || !native.isServiceConnected()) delay(100) }
        check(Settings.Secure.getString(context.contentResolver, key) == original)
    }
}
