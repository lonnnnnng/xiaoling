package com.longdev.xiaoling.ui.promptsettings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.longdev.xiaoling.prompt.PromptDefaults
import com.longdev.xiaoling.prompt.PromptSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PromptSettingsPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routesEachPromptEditorToItsOwnAction() {
        val settings = mutableStateOf(promptSettings())
        val actions = FakePromptSettingsActions(settings)
        composeRule.setContent {
            MaterialTheme {
                PromptSettingsPage(
                    settings = settings.value,
                    actions = actions,
                    onBack = { actions.backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("prompt-settings-chat-enabled").performClick()
        composeRule.onNodeWithTag("prompt-settings-chat-text").performTextReplacement("chat-updated")
        composeRule.onNodeWithTag("prompt-settings-chat-restore").performClick()

        composeRule.onNodeWithTag("prompt-settings-summary-enabled").performScrollTo().performClick()
        composeRule.onNodeWithTag("prompt-settings-summary-text").performScrollTo().performTextReplacement("summary-updated")
        composeRule.onNodeWithTag("prompt-settings-summary-restore").performScrollTo().performClick()

        composeRule.onNodeWithTag("prompt-settings-agent-summary-enabled").performScrollTo().performClick()
        composeRule.onNodeWithTag("prompt-settings-agent-summary-text").performScrollTo()
            .performTextReplacement("agent-summary-updated")
        composeRule.onNodeWithTag("prompt-settings-agent-summary-restore").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(false), actions.chatEnabledValues)
            assertEquals(listOf("chat-updated"), actions.chatPromptValues)
            assertEquals(1, actions.restoreChatCount)
            assertEquals(listOf(false), actions.summaryEnabledValues)
            assertEquals(listOf("summary-updated"), actions.summaryPromptValues)
            assertEquals(1, actions.restoreSummaryCount)
            assertEquals(listOf(false), actions.agentSummaryEnabledValues)
            assertEquals(listOf("agent-summary-updated"), actions.agentSummaryPromptValues)
            assertEquals(1, actions.restoreAgentSummaryCount)
            assertEquals(1, actions.backCount)
        }
    }

    @Test
    fun previewShowsFinalPolicyAndOnlyOneSectionAtATime() {
        composeRule.setContent {
            MaterialTheme {
                PromptSettingsPage(
                    settings = promptSettings(),
                    actions = FakePromptSettingsActions(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("prompt-settings-chat-preview").performClick()
        composeRule.onNodeWithText("不得声称本轮已经调用工具", substring = true).assertExists()

        composeRule.onNodeWithTag("prompt-settings-summary-preview").performScrollTo().performClick()
        composeRule.onNodeWithText("不得把 source=user", substring = true).assertExists()
        composeRule.onNodeWithText("不得声称本轮已经调用工具", substring = true).assertDoesNotExist()
    }

    private fun promptSettings() = PromptSettings(
        chatPromptEnabled = true,
        chatPrompt = "chat-custom",
        summaryPromptEnabled = true,
        summaryPrompt = "summary-custom",
        agentSummaryPromptEnabled = true,
        agentSummaryPrompt = "agent-summary-custom",
    )

    private class FakePromptSettingsActions(
        private val settings: MutableState<PromptSettings>? = null,
    ) : PromptSettingsActions {
        val chatEnabledValues = mutableListOf<Boolean>()
        val chatPromptValues = mutableListOf<String>()
        var restoreChatCount = 0
        val summaryEnabledValues = mutableListOf<Boolean>()
        val summaryPromptValues = mutableListOf<String>()
        var restoreSummaryCount = 0
        val agentSummaryEnabledValues = mutableListOf<Boolean>()
        val agentSummaryPromptValues = mutableListOf<String>()
        var restoreAgentSummaryCount = 0
        var backCount = 0

        override fun updateChatPromptEnabled(value: Boolean) {
            chatEnabledValues += value
            settings?.let { it.value = it.value.copy(chatPromptEnabled = value) }
        }

        override fun updateChatPrompt(value: String) {
            chatPromptValues += value
            settings?.let { it.value = it.value.copy(chatPrompt = value) }
        }

        override fun restoreChatPrompt() {
            restoreChatCount += 1
            settings?.let { it.value = it.value.copy(chatPrompt = PromptDefaults.CHAT) }
        }

        override fun updateSummaryPromptEnabled(value: Boolean) {
            summaryEnabledValues += value
            settings?.let { it.value = it.value.copy(summaryPromptEnabled = value) }
        }

        override fun updateSummaryPrompt(value: String) {
            summaryPromptValues += value
            settings?.let { it.value = it.value.copy(summaryPrompt = value) }
        }

        override fun restoreSummaryPrompt() {
            restoreSummaryCount += 1
            settings?.let { it.value = it.value.copy(summaryPrompt = PromptDefaults.SUMMARY) }
        }

        override fun updateAgentSummaryPromptEnabled(value: Boolean) {
            agentSummaryEnabledValues += value
            settings?.let { it.value = it.value.copy(agentSummaryPromptEnabled = value) }
        }

        override fun updateAgentSummaryPrompt(value: String) {
            agentSummaryPromptValues += value
            settings?.let { it.value = it.value.copy(agentSummaryPrompt = value) }
        }

        override fun restoreAgentSummaryPrompt() {
            restoreAgentSummaryCount += 1
            settings?.let { it.value = it.value.copy(agentSummaryPrompt = PromptDefaults.AGENT_SUMMARY) }
        }
    }
}
