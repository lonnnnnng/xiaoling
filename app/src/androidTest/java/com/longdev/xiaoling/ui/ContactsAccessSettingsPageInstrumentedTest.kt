package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactsAccessSettingsPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingPermissionShowsExplicitGrantAndDelegatesActions() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                ContactsAccessSettingsContent(
                    permissionGranted = false,
                    onRequestPermission = { events += "grant" },
                    onOpenSystemSettings = { events += "settings" },
                    onBack = { events += "back" },
                )
            }
        }

        composeRule.onNodeWithText("联系人权限未授权").assertExists()
        composeRule.onNodeWithText("授权只读联系人").performClick()
        composeRule.onNodeWithText("打开系统权限设置").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("grant", "settings", "back"), events)
        }
    }

    @Test
    fun grantedPermissionShowsMinimalReadOnlyBoundary() {
        composeRule.setContent {
            MaterialTheme {
                ContactsAccessSettingsContent(
                    permissionGranted = true,
                    onRequestPermission = {},
                    onOpenSystemSettings = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("联系人权限已授权").assertExists()
        composeRule.onNodeWithText("授权只读联系人").assertDoesNotExist()
        composeRule.onNodeWithText("搜索摘要只返回姓名、匹配类型和稳定 ID，不返回具体号码或邮箱。").assertExists()
        composeRule.onNodeWithText("当前只读，不会创建、修改或删除联系人，也不会自动拨号、发短信或发送邮件。").assertExists()
    }
}
