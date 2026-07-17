package com.longdev.xiaoling.prompt

data class PromptSettings(
    val chatPromptEnabled: Boolean = false,
    val chatPrompt: String = PromptDefaults.CHAT,
    val summaryPromptEnabled: Boolean = false,
    val summaryPrompt: String = PromptDefaults.SUMMARY,
    val agentSummaryPromptEnabled: Boolean = false,
    val agentSummaryPrompt: String = PromptDefaults.AGENT_SUMMARY,
)

object PromptDefaults {
    const val CHAT = "默认使用中文，回答直接、清晰；信息不足时明确说明，不编造事实。"
    const val SUMMARY = "优先保留用户的长期偏好、目标、约束、已确认事实和未解决问题。"
    const val AGENT_SUMMARY = "默认使用中文简洁汇报任务是否完成、调用了什么工具以及执行结果。"
}
