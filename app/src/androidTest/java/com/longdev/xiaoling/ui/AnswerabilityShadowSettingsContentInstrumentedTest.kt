package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
                    onEnabledChanged = { enabled.value = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("启用答案可回答性 Shadow").performClick()

        composeRule.onNodeWithContentDescription("启用答案可回答性 Shadow").assertIsOn()
    }
}
