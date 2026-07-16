package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import org.json.JSONObject

class OpenAiAgentLlm(
    private val client: OpenAiCompatibleClient,
    private val config: ProviderRequestConfig,
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
                        {"tool":"工具名","arguments":{"goal":"用户目标"}}
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
                    content = "你是小灵的 Agent 总结器。根据工具执行结果，用中文简洁说明任务是否完成、调用了什么工具、结果是什么。",
                ),
                RequestMessage(
                    role = "user",
                    content = """
                        用户目标：$goal
                        工具：${toolCall.name}
                        工具参数：${toolCall.arguments}
                        工具结果：${toolResult.content}

                        请输出最终回复。
                    """.trimIndent(),
                ),
            ),
        ).responseText
    }

    private fun ToolDefinition.toPromptLine(): String {
        val requiredFields = inputSchema
            .filter { it.required }
            .joinToString(", ") { it.name }
            .ifBlank { "无" }
        return "- $name: $description; risk=${risk.name}; required_args=$requiredFields"
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
