package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSampleSummary
import org.junit.Rule
import org.junit.Test

class AnswerabilityShadowSettingsContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explicitSwitchEnablesShadowWithoutHidingItsReadOnlyBoundary() {
        val enabled = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                AnswerabilityShadowSettingsContent(
                    enabled = enabled.value,
                    sampleSummary = KnowledgeAnswerabilityShadowSampleSummary(
                        sampleCount = 3,
                        completedCount = 2,
                        unknownCount = 1,
                        judgeAttemptCount = 3,
                        totalTokens = 42L,
                    ),
                    onEnabledChanged = { enabled.value = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("启用答案可回答性 Shadow").performClick()

        composeRule.onNodeWithContentDescription("启用答案可回答性 Shadow").assertIsOn()
        composeRule.onNodeWithText("样本 3 · 完成 2 · 未知 1 · 跳过 0").assertExists()
        composeRule.onNodeWithText("Judge 尝试 3 次 · 取消 0 · 异常 0").assertExists()
        composeRule
            .onNodeWithText("仅保存在当前进程内；重启后清空，不包含问题、答案、引用、原始响应或密钥。")
            .performScrollTo()
            .assertExists()
    }
}
