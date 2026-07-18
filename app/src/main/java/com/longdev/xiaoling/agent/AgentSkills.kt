package com.longdev.xiaoling.agent

data class AgentSkillDefinition(
    val id: String,
    val version: Int = 1,
    val name: String,
    val description: String,
    val instructions: String,
    val toolNames: Set<String>,
    val keywords: Set<String>,
)

interface AgentSkillRegistry {
    fun select(goal: String, limit: Int = 3): List<AgentSkillDefinition>
}

object BuiltInAgentSkillRegistry : AgentSkillRegistry {
    private val skills = listOf(
        AgentSkillDefinition(
            id = "conversation-recall",
            name = "历史会话检索",
            description = "查找和回顾本机历史会话。",
            instructions = "优先使用搜索定位相关会话；用户未给关键词时再列出最近会话。",
            toolNames = setOf("app.list_conversations", "app.search_conversations"),
            keywords = setOf("会话", "聊天", "历史", "之前", "找回", "conversation"),
        ),
        AgentSkillDefinition(
            id = "local-notes",
            name = "本机笔记",
            description = "检索或创建小灵本机笔记。",
            instructions = "查找内容时先搜索笔记；只有用户明确要求记录时才创建笔记。",
            toolNames = setOf("notes.list", "notes.search", "notes.create"),
            keywords = setOf("笔记", "记录", "备忘", "note", "notes"),
        ),
        AgentSkillDefinition(
            id = "personal-memory",
            name = "长期记忆",
            description = "检索或保存用户明确授权的长期记忆。",
            instructions = "回答偏好和长期事实时检索记忆；只有用户明确要求记住时才写入。",
            toolNames = setOf("memory.search", "memory.remember"),
            keywords = setOf("记忆", "记住", "偏好", "习惯", "memory", "remember"),
        ),
        AgentSkillDefinition(
            id = "device-time",
            name = "设备时间",
            description = "读取设备当前时间和时区。",
            instructions = "涉及当前时间、日期或时区时读取设备时间，不根据模型训练时间猜测。",
            toolNames = setOf("app.current_time"),
            keywords = setOf("时间", "日期", "几点", "今天", "时区", "time", "date"),
        ),
    )

    override fun select(goal: String, limit: Int): List<AgentSkillDefinition> {
        val normalized = goal.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        // long: 规则只负责缩小提示词和工具面，不做业务决策；同分时按稳定 ID 排序，保证重试和进程恢复得到一致的 Skill 集合。
        return skills
            .map { skill -> skill to skill.keywords.count { keyword -> normalized.contains(keyword.lowercase()) } }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<AgentSkillDefinition, Int>> { it.second }.thenBy { it.first.id })
            .take(limit.coerceIn(1, 3))
            .map { it.first }
    }
}

class SkillScopedToolRegistry(
    private val delegate: ToolRegistry,
    selectedSkills: List<AgentSkillDefinition>,
) : ToolRegistry, AgentRunContextAwareToolRegistry {
    private val allowedToolNames = selectedSkills.flatMapTo(linkedSetOf()) { it.toolNames }

    init {
        val unregisteredToolNames = allowedToolNames.filter { delegate.definition(it) == null }
        require(unregisteredToolNames.isEmpty()) {
            "Skill 引用了未注册工具：${unregisteredToolNames.sorted().joinToString()}"
        }
    }

    override fun bindRunContext(context: AgentToolExecutionContext) {
        (delegate as? AgentRunContextAwareToolRegistry)?.bindRunContext(context)
    }

    override fun availableTools(): List<ToolDefinition> {
        val available = delegate.availableTools()
        return if (allowedToolNames.isEmpty()) available else available.filter { it.name in allowedToolNames }
    }

    override fun definition(name: String): ToolDefinition? {
        if (allowedToolNames.isNotEmpty() && name !in allowedToolNames) return null
        return delegate.definition(name)
    }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        // long: Skill 只能缩小现有工具面，执行前再次校验白名单；即使模型伪造工具名，也不能绕过 Runtime 的注册表和风险策略。
        check(allowedToolNames.isEmpty() || call.name in allowedToolNames) {
            "Skill 未授权工具：${call.name}"
        }
        return delegate.execute(call)
    }
}
