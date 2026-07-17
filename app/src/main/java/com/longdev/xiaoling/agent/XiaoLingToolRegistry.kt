package com.longdev.xiaoling.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

class XiaoLingToolRegistry(
    private val clock: AgentClock,
    private val conversationStore: AgentConversationStore,
    private val noteStore: AgentNoteStore,
    private val memoryStore: AgentMemoryStore,
) : ToolRegistry, AgentRunContextAwareToolRegistry {
    private var runContext: AgentToolExecutionContext? = null

    private val tools = listOf(
        ToolDefinition(
            name = "app.current_time",
            description = "读取手机本地当前时间和时区，用于需要时间上下文的任务。",
            risk = ToolRisk.SAFE,
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "app.list_conversations",
            description = "列出最近的本地会话标题、消息数和更新时间，用于帮助用户回到历史对话。",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "app.search_conversations",
            description = "按关键词搜索本地会话标题、摘要和消息内容，用于查找旧会话。",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "搜索关键词。",
                    required = true,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.list",
            description = "列出最近创建的本地笔记。",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.search",
            description = "按关键词搜索本地笔记标题和正文。",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "搜索关键词。",
                    required = true,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.create",
            description = "创建一条本地笔记；写入前需要用户确认，写入后会回读验证。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField(
                    name = "title",
                    description = "笔记标题。",
                    required = true,
                ),
                ToolInputField(
                    name = "content",
                    description = "笔记正文。",
                    required = true,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "memory.search",
            description = "检索本机长期记忆，帮助回答用户偏好、历史事实或长期备注。",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "检索关键词；为空时返回最近记忆。",
                    required = false,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "memory.remember",
            description = "把用户明确希望长期保留的偏好、事实或备注写入本机长期记忆；写入前必须经过用户审批。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField(
                    name = "note",
                    description = "要长期保留的具体事实或偏好，必须来自用户当前明确表达的内容。",
                    required = true,
                ),
                ToolInputField(
                    name = "type",
                    description = "记忆类型：Preference、ProfileFact、Episode 或 Procedure。",
                    required = false,
                ),
                ToolInputField(
                    name = "tags",
                    description = "可选标签，多个标签用逗号分隔。",
                    required = false,
                ),
            ),
            timeoutMs = 5_000,
        ),
    )

    override fun bindRunContext(context: AgentToolExecutionContext) {
        runContext = context
    }

    override fun availableTools(): List<ToolDefinition> = tools

    override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        return when (call.name) {
            "app.current_time" -> currentTime()
            "app.list_conversations" -> listConversations(call)
            "app.search_conversations" -> searchConversations(call)
            "notes.list" -> listNotes(call)
            "notes.search" -> searchNotes(call)
            "notes.create" -> createNote(call)
            "memory.search" -> searchMemory(call)
            "memory.remember" -> remember(call)
            else -> ToolExecutionResult(success = false, content = "未知工具：${call.name}")
        }
    }

    private fun currentTime(): ToolExecutionResult {
        return ToolExecutionResult(
            success = true,
            content = "当前时间：${clock.formattedNow()} · 时区：${clock.zoneId()}",
        )
    }

    private suspend fun listConversations(call: ToolCall): ToolExecutionResult {
        val conversations = conversationStore.list(call.limit())
        return ToolExecutionResult(success = true, content = conversations.toConversationText("最近会话"))
    }

    private suspend fun searchConversations(call: ToolCall): ToolExecutionResult {
        val query = call.arguments["query"].orEmpty().trim()
        if (query.isBlank()) return ToolExecutionResult(success = false, content = "会话搜索关键词不能为空")
        val conversations = conversationStore.search(query = query, limit = call.limit())
        return ToolExecutionResult(success = true, content = conversations.toConversationText("匹配会话"))
    }

    private suspend fun listNotes(call: ToolCall): ToolExecutionResult {
        val notes = noteStore.list(call.limit())
        return ToolExecutionResult(success = true, content = notes.toNoteText("最近笔记"))
    }

    private suspend fun searchNotes(call: ToolCall): ToolExecutionResult {
        val query = call.arguments["query"].orEmpty().trim()
        if (query.isBlank()) return ToolExecutionResult(success = false, content = "笔记搜索关键词不能为空")
        val notes = noteStore.search(query = query, limit = call.limit())
        return ToolExecutionResult(success = true, content = notes.toNoteText("匹配笔记"))
    }

    private suspend fun createNote(call: ToolCall): ToolExecutionResult {
        val title = call.arguments["title"].orEmpty().trim()
        val content = call.arguments["content"].orEmpty().trim()
        if (title.isBlank() || content.isBlank()) {
            return ToolExecutionResult(success = false, content = "笔记标题和正文不能为空")
        }
        // long: notes.create 是第一批带本地写入副作用的工具，必须由 Runtime 先走审批；写入后立即回读，避免只凭 insert 成功就向用户宣称任务完成。
        val created = noteStore.create(title = title, content = content)
        val verified = noteStore.get(created.id)
            ?.takeIf { it.title == title && it.content == content }
        return if (verified == null) {
            ToolExecutionResult(
                success = false,
                verified = false,
                content = "笔记已写入但回读验证失败，不能确认创建成功：$title",
            )
        } else {
            ToolExecutionResult(
                success = true,
                verified = true,
                content = "已创建并验证笔记：${created.title}\n${created.content}",
            )
        }
    }

    private suspend fun remember(call: ToolCall): ToolExecutionResult {
        val note = call.arguments["note"].orEmpty().trim()
        if (note.isBlank()) return ToolExecutionResult(success = false, content = "长期记忆内容不能为空")
        val tags = call.arguments["tags"].orEmpty().trim()
        val type = call.arguments["type"].orEmpty().trim().ifBlank { "Episode" }
        val source = runContext?.let {
            AgentMemorySource(
                conversationId = it.conversationId,
                runId = it.runId,
                summary = "由 /agent 任务写入：${it.goal}",
            )
        } ?: AgentMemorySource(conversationId = null, runId = null, summary = "来源未知")
        // long: 长期记忆写入要保存来源和启用状态，后续用户才能追问“为什么记住这件事”，并在管理页禁用或删除。
        val record = memoryStore.remember(
            content = note,
            tags = tags,
            type = type,
            source = source,
            confidence = 0.8,
        )
        val tagText = record.tags.takeIf { it.isNotBlank() }?.let { " · 标签：$it" }.orEmpty()
        return ToolExecutionResult(
            success = true,
            content = "已保存长期记忆：${record.content} · 类型：${record.type}$tagText · 来源：${record.sourceSummary}",
        )
    }

    private suspend fun searchMemory(call: ToolCall): ToolExecutionResult {
        val memories = memoryStore.search(
            query = call.arguments["query"].orEmpty().trim(),
            limit = call.limit(),
            enabledOnly = true,
        )
        if (memories.isEmpty()) return ToolExecutionResult(success = true, content = "未找到匹配长期记忆。")
        return ToolExecutionResult(
            success = true,
            content = memories.joinToString(separator = "\n", prefix = "长期记忆：\n") { memory ->
                val tags = memory.tags.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                "- $tags${memory.content} · 类型：${memory.type} · 来源：${memory.sourceSummary}"
            },
        )
    }

    private fun ToolCall.limit(): Int {
        return arguments["limit"]
            ?.toIntOrNull()
            ?.let { min(max(it, 1), 10) }
            ?: 5
    }

    private fun List<AgentConversationRecord>.toConversationText(title: String): String {
        if (isEmpty()) return "$title：无"
        return joinToString(separator = "\n", prefix = "$title：\n") { conversation ->
            "- ${conversation.title} · ${conversation.messageCount} 条消息 · id=${conversation.id}"
        }
    }

    private fun List<AgentNoteRecord>.toNoteText(title: String): String {
        if (isEmpty()) return "$title：无"
        return joinToString(separator = "\n", prefix = "$title：\n") { note ->
            "- ${note.title} · id=${note.id}\n  ${note.content.take(120)}"
        }
    }
}

class SystemAgentClock(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AgentClock {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun formattedNow(): String = formatter.format(Instant.ofEpochMilli(nowMillis()))

    override fun zoneId(): String = zone.id
}
