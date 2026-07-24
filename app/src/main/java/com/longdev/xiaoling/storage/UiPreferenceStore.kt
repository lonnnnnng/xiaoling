package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceRolloutPreference
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

    fun loadMemoryCandidatesEnabled(): Boolean {
        return preferences.getBoolean(KEY_MEMORY_CANDIDATES_ENABLED, false)
    }

    fun saveMemoryCandidatesEnabled(enabled: Boolean) {
        // long: 候选记忆可能处理个人陈述，必须由用户主动开启并持久化选择；首次安装和旧版本升级都保持关闭。
        preferences.edit()
            .putBoolean(KEY_MEMORY_CANDIDATES_ENABLED, enabled)
            .apply()
    }

    fun loadUserAgent(): String {
        return preferences.getString(KEY_USER_AGENT, ProviderRequestConfig.DEFAULT_USER_AGENT)
            ?.trim()
            .orEmpty()
            .ifBlank { ProviderRequestConfig.DEFAULT_USER_AGENT }
    }

    fun saveUserAgent(userAgent: String) {
        // long: UA 会影响兼容网关的路由判断，作为设备级网络偏好独立保存；过滤换行可防止无效 Header 或 Header 注入。
        val normalized = userAgent
            .filterNot { it == '\r' || it == '\n' }
            .take(MAX_USER_AGENT_LENGTH)
            .trim()
            .ifBlank { ProviderRequestConfig.DEFAULT_USER_AGENT }
        preferences.edit()
            .putString(KEY_USER_AGENT, normalized)
            .apply()
    }

    fun loadReasoningSummaryEnabled(): Boolean {
        return preferences.getBoolean(KEY_REASONING_SUMMARY_ENABLED, false)
    }

    fun saveReasoningSummaryEnabled(enabled: Boolean) {
        // long: 供应商推理摘要是显式 opt-in，旧版本升级和首次安装都保持关闭；用户选择单独持久化，不能随 Provider 切换被意外开启。
        preferences.edit()
            .putBoolean(KEY_REASONING_SUMMARY_ENABLED, enabled)
            .apply()
    }

    fun loadDeviceAgentEnabled(): Boolean {
        return preferences.getBoolean(KEY_DEVICE_AGENT_ENABLED, false)
    }

    fun saveDeviceAgentEnabled(enabled: Boolean) {
        // long: Accessibility 可读取跨应用界面结构，必须与系统授权分开由用户再次显式开启；首次安装和旧版本升级始终保持关闭。
        preferences.edit()
            .putBoolean(KEY_DEVICE_AGENT_ENABLED, enabled)
            .apply()
    }

    fun loadKnowledgeRelevanceRolloutPreference(): KnowledgeRelevanceRolloutPreference {
        if (!preferences.getBoolean(KEY_KNOWLEDGE_RELEVANCE_ENFORCEMENT_ENABLED, false)) {
            return KnowledgeRelevanceRolloutPreference()
        }
        return KnowledgeRelevanceRolloutPreference(
            enforcementEnabled = true,
            gateVersion = preferences.getString(KEY_KNOWLEDGE_RELEVANCE_GATE_VERSION, null)?.trim()?.ifBlank { null },
            providerId = preferences.getString(KEY_KNOWLEDGE_RELEVANCE_PROVIDER_ID, null)?.trim()?.ifBlank { null },
            model = preferences.getString(KEY_KNOWLEDGE_RELEVANCE_MODEL, null)?.trim()?.ifBlank { null },
        )
    }

    fun saveKnowledgeRelevanceRolloutPreference(preference: KnowledgeRelevanceRolloutPreference) {
        if (!preference.enforcementEnabled) {
            rollbackKnowledgeRelevanceRollout()
            return
        }
        // long: 这里只保存用户请求的灰度身份；真正执行前仍由 rollout policy 与当前冻结 gate 逐项核对，不能把本地布尔值直接当作上线资格。
        preferences.edit()
            .putBoolean(KEY_KNOWLEDGE_RELEVANCE_ENFORCEMENT_ENABLED, true)
            .putString(KEY_KNOWLEDGE_RELEVANCE_GATE_VERSION, preference.gateVersion?.trim()?.ifBlank { null })
            .putString(KEY_KNOWLEDGE_RELEVANCE_PROVIDER_ID, preference.providerId?.trim()?.ifBlank { null })
            .putString(KEY_KNOWLEDGE_RELEVANCE_MODEL, preference.model?.trim()?.ifBlank { null })
            .apply()
    }

    fun rollbackKnowledgeRelevanceRollout() {
        // long: 撤销必须清除执行位和绑定身份，避免未来同名 gate 或模型重新出现时沿用一次旧授权。
        preferences.edit()
            .remove(KEY_KNOWLEDGE_RELEVANCE_ENFORCEMENT_ENABLED)
            .remove(KEY_KNOWLEDGE_RELEVANCE_GATE_VERSION)
            .remove(KEY_KNOWLEDGE_RELEVANCE_PROVIDER_ID)
            .remove(KEY_KNOWLEDGE_RELEVANCE_MODEL)
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
        private const val KEY_MEMORY_CANDIDATES_ENABLED = "memory_candidates_enabled"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_REASONING_SUMMARY_ENABLED = "reasoning_summary_enabled"
        private const val KEY_DEVICE_AGENT_ENABLED = "device_agent_enabled"
        private const val KEY_KNOWLEDGE_RELEVANCE_ENFORCEMENT_ENABLED = "knowledge_relevance_enforcement_enabled"
        private const val KEY_KNOWLEDGE_RELEVANCE_GATE_VERSION = "knowledge_relevance_gate_version"
        private const val KEY_KNOWLEDGE_RELEVANCE_PROVIDER_ID = "knowledge_relevance_provider_id"
        private const val KEY_KNOWLEDGE_RELEVANCE_MODEL = "knowledge_relevance_model"
        private const val MAX_USER_AGENT_LENGTH = 512
    }
}
