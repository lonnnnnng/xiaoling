package com.longdev.xiaoling.agent

import java.nio.charset.StandardCharsets

/**
 * long: 标准 SKILL.md 只包含自然语言约定，没有小灵工具、风险和 Android 权限清单；导入后只提供指令，不据此授予任何工具。
 */
object GitHubSkillMarkdownCodec {
    private val validId = Regex("[a-z0-9][a-z0-9._-]{2,63}")

    fun decode(raw: String): AgentSkillDefinition {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= AgentSkillDocumentCodec.MAX_DOCUMENT_BYTES) {
            "GitHub Skill 文件不能超过 64 KiB"
        }
        val normalized = raw.replace("\r\n", "\n").trim()
        require(normalized.startsWith("---\n")) { "SKILL.md 缺少 YAML frontmatter" }
        val end = normalized.indexOf("\n---\n", startIndex = 4)
        require(end > 4) { "SKILL.md frontmatter 未结束" }
        val frontmatter = normalized.substring(4, end).lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim().trim('"', '\'')
            }
            .toMap()
        val name = frontmatter["name"].orEmpty().trim()
        val description = frontmatter["description"].orEmpty().trim()
        val instructions = normalized.substring(end + 5).trim()
        val id = name.lowercase()
        require(validId.matches(id)) { "SKILL.md 的 name 必须是 3-64 位小写字母、数字、点、下划线或连字符" }
        require(name.length <= 80 && description.length in 1..500) { "SKILL.md 的名称或描述超出长度限制" }
        require(instructions.length in 1..8_000) { "SKILL.md 正文必须在 1-8000 字符内" }
        return AgentSkillDefinition(
            id = id,
            version = 1,
            name = name,
            description = description,
            instructions = instructions,
            toolNames = emptySet(),
            keywords = setOf(name),
            triggerExamples = listOf("使用 $name"),
            declaredRisk = ToolRisk.SAFE,
            failureRecovery = "指令无法适用于当前任务时停止，不执行文档中提到的未知工具或命令。",
            completionCriteria = "按导入的指令回答，并只使用 Agent Profile 和其他已选 Skill 允许的工具。",
            source = AgentSkillSource.LOCAL,
        )
    }
}
