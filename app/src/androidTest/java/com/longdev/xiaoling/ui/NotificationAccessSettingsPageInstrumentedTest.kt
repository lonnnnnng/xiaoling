package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationAccessSettingsPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deniedStateShowsSystemSettingsAndReadOnlyBoundary() {
        composeRule.setContent {
            MaterialTheme {
                NotificationAccessSettingsContent(
                    accessGranted = false,
                    connected = false,
                    onOpenSystemSettings = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("通知访问未授权").assertExists()
        composeRule.onNodeWithText("打开系统通知访问设置").assertExists()
        composeRule.onNodeWithText("当前不会点击、回复、清除通知，也不会执行通知动作或跳转第三方应用。").assertExists()
    }

    @Test
    fun grantedConnectedStateShowsAvailable() {
        composeRule.setContent {
            MaterialTheme {
                NotificationAccessSettingsContent(
                    accessGranted = true,
                    connected = true,
                    onOpenSystemSettings = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("通知访问可用").assertExists()
        composeRule.onNodeWithText("只有前台 Agent 且 Profile/Skill 显式允许时才能读取当前通知。").assertExists()
    }
}
