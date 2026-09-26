package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilityPlanningPreflightTest {
    @Test
    fun keepsOnlyDiscoveredPackagesThatAreAlreadyRegistered() {
        val result = AppCapabilityPlanningPreflight.resolve(
            InstalledAppDirectoryReadResult.Success(
                InstalledAppDirectory(
                    apps = listOf(
                        InstalledAppRecord("设置", "com.android.settings", InstalledAppCapability.SETTINGS),
                        InstalledAppRecord("第三方天气", "com.example.weather", InstalledAppCapability.WEATHER),
                        InstalledAppRecord("计算器", "com.android.calculator2", InstalledAppCapability.CALCULATOR),
                    ),
                    truncated = false,
                ),
            ),
        )

        assertEquals(
            listOf("com.android.calculator2", "com.android.settings"),
            (result as AppCapabilityPlanningPreflightResult.Available).allowedAppPackages,
        )
        assertEquals(
            listOf("com.android.calculator2", "com.android.settings"),
            result.availableApps.map(InstalledAppRecord::packageName),
        )
        assertTrue(AppCapabilityPlanningPreflight.validateTargetPackage("com.android.settings", result.allowedAppPackages.toSet()))
        assertFalse(AppCapabilityPlanningPreflight.validateTargetPackage("com.example.weather", result.allowedAppPackages.toSet()))
    }

    @Test
    fun unavailableDirectoryFailsClosed() {
        val result = AppCapabilityPlanningPreflight.resolve(InstalledAppDirectoryReadResult.Unavailable)

        assertEquals(
            AppCapabilityPlanningPreflightResult.Unavailable("当前可启动应用目录不可用"),
            result,
        )
    }

    @Test
    fun noRegisteredDiscoveredPackageFailsClosed() {
        val result = AppCapabilityPlanningPreflight.resolve(
            InstalledAppDirectoryReadResult.Success(
                InstalledAppDirectory(
                    apps = listOf(
                        InstalledAppRecord("第三方应用", "com.example.app", InstalledAppCapability.UNKNOWN),
                    ),
                    truncated = false,
                ),
            ),
        )

        assertEquals(
            AppCapabilityPlanningPreflightResult.Unavailable("当前没有已发现且已登记的可启动设备应用"),
            result,
        )
    }
}
