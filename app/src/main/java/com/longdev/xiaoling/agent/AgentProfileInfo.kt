package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ApiMode

/**
 * 本次 Agent Run 对外可读的最小 Profile 状态。
 *
 * 这里不携带 Provider ID、地址、系统提示词或工具白名单；状态工具只需要回答用户当前
 * 实际使用的 Agent 和模型，避免把配置审计字段误当成普通回答内容。
 * 作者：long
 */
data class AgentExecutionProfileInfo(
    val name: String,
    val model: String,
    val apiMode: ApiMode,
    val memoryRecallEnabled: Boolean,
)

internal fun AgentProfileSnapshot.toExecutionProfileInfo(
    memoryRecallEnabled: Boolean,
): AgentExecutionProfileInfo = AgentExecutionProfileInfo(
    name = name,
    model = model,
    apiMode = apiMode,
    memoryRecallEnabled = memoryEnabled && memoryRecallEnabled,
)

internal object AgentProfileInfoResultCodec {
    fun encode(info: AgentExecutionProfileInfo): String = buildString {
        appendLine("Agent 名称：${sanitize(info.name, "未命名 Agent")}")
        appendLine("模型：${sanitize(info.model, "未设置")}")
        appendLine("API 模式：${info.apiMode.label}")
        appendLine("本次长期记忆召回：${if (info.memoryRecallEnabled) "已开启" else "已关闭"}")
        append("说明：以上只展示本次 Run 冻结的非敏感状态，不展示敏感配置、系统提示词或内部能力清单。")
    }

    private fun sanitize(value: String, fallback: String): String = value
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(200)
        .ifBlank { fallback }
}
