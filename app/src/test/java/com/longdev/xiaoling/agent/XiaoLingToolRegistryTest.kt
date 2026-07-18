package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoLingToolRegistryTest {
    @Test
    fun registryExposesFirstInternalAgentTools() {
        val registry = testRegistry()

        val tools = registry.availableTools().associateBy { it.name }

        assertEquals(
            setOf(
                "app.current_time",
                "app.list_conversations",
                "app.search_conversations",
                "notes.list",
                "notes.search",
                "notes.create",
                "memory.search",
                "memory.remember",
            ),
            tools.keys,
        )
        assertEquals(ToolRisk.SAFE, tools.getValue("app.current_time").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.search_conversations").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("notes.create").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("memory.remember").risk)
        assertNotNull(tools.getValue("notes.create").inputSchema.singleOrNull { it.name == "title" && it.required })
        assertNotNull(tools.getValue("memory.remember").inputSchema.singleOrNull { it.name == "note" && it.required })
    }

    @Test
    fun productionToolsDeclareCompleteSchemaAndFailClosedPolicies() {
        val tools = testRegistry().availableTools().associateBy { it.name }
        val limitFields = listOf(
            "app.list_conversations",
            "app.search_conversations",
            "notes.list",
            "notes.search",
            "memory.search",
        ).map { name -> tools.getValue(name).inputSchema.single { it.name == "limit" } }

        assertTrue(tools.values.all { it.timeoutMs == 5_000L })
        assertTrue(tools.values.all { it.permissionPolicy.requiredAndroidPermissions.isEmpty() })
        assertTrue(tools.values.none { it.permissionPolicy.supportsBackground })
        assertTrue(limitFields.all {
            it.type == ToolInputType.INTEGER && it.minimum == 1.0 && it.maximum == 10.0
        })
        assertEquals(ToolApprovalPolicy.NONE, tools.getValue("notes.search").approvalPolicy)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, tools.getValue("notes.create").approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.create").verificationPolicy)
        assertEquals(
            setOf("Preference", "ProfileFact", "Episode", "Procedure"),
            tools.getValue("memory.remember").inputSchema.single { it.name == "type" }.enumValues,
        )

        val invalidTags = tools.getValue("memory.remember").validateArguments(
            mapOf(
                "note" to "用户喜欢紧凑界面",
                "tags" to (1..11).joinToString(",") { "tag$it" },
            ),
        )
        assertTrue(invalidTags.errors.contains("长期记忆标签不能超过 10 个"))
    }

    @Test
    fun currentTimeToolReturnsStableLocalTimeSnapshot() = runTest {
        val result = testRegistry().execute(ToolCall(name = "app.current_time", arguments = emptyMap(), risk = ToolRisk.SAFE))

        assertTrue(result.success)
        assertEquals("当前时间：2026-07-17 08:30:45 · 时区：Asia/Shanghai", result.content)
    }

    @Test
    fun conversationSearchFindsOldConversation() = runTest {
        val result = testRegistry().execute(
            ToolCall(
                name = "app.search_conversations",
                arguments = mapOf("query" to "表格"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("匹配会话"))
        assertTrue(result.content.contains("Markdown 渲染排查"))
        assertTrue(result.content.contains("conversation-markdown"))
    }

    @Test
    fun notesCreateWritesAndVerifiesByReadingBack() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)

        val result = registry.execute(
            ToolCall(
                name = "notes.create",
                arguments = mapOf("title" to "发布检查", "content" to "发布前确认 release 签名和 SHA-256。"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("已创建并验证笔记：发布检查"))
        assertEquals("发布检查", noteStore.records.single().title)
        assertEquals(true, result.verified)
    }

    @Test
    fun notesCreateMarksResultUnverifiedWhenReadBackFails() = runTest {
        val registry = testRegistry(
            noteStore = object : InMemoryAgentNoteStore() {
                override suspend fun get(id: String): AgentNoteRecord? = null
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "notes.create",
                arguments = mapOf("title" to "未验证笔记", "content" to "这条笔记回读失败。"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertEquals(false, result.success)
        assertEquals(false, result.verified)
        assertTrue(result.content.contains("回读验证失败"))
    }

    @Test
    fun memoryRememberPersistsSourceAndSearchOnlyReturnsEnabledMemory() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    runId = "run-1",
                    goal = "记住用户偏好",
                ),
            )
        }

        val remember = registry.execute(
            ToolCall(
                name = "memory.remember",
                arguments = mapOf(
                    "note" to "用户喜欢紧凑、明亮但不刺眼的 Android UI",
                    "type" to "Preference",
                    "tags" to "ui,preference",
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        memoryStore.records += AgentMemoryRecord(
            id = "disabled-memory",
            content = "不应该被检索的禁用记忆",
            tags = "hidden",
            type = "Episode",
            sourceConversationId = "conversation-old",
            sourceRunId = null,
            sourceSummary = "手工禁用",
            confidence = 0.8,
            enabled = false,
            createdAt = 1,
            updatedAt = 1,
        )
        val search = registry.execute(
            ToolCall(
                name = "memory.search",
                arguments = mapOf("query" to "Android UI"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(remember.success)
        assertEquals(true, remember.verified)
        assertTrue(remember.content.contains("来源：由 /agent Run 写入（来源 Run 可查看）"))
        assertFalse(remember.content.contains("记住用户偏好"))
        assertTrue(search.success)
        assertTrue(search.content.contains("用户喜欢紧凑、明亮但不刺眼的 Android UI"))
        assertTrue(search.content.contains("Preference"))
        assertTrue(!search.content.contains("不应该被检索"))
        assertEquals(listOf("memory-1"), search.memoryIdsUsed)
    }

    @Test
    fun disabledMemoryRecallHidesSearchToolAndDoesNotReadStore() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    runId = "run-1",
                    goal = "不使用记忆",
                    memoryRecallEnabled = false,
                ),
            )
        }

        assertTrue(registry.availableTools().none { it.name == "memory.search" })
        val result = registry.execute(
            ToolCall(name = "memory.search", arguments = mapOf("query" to "Android"), risk = ToolRisk.SAFE),
        )
        assertTrue(result.success)
        assertTrue(result.memoryIdsUsed.isEmpty())
        assertTrue(result.content.contains("关闭长期记忆召回"))
        assertTrue(memoryStore.searchQueries.isEmpty())
    }

    @Test
    fun memoryRememberMarksResultUnverifiedWhenReadBackFails() = runTest {
        val registry = testRegistry(
            memoryStore = object : InMemoryAgentMemoryStore() {
                override suspend fun get(memoryId: String): AgentMemoryRecord? = null
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "memory.remember",
                arguments = mapOf("note" to "需要回读确认的偏好"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertEquals(false, result.success)
        assertEquals(false, result.verified)
        assertTrue(result.content.contains("回读验证失败"))
    }

    private fun testRegistry(
        conversationStore: AgentConversationStore = InMemoryAgentConversationStore(),
        noteStore: InMemoryAgentNoteStore = InMemoryAgentNoteStore(),
        memoryStore: InMemoryAgentMemoryStore = InMemoryAgentMemoryStore(),
    ): XiaoLingToolRegistry {
        return XiaoLingToolRegistry(
            clock = FakeAgentClock(),
            conversationStore = conversationStore,
            noteStore = noteStore,
            memoryStore = memoryStore,
        )
    }
}

private class FakeAgentClock : AgentClock {
    override fun nowMillis(): Long = 1_784_252_245_000
    override fun formattedNow(): String = "2026-07-17 08:30:45"
    override fun zoneId(): String = "Asia/Shanghai"
}

private class InMemoryAgentConversationStore : AgentConversationStore {
    private val conversations = listOf(
        AgentConversationRecord(
            id = "conversation-markdown",
            title = "Markdown 渲染排查",
            summary = "处理表格、引用和图片渲染。",
            messageCount = 12,
            updatedAt = 10,
        ),
        AgentConversationRecord(
            id = "conversation-release",
            title = "Release 发布",
            summary = "构建正式签名 APK。",
            messageCount = 8,
            updatedAt = 9,
        ),
    )

    override suspend fun list(limit: Int): List<AgentConversationRecord> = conversations.take(limit)

    override suspend fun search(query: String, limit: Int): List<AgentConversationRecord> {
        return conversations
            .filter { it.title.contains(query, ignoreCase = true) || it.summary.contains(query, ignoreCase = true) }
            .take(limit)
    }
}

private open class InMemoryAgentNoteStore : AgentNoteStore {
    val records = mutableListOf<AgentNoteRecord>()

    override suspend fun list(limit: Int): List<AgentNoteRecord> = records.take(limit)

    override suspend fun search(query: String, limit: Int): List<AgentNoteRecord> {
        return records
            .filter { it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
            .take(limit)
    }

    override suspend fun create(title: String, content: String): AgentNoteRecord {
        return AgentNoteRecord(
            id = "note-${records.size + 1}",
            title = title,
            content = content,
            createdAt = 1_784_252_245_000 + records.size,
            updatedAt = 1_784_252_245_000 + records.size,
        ).also { records += it }
    }

    open override suspend fun get(id: String): AgentNoteRecord? = records.firstOrNull { it.id == id }
}

private open class InMemoryAgentMemoryStore : AgentMemoryStore {
    val records = mutableListOf<AgentMemoryRecord>()
    val searchQueries = mutableListOf<String>()

    override suspend fun remember(
        content: String,
        tags: String,
        type: String,
        source: AgentMemorySource,
        confidence: Double,
    ): AgentMemoryRecord {
        val record = AgentMemoryRecord(
            id = "memory-${records.size + 1}",
            content = content,
            tags = tags,
            type = type,
            sourceConversationId = source.conversationId,
            sourceRunId = source.runId,
            sourceSummary = source.summary,
            confidence = confidence,
            enabled = true,
            createdAt = 1_784_252_245_000 + records.size,
            updatedAt = 1_784_252_245_000 + records.size,
        )
        records += record
        return record
    }

    open override suspend fun get(memoryId: String): AgentMemoryRecord? = records.firstOrNull { it.id == memoryId }

    override suspend fun search(query: String, limit: Int, enabledOnly: Boolean): List<AgentMemoryRecord> {
        searchQueries += query
        val normalized = query.trim()
        return records
            .filter { !enabledOnly || it.enabled }
            .filter { record ->
                normalized.isBlank() ||
                    record.content.contains(normalized, ignoreCase = true) ||
                    record.tags.contains(normalized, ignoreCase = true) ||
                    record.sourceSummary.contains(normalized, ignoreCase = true)
            }
            .take(limit)
    }
}
