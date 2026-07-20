package com.longdev.xiaoling.device

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class DeviceAccessibilityServiceInstrumentedTest {
    @Test
    fun manifestDeclaresNodeActionsWithoutGestureOrScreenshotCapability() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, XiaoLingAccessibilityService::class.java)
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertFalse(service.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, service.permission)
        assertTrue(service.metaData.getInt("android.accessibilityservice") != 0)

        val installed = context.getSystemService(AccessibilityManager::class.java)
            .installedAccessibilityServiceList
            .single { info ->
                val installedService = info.resolveInfo.serviceInfo
                installedService.packageName == component.packageName && installedService.name == component.className
            }
        assertTrue(installed.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0)
        assertTrue(installed.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        assertTrue(installed.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        assertTrue(installed.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0)
        assertTrue(installed.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0)
    }

    @Test
    fun manifestQueriesExposeOnlyInitialActionAllowlistLaunchers() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(context.packageManager.getLaunchIntentForPackage("com.longdev.xiaoling") != null)
        assertTrue(context.packageManager.getLaunchIntentForPackage("com.android.calculator2") != null)
        assertTrue(context.packageManager.getLaunchIntentForPackage("com.android.deskclock") != null)
        assertTrue(context.packageManager.getLaunchIntentForPackage("com.android.settings") != null)
    }

}
