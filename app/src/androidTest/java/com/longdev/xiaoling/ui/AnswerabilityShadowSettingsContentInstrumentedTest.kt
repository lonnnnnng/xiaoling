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
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistentSummary
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
                    persistentSummary = KnowledgeAnswerabilityShadowPersistentSummary(
                        observationCount = 5,
                        judgeIdentityCount = 1,
                        completedCount = 4,
                        unknownCount = 1,
                        judgeAttemptCount = 6,
                        totalTokens = 84L,
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
        composeRule.onNodeWithText("观测 5 · Judge 身份 1 · 完成 4 · 未知 1").performScrollTo().assertExists()
        composeRule
            .onNodeWithText("本卡片仅保存在当前进程内，重启后清空；notice 不会从历史消息恢复。")
            .performScrollTo()
            .assertExists()
    }
}
