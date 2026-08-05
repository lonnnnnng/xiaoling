package com.longdev.xiaoling.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDeviceAccessibilityGateway(context: Context) : DeviceAccessibilityGateway {
    private val appContext = context.applicationContext

    override fun isServiceAuthorized(): Boolean {
        val manager = appContext.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(appContext, XiaoLingAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val serviceInfo = info.resolveInfo.serviceInfo
            serviceInfo.packageName == expected.packageName && serviceInfo.name == expected.className
        }
    }

    override fun isServiceConnected(): Boolean = DeviceAccessibilityRuntime.isConnected()

    override fun currentWindowGeneration(): Long = DeviceAccessibilityRuntime.currentGeneration()

    override suspend fun captureRawWindow(): RawDeviceWindow? {
        return withContext(Dispatchers.Main.immediate) {
            DeviceAccessibilityRuntime.captureRawWindow()
        }
    }

    override suspend fun launchApp(packageName: String): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            DeviceActionPolicy.launchPackageCandidates(packageName).any { candidatePackageName ->
                val intent = appContext.packageManager.getLaunchIntentForPackage(candidatePackageName)
                    ?: return@any false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                // long: 某些 Redmi ROM 只安装 Google 实现；请求的 AOSP 包不可启动时，同族候选仍受显式白名单约束。
                runCatching { appContext.startActivity(intent) }.isSuccess
            }
        }
    }

    override fun isHomePackage(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val home = appContext.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        return home != null && home == packageName
    }

    override suspend fun performGlobalAction(action: DeviceGlobalAction): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            DeviceAccessibilityRuntime.performGlobalAction(action)
        }
    }

    override suspend fun performNodeAction(
        expectedWindowGeneration: Long,
        nodePath: List<Int>,
        expectedFingerprint: String,
        action: DeviceNodeAction,
        text: String?,
        direction: DeviceScrollDirection?,
    ): RawDeviceActionResult {
        return withContext(Dispatchers.Main.immediate) {
            DeviceAccessibilityRuntime.performNodeAction(
                expectedWindowGeneration = expectedWindowGeneration,
                nodePath = nodePath,
                expectedFingerprint = expectedFingerprint,
                action = action,
                text = text,
                direction = direction,
            )
        }
    }
}
