package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import org.json.JSONObject

class OpenAiAgentLlm(
    private val client: OpenAiCompatibleClient,
    private val config: ProviderRequestConfig,
    private val summarySystemPrompt: String,
) : AgentLlm {
    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
        val response = client.sendMessage(
            config = config.copy(streamingEnabled = false, temperature = 0.0),
            messages = listOf(
                RequestMessage(
                    role = "system",
                    content = """
                        你是小灵的工具规划器。只能从应用提供的工具中选择一个工具。
                        你必须只返回 JSON，不要返回 Markdown，不要解释。
                        JSON 格式：
                        {"tool":"工具名","arguments":{"参数名":"参数值"}}
                        arguments 只能包含所选工具定义中的参数。没有参数时返回空对象。
                        只有当用户明确希望长期保存事实或偏好时，才选择 memory.remember。
                    """.trimIndent(),
                ),
                RequestMessage(
                    role = "user",
                    content = """
                        用户目标：$goal

                        可用工具：
                        ${tools.joinToString("\n") { tool -> tool.toPromptLine() }}
                    """.trimIndent(),
                ),
            ),
        )
        return AgentToolCallParser.parse(response.responseText, tools)
    }

    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String {
        return client.sendMessage(
            config = config.copy(streamingEnabled = false, temperature = 0.0),
            messages = listOf(
                RequestMessage(
                    role = "system",
                    content = summarySystemPrompt,
                ),
                RequestMessage(
                    role = "user",
                    content = """
                        任务类型：${toolCall.name}
                        结果长度：${toolResult.content.length} 字符

                        根据 system 中的用户偏好选择展示配置。只返回 JSON：
                        {"style":"compact","tone":"neutral"}
                    """.trimIndent(),
                ),
            ),
        ).responseText
    }

    private fun ToolDefinition.toPromptLine(): String {
        val fields = inputSchema
            .joinToString(", ") { field ->
                "${field.name}${if (field.required) "*" else ""}:${field.description}"
            }
            .ifBlank { "无参数" }
        return "- $name: $description; risk=${risk.name}; args=$fields"
    }
}

internal object AgentToolCallParser {
    fun parse(raw: String, tools: List<ToolDefinition>): ToolCall {
        val jsonText = raw.extractJsonObject()
        val json = runCatching { JSONObject(jsonText) }
            .getOrElse { error("模型没有返回有效工具调用 JSON：$raw") }
        val toolName = json.optString("tool").ifBlank { json.optString("name") }
        val definition = tools.firstOrNull { it.name == toolName }
            ?: error("模型选择了未注册工具：$toolName")
        val argumentsJson = json.optJSONObject("arguments") ?: JSONObject()
        val arguments = buildMap {
            argumentsJson.keys().forEach { key -> put(key, argumentsJson.optString(key)) }
        }
        // long: 解析层保留模型原始参数，不替模型补必填字段；缺参必须交给 Runtime 的 tool.validate 失败，这样审计记录能反映真实模型输出。
        return ToolCall(
            name = definition.name,
            arguments = arguments,
            risk = definition.risk,
        )
    }

    private fun String.extractJsonObject(): String {
        val trimmed = trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val fenced = Regex("""```(?:json)?\s*(\{[\s\S]*?})\s*```""").find(trimmed)?.groupValues?.getOrNull(1)
        if (fenced != null) return fenced
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return trimmed
    }
}
