package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import org.json.JSONObject
import java.math.BigDecimal

class OpenAiAgentLlm(
    private val client: OpenAiCompatibleClient,
    private val config: ProviderRequestConfig,
    private val summarySystemPrompt: String,
    private val selectedSkills: List<AgentSkillDefinition> = emptyList(),
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
                        {"tool":"工具名","arguments":{"文本参数":"内容","整数参数":1,"数值参数":0.5,"布尔参数":true}}
                        arguments 必须满足所选工具的 inputSchema，不能增加未声明参数。没有参数时返回空对象。
                        只有当用户明确希望长期保存事实或偏好时，才选择 memory.remember。
                    """.trimIndent(),
                ),
                RequestMessage(
                    role = "user",
                    content = """
                        用户目标：$goal

                        可用工具：
                        ${tools.joinToString("\n") { tool -> tool.toModelPromptLine() }}

                        按目标选中的 Skill：
                        ${selectedSkills.toModelPromptBlock()}
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
}

private fun List<AgentSkillDefinition>.toModelPromptBlock(): String {
    if (isEmpty()) return "未命中专用 Skill，按可用工具定义处理。"
    return joinToString("\n") { skill ->
        "- ${skill.name}：${skill.instructions}"
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
        val argumentsJson = when (val rawArguments = json.opt("arguments")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> rawArguments
            else -> error("工具 ${definition.name} 的 arguments 必须是 JSON object")
        }
        val fields = definition.inputSchema.associateBy { it.name }
        val arguments = buildMap {
            argumentsJson.keys().forEach { key ->
                val rawValue = argumentsJson.get(key)
                val field = fields[key]
                put(key, if (field == null) rawValue.toString() else field.normalizeJsonValue(rawValue))
            }
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

    private fun ToolInputField.normalizeJsonValue(rawValue: Any): String {
        return when (type) {
            ToolInputType.STRING -> (rawValue as? String)
                ?: error("工具参数 $name 必须使用 JSON string")
            ToolInputType.INTEGER -> {
                val number = rawValue as? Number ?: error("工具参数 $name 必须使用 JSON integer")
                runCatching { BigDecimal(number.toString()).longValueExact() }
                    .getOrElse { error("工具参数 $name 必须是 Long 范围内的 JSON integer") }
                    .toString()
            }
            ToolInputType.NUMBER -> {
                val number = rawValue as? Number ?: error("工具参数 $name 必须使用 JSON number")
                if (!number.toDouble().isFinite()) error("工具参数 $name 必须使用有限 JSON number")
                number.toString()
            }
            ToolInputType.BOOLEAN -> (rawValue as? Boolean)
                ?.toString()
                ?: error("工具参数 $name 必须使用 JSON boolean")
        }
    }
}
