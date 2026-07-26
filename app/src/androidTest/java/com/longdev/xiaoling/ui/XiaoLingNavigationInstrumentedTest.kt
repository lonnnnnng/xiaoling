package com.longdev.xiaoling.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.MainActivity
import org.junit.Rule
import org.junit.Test

class XiaoLingNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackReturnsFromSettingsSubPageBeforeShowingExitNotice() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("bottom_tab_settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("bottom_tab_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("模型提供方管理").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("网络请求").performClick()
        composeRule.onNodeWithContentDescription("返回设置").assertExists()

        pressSystemBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("模型提供方管理").fetchSemanticsNodes().isNotEmpty()
        }

        // long: 设置根页属于应用根导航；第一次系统返回只展示退出提示，不能直接结束 Activity。
        pressSystemBack()
        composeRule.onNodeWithText("再返回一次退出应用").assertExists()
    }

    private fun pressSystemBack() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }
}
