package com.longdev.xiaoling.ui.promptsettings

interface PromptSettingsActions {
    fun updateChatPromptEnabled(value: Boolean)

    fun updateChatPrompt(value: String)

    fun restoreChatPrompt()

    fun updateSummaryPromptEnabled(value: Boolean)

    fun updateSummaryPrompt(value: String)

    fun restoreSummaryPrompt()

    fun updateAgentSummaryPromptEnabled(value: Boolean)

    fun updateAgentSummaryPrompt(value: String)

    fun restoreAgentSummaryPrompt()
}
