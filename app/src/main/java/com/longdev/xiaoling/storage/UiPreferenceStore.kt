package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.prompt.PromptDefaults
import com.longdev.xiaoling.prompt.PromptSettings

class UiPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE)

    fun loadThemeMode(): AppThemeMode {
        val stored = preferences.getString(KEY_THEME_MODE, null).orEmpty()
        return AppThemeMode.entries.firstOrNull { it.name == stored } ?: AppThemeMode.SYSTEM
    }

    fun saveThemeMode(mode: AppThemeMode) {
        // long: 主题属于用户使用环境偏好，和 Provider 密钥配置分开保存，避免后续导入/清空 Provider 时误改夜间模式选择。
        preferences.edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }

    fun loadPromptSettings(): PromptSettings {
        return PromptSettings(
            chatPromptEnabled = preferences.getBoolean(KEY_CHAT_PROMPT_ENABLED, false),
            chatPrompt = preferences.getString(KEY_CHAT_PROMPT, PromptDefaults.CHAT) ?: PromptDefaults.CHAT,
            summaryPromptEnabled = preferences.getBoolean(KEY_SUMMARY_PROMPT_ENABLED, false),
            summaryPrompt = preferences.getString(KEY_SUMMARY_PROMPT, PromptDefaults.SUMMARY) ?: PromptDefaults.SUMMARY,
            agentSummaryPromptEnabled = preferences.getBoolean(KEY_AGENT_SUMMARY_PROMPT_ENABLED, false),
            agentSummaryPrompt = preferences.getString(KEY_AGENT_SUMMARY_PROMPT, PromptDefaults.AGENT_SUMMARY)
                ?: PromptDefaults.AGENT_SUMMARY,
        )
    }

    fun savePromptSettings(settings: PromptSettings) {
        // long: 提示词是设备级交互偏好，不随 Provider 或单个会话切换；集中保存可避免换模型时误丢用户长期调整的表达方式。
        preferences.edit()
            .putBoolean(KEY_CHAT_PROMPT_ENABLED, settings.chatPromptEnabled)
            .putString(KEY_CHAT_PROMPT, settings.chatPrompt)
            .putBoolean(KEY_SUMMARY_PROMPT_ENABLED, settings.summaryPromptEnabled)
            .putString(KEY_SUMMARY_PROMPT, settings.summaryPrompt)
            .putBoolean(KEY_AGENT_SUMMARY_PROMPT_ENABLED, settings.agentSummaryPromptEnabled)
            .putString(KEY_AGENT_SUMMARY_PROMPT, settings.agentSummaryPrompt)
            .apply()
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_CHAT_PROMPT_ENABLED = "chat_prompt_enabled"
        private const val KEY_CHAT_PROMPT = "chat_prompt"
        private const val KEY_SUMMARY_PROMPT_ENABLED = "summary_prompt_enabled"
        private const val KEY_SUMMARY_PROMPT = "summary_prompt"
        private const val KEY_AGENT_SUMMARY_PROMPT_ENABLED = "agent_summary_prompt_enabled"
        private const val KEY_AGENT_SUMMARY_PROMPT = "agent_summary_prompt"
    }
}
