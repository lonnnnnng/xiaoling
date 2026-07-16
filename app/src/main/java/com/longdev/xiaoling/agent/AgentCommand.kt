package com.longdev.xiaoling.agent

object AgentCommand {
    fun matches(input: String): Boolean = COMMAND_REGEX.containsMatchIn(input.trim())

    fun goal(input: String): String {
        return input.trim()
            .replaceFirst(Regex("^$PREFIX\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "跑通最小 Agent 链路" }
    }

    private const val PREFIX = "/agent"
    private val COMMAND_REGEX = Regex("^$PREFIX(?:\\s|$)", RegexOption.IGNORE_CASE)
}
