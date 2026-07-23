package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.longdev.xiaoling.model.ProviderRequestConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NetworkRequestSettingsContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorExposesCopyClearRestoreAndBackActions() {
        val userAgent = mutableStateOf("custom-user-agent")
        var copiedUserAgent: String? = null
        var resetCount = 0
        var backCount = 0

        composeRule.setContent {
            MaterialTheme {
                NetworkRequestSettingsContent(
                    userAgent = userAgent.value,
                    onUserAgentChanged = { userAgent.value = it },
                    onResetUserAgent = {
                        resetCount += 1
                        userAgent.value = ProviderRequestConfig.DEFAULT_USER_AGENT
                    },
                    onCopyUserAgent = { copiedUserAgent = it },
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("复制 User-Agent").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("custom-user-agent", copiedUserAgent) }

        composeRule.onNodeWithContentDescription("清空 User-Agent").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("", userAgent.value) }
        composeRule.onNodeWithContentDescription("复制 User-Agent").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("清空 User-Agent").assertIsNotEnabled()

        composeRule.onNodeWithTag("network-request-user-agent").performTextReplacement("replacement-user-agent")
        composeRule.runOnIdle { assertEquals("replacement-user-agent", userAgent.value) }

        composeRule.onNodeWithContentDescription("恢复默认 User-Agent").performClick()
        composeRule.runOnIdle {
            assertEquals(1, resetCount)
            assertEquals(ProviderRequestConfig.DEFAULT_USER_AGENT, userAgent.value)
        }

        composeRule.onNodeWithContentDescription("返回设置").performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }
}
