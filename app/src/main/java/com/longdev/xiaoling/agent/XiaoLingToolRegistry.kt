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
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "app.list_conversations",
            description = "列出最近的本地会话标题、消息数和更新时间，用于帮助用户回到历史对话。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "app.search_conversations",
            description = "按关键词搜索本地会话标题、摘要和消息内容，用于查找旧会话。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "搜索关键词。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.list",
            description = "列出最近创建的本地笔记。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.search",
            description = "按关键词搜索本地笔记标题和正文。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "搜索关键词。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
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
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "content",
                    description = "笔记正文。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 20_000,
                ),
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "memory.search",
            description = "检索本机长期记忆，帮助回答用户偏好、历史事实或长期备注。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "检索关键词；为空时返回最近记忆。",
                    required = false,
                    type = ToolInputType.STRING,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
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
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 2_000,
                ),
                ToolInputField(
                    name = "type",
                    description = "记忆类型：Preference、ProfileFact、Episode 或 Procedure。",
                    required = false,
                    type = ToolInputType.STRING,
                    enumValues = setOf("Preference", "ProfileFact", "Episode", "Procedure"),
                ),
                ToolInputField(
                    name = "tags",
                    description = "可选标签，多个标签用逗号分隔。",
                    required = false,
                    type = ToolInputType.STRING,
                    maxLength = 500,
                ),
            ),
            businessValidators = listOf(
                ToolBusinessValidator { arguments ->
                    val tags = arguments["tags"].orEmpty()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    when {
                        tags.size > 10 -> listOf("长期记忆标签不能超过 10 个")
                        tags.any { it.length > 50 } -> listOf("长期记忆单个标签不能超过 50 个字符")
                        else -> emptyList()
                    }
                },
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 5_000,
        ),
    )

    init {
        ToolRegistryContract.requireValid(tools)
    }

    override fun bindRunContext(context: AgentToolExecutionContext) {
        runContext = context
    }

    override fun availableTools(): List<ToolDefinition> {
        val context = runContext
        if (context?.memoryRecallEnabled == false) {
            // long: 关闭单次记忆召回时从规划器工具清单移除 memory.search，避免模型先提出调用再由执行器拒绝造成误导性审计。
            return tools.filterNot { it.name == "memory.search" }
        }
        return tools
    }

    fun registeredTools(): List<ToolDefinition> = tools

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
        // long: noteStore 已返回真实业务 ID，说明写入动作已经发生；回执不填幂等键，因为当前存储层还不能保证同一 ToolCall 重放只写入一次。
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = created.id,
            idempotencyKey = null,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val verified = noteStore.get(created.id)
            ?.takeIf { it.title == title && it.content == content }
        return if (verified == null) {
            ToolExecutionResult(
                success = false,
                verified = false,
                content = "笔记已写入但回读验证失败，不能确认创建成功：$title",
                executionReceipt = receipt,
            )
        } else {
            ToolExecutionResult(
                success = true,
                verified = true,
                content = "已创建并验证笔记：${created.title}\n${created.content}",
                executionReceipt = receipt,
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
                summary = "由 /agent Run 写入（来源 Run 可查看）",
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
        // long: 记忆记录 ID 是已发生写入的持久化证据；当前没有按 ToolCall 去重约束，因此只记录 operation ID，不宣称可以幂等重放。
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = record.id,
            idempotencyKey = null,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val verified = memoryStore.get(record.id)?.takeIf { stored ->
            stored.content == record.content &&
                stored.tags == record.tags &&
                stored.type == record.type &&
                stored.sourceConversationId == record.sourceConversationId &&
                stored.sourceRunId == record.sourceRunId &&
                stored.enabled
        }
        val tagText = record.tags.takeIf { it.isNotBlank() }?.let { " · 标签：$it" }.orEmpty()
        return if (verified == null) {
            ToolExecutionResult(
                success = false,
                verified = false,
                content = "长期记忆已写入但回读验证失败，不能确认保存成功：${record.content}",
                executionReceipt = receipt,
            )
        } else {
            ToolExecutionResult(
                success = true,
                verified = true,
                content = "已保存并验证长期记忆：${record.content} · 类型：${record.type}$tagText · 来源：${record.sourceSummary}",
                executionReceipt = receipt,
            )
        }
    }

    private suspend fun searchMemory(call: ToolCall): ToolExecutionResult {
        if (runContext?.memoryRecallEnabled == false) {
            return ToolExecutionResult(success = true, content = "本次 Run 已关闭长期记忆召回。")
        }
        val memories = memoryStore.search(
            query = call.arguments["query"].orEmpty().trim(),
            limit = call.limit(),
            enabledOnly = true,
        )
        if (memories.isEmpty()) return ToolExecutionResult(success = true, content = "未找到匹配长期记忆。")
        return ToolExecutionResult(
            success = true,
            memoryIdsUsed = memories.map { it.id },
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
