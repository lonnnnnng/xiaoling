package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarAccessSettingsPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingPermissionShowsExplicitGrantAndDelegatesActions() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CalendarAccessSettingsContent(
                    readPermissionGranted = false,
                    writePermissionGranted = false,
                    onRequestReadPermission = { events += "read" },
                    onRequestWritePermission = { events += "write" },
                    onOpenSystemSettings = { events += "settings" },
                    onBack = { events += "back" },
                )
            }
        }

        composeRule.onNodeWithText("日历权限未授权").assertExists()
        composeRule.onNodeWithText("授权只读日历").performClick()
        composeRule.onNodeWithText("授权创建日程").performClick()
        composeRule.onNodeWithText("打开系统权限设置").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("read", "write", "settings", "back"), events)
        }
    }

    @Test
    fun grantedPermissionShowsReadOnlyBoundaryAndProfileGate() {
        composeRule.setContent {
            MaterialTheme {
                CalendarAccessSettingsContent(
                    readPermissionGranted = true,
                    writePermissionGranted = true,
                    onRequestReadPermission = {},
                    onRequestWritePermission = {},
                    onOpenSystemSettings = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("日历权限已授权").assertExists()
        composeRule.onNodeWithText("日程创建权限已授权").assertExists()
        composeRule.onNodeWithText("授权只读日历").assertDoesNotExist()
        composeRule.onNodeWithText("只返回标题、开始时间、结束时间和全天标记。").assertExists()
        composeRule.onNodeWithText("创建日程仅支持一次性非全天事件，每次都需前台审批，写入后回读验证。").assertExists()
    }
}
