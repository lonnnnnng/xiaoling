package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Collator
import java.util.Locale

/**
 * long: 真机验收只读取当前 Launcher 可启动目录，确认发现层能看到真实入口但不会把包信息扩展成版本、权限或 Provider 数据。
 */
@RunWith(AndroidJUnit4::class)
class InstalledAppDirectoryInstrumentedTest {
    @Test
    fun readsCurrentLauncherDirectoryWithPrivacySafeCandidates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidInstalledAppDirectoryReader(context).read()
        assertTrue("Launcher 目录读取失败：$result", result is InstalledAppDirectoryReadResult.Success)

        val directory = (result as InstalledAppDirectoryReadResult.Success).directory
        assertTrue("真实设备至少应有一个可启动应用", directory.apps.isNotEmpty())
        assertTrue(directory.apps.size <= InstalledAppDirectoryPolicy.MAX_APPS)
        assertNotNull(
            "小灵自身应作为当前用户可启动入口被发现",
            directory.apps.firstOrNull { it.packageName == context.packageName },
        )
        assertTrue(
            "目录必须按中文/大小写无关的应用名稳定排序",
            directory.apps == directory.apps.sortedWith(
                Comparator { left, right ->
                    val appNameOrder = Collator.getInstance(Locale.CHINA).compare(left.appName, right.appName)
                    if (appNameOrder != 0) appNameOrder else left.packageName.compareTo(right.packageName)
                },
            ),
        )
        assertTrue(directory.apps.all { it.appName.none(Char::isISOControl) })
        assertTrue(directory.apps.all { it.packageName.matches(PACKAGE_NAME) })

        val encoded = InstalledAppDirectoryResultCodec.encode(directory)
        assertTrue(encoded.contains("launcher_directory"))
        assertFalse(encoded.contains("version"))
        assertFalse(encoded.contains("signature"))
        assertFalse(encoded.contains("permission"))
        assertFalse(encoded.contains("provider"))
        assertFalse(encoded.contains("install_source"))
        println(
            "STAGE270_APP_DIRECTORY count=${directory.apps.size} " +
                "truncated=${directory.truncated} selfFound=true privacySafe=true",
        )
    }

    @Test
    fun planningPreflightUsesOnlyDiscoveredRegisteredPackages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AppCapabilityPlanningPreflight.resolve(
            AndroidInstalledAppDirectoryReader(context).read(),
        )

        assertTrue("真实设备没有可用于设备规划的已登记应用：$result", result is AppCapabilityPlanningPreflightResult.Available)
        val allowedPackages = (result as AppCapabilityPlanningPreflightResult.Available).allowedAppPackages
        assertTrue(allowedPackages.isNotEmpty())
        assertTrue(allowedPackages.all { it in com.longdev.xiaoling.device.DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES })
        assertTrue(allowedPackages.contains(context.packageName))
        println("STAGE271_APP_PREFLIGHT allowedPackages=${allowedPackages.size} whitelistIntersection=true")
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}
