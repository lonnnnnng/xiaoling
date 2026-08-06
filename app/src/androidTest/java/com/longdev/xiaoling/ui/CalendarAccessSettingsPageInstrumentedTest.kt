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
                    permissionGranted = false,
                    onRequestPermission = { events += "request" },
                    onOpenSystemSettings = { events += "settings" },
                    onBack = { events += "back" },
                )
            }
        }

        composeRule.onNodeWithText("日历权限未授权").assertExists()
        composeRule.onNodeWithText("授权只读日历").performClick()
        composeRule.onNodeWithText("打开系统权限设置").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("request", "settings", "back"), events)
        }
    }

    @Test
    fun grantedPermissionShowsReadOnlyBoundaryAndProfileGate() {
        composeRule.setContent {
            MaterialTheme {
                CalendarAccessSettingsContent(
                    permissionGranted = true,
                    onRequestPermission = {},
                    onOpenSystemSettings = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("日历权限已授权").assertExists()
        composeRule.onNodeWithText("授权只读日历").assertDoesNotExist()
        composeRule.onNodeWithText("只返回标题、开始时间、结束时间和全天标记。").assertExists()
        composeRule.onNodeWithText(
            "授权后仍需在 Agent Profile 中显式启用 calendar.list_events，并启用 calendar-overview Skill。",
        ).assertExists()
    }
}
