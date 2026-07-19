package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeChunkRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.knowledge.KnowledgeSearchResult
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
                "knowledge.search",
            ),
            tools.keys,
        )
        assertEquals(ToolRisk.SAFE, tools.getValue("app.current_time").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.search_conversations").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("notes.create").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("memory.remember").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("knowledge.search").risk)
        assertNotNull(tools.getValue("notes.create").inputSchema.singleOrNull { it.name == "title" && it.required })
        assertNotNull(tools.getValue("memory.remember").inputSchema.singleOrNull { it.name == "note" && it.required })
        assertNotNull(tools.getValue("knowledge.search").inputSchema.singleOrNull { it.name == "query" && it.required })
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
        val backgroundTools = tools.values
            .filter { it.permissionPolicy.supportsBackground }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "app.current_time",
                "app.list_conversations",
                "app.search_conversations",
                "notes.list",
                "notes.search",
                "memory.search",
                "knowledge.search",
            ),
            backgroundTools,
        )
        assertTrue(tools.values.filter { it.risk != ToolRisk.SAFE }.none { it.permissionPolicy.supportsBackground })
        assertTrue(limitFields.all {
            it.type == ToolInputType.INTEGER && it.minimum == 1.0 && it.maximum == 10.0
        })
        assertEquals(
            5.0,
            tools.getValue("knowledge.search").inputSchema.single { it.name == "limit" }.maximum,
        )
        assertEquals(ToolApprovalPolicy.NONE, tools.getValue("notes.search").approvalPolicy)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, tools.getValue("notes.create").approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.create").verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("notes.create").replaySafety)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("memory.remember").replaySafety)
        assertTrue(testRegistry().supportsCommittedEffectVerification("notes.create"))
        assertTrue(testRegistry().supportsCommittedEffectVerification("memory.remember"))
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
    fun knowledgeSearchReturnsStableReferencesBoundToCurrentRun() = runTest {
        val knowledgeStore = InMemoryKnowledgeDocumentStore()
        val registry = testRegistry(knowledgeStore = knowledgeStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-knowledge",
                    userMessageId = "message-knowledge",
                    runId = "run-knowledge",
                    goal = "从知识库查找发布门禁",
                ),
            )
        }

        val result = registry.execute(
            ToolCall(
                name = "knowledge.search",
                arguments = mapOf("query" to "发布门禁", "limit" to "2"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("发布检查清单.md"))
        assertTrue(result.content.contains("必须在 Redmi 真机完成验收"))
        assertEquals("发布门禁", knowledgeStore.lastQuery)
        assertEquals(2, knowledgeStore.lastLimit)
        assertEquals("conversation-knowledge", knowledgeStore.lastConversationId)
        assertEquals("run-knowledge", knowledgeStore.lastRunId)
        assertEquals(1, result.knowledgeReferences.size)
        val reference = result.knowledgeReferences.single()
        assertEquals("knowledge-retrieval-1", reference.retrievalId)
        assertEquals("document-release", reference.documentId)
        assertEquals("发布检查清单.md", reference.documentName)
        assertEquals(3, reference.documentRevision)
        assertEquals("chunk-release-r3-0", reference.chunkId)
        assertEquals(0, reference.chunkSequence)
        assertEquals(12, reference.startOffset)
        assertEquals(38, reference.endOffset)
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
                id = "tool-call-note-1",
                name = "notes.create",
                arguments = mapOf("title" to "发布检查", "content" to "发布前确认 release 签名和 SHA-256。"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("已创建并验证笔记：发布检查"))
        assertEquals("发布检查", noteStore.records.single().title)
        assertEquals(true, result.verified)
        assertEquals(
            ToolExecutionReceipt(
                toolCallId = "tool-call-note-1",
                operationId = "note-1",
                idempotencyKey = "tool-call-note-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
            result.executionReceipt,
        )
    }

    @Test
    fun notesCreateRepeatedToolCallReturnsSameOperationId() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-idempotent",
            name = "notes.create",
            arguments = mapOf("title" to "幂等笔记", "content" to "同一个 ToolCall 只能创建一次。"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val first = registry.execute(call)
        val replay = registry.execute(call)

        assertEquals(1, noteStore.records.size)
        assertEquals(first.executionReceipt?.operationId, replay.executionReceipt?.operationId)
        assertEquals(call.id, first.executionReceipt?.idempotencyKey)
        assertEquals(call.id, replay.executionReceipt?.idempotencyKey)
    }

    @Test
    fun notesCreateRejectsPayloadDriftForExistingToolCall() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)
        val original = ToolCall(
            id = "tool-call-note-conflict",
            name = "notes.create",
            arguments = mapOf("title" to "原始标题", "content" to "原始正文"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        registry.execute(original)

        val error = runCatching {
            registry.execute(original.copy(arguments = mapOf("title" to "被篡改标题", "content" to "原始正文")))
        }.exceptionOrNull()

        assertTrue(error is AgentNoteIdempotencyConflictException)
        assertEquals(1, noteStore.records.size)
        assertEquals("原始标题", noteStore.records.single().title)
        assertEquals("原始正文", noteStore.records.single().content)
    }

    @Test
    fun notesCreateCommittedEffectVerificationReadsOriginalOperationWithoutCreatingAgain() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-readback-recovery",
            name = "notes.create",
            arguments = mapOf("title" to "恢复回读", "content" to "只读验证原 operation"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val created = registry.execute(call)
        val receipt = requireNotNull(created.executionReceipt)

        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertEquals(1, noteStore.records.size)
        assertEquals(created, recovered)
        assertEquals(receipt.operationId, recovered?.executionReceipt?.operationId)
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
                id = "tool-call-memory-1",
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
        assertEquals(
            ToolExecutionReceipt(
                toolCallId = "tool-call-memory-1",
                operationId = "memory-1",
                idempotencyKey = "tool-call-memory-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
            remember.executionReceipt,
        )
        assertTrue(search.success)
        assertTrue(search.content.contains("用户喜欢紧凑、明亮但不刺眼的 Android UI"))
        assertTrue(search.content.contains("Preference"))
        assertTrue(!search.content.contains("不应该被检索"))
        assertEquals(listOf("memory-1"), search.memoryIdsUsed)
    }

    @Test
    fun memoryRememberReusesSameToolCallAndRejectsPayloadDrift() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore)
        val call = ToolCall(
            id = "tool-call-memory-idempotent",
            name = "memory.remember",
            arguments = mapOf("note" to "用户喜欢紧凑界面", "type" to "Preference"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val first = registry.execute(call)
        val replay = registry.execute(call)
        val conflict = runCatching {
            registry.execute(call.copy(arguments = call.arguments + ("note" to "用户喜欢宽松界面")))
        }.exceptionOrNull()

        assertEquals(first.executionReceipt?.operationId, replay.executionReceipt?.operationId)
        assertEquals(call.id, replay.executionReceipt?.idempotencyKey)
        assertEquals(1, memoryStore.records.size)
        assertTrue(conflict is AgentMemoryIdempotencyConflictException)
    }

    @Test
    fun memoryRememberCommittedEffectVerificationReadsOriginalOperationWithoutRememberingAgain() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-memory-recovery",
                    userMessageId = "message-memory-recovery",
                    runId = "run-memory-recovery",
                    goal = "恢复长期记忆验证",
                ),
            )
        }
        val call = ToolCall(
            id = "tool-call-memory-recovery",
            name = "memory.remember",
            arguments = mapOf(
                "note" to "用户喜欢紧凑界面",
                "type" to "Preference",
                "tags" to "ui,preference",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val created = registry.execute(call)
        val receipt = requireNotNull(created.executionReceipt)

        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertEquals(1, memoryStore.rememberCallCount)
        assertEquals(1, memoryStore.verificationCallCount)
        assertEquals(created, recovered)
        assertEquals(receipt, recovered?.executionReceipt)
    }

    @Test
    fun memoryRememberRecoveryFailuresExposeStableSuggestedActions() = runTest {
        val expectedSuggestions = mapOf(
            AgentMemoryOperationVerificationFailure.OPERATION_NOT_FOUND to "重新保存",
            AgentMemoryOperationVerificationFailure.EVIDENCE_INCOMPLETE to "历史版本",
            AgentMemoryOperationVerificationFailure.PAYLOAD_MISMATCH to "重新确认",
            AgentMemoryOperationVerificationFailure.OPERATION_MISMATCH to "重新确认",
            AgentMemoryOperationVerificationFailure.MEMORY_NOT_FOUND to "重新保存",
            AgentMemoryOperationVerificationFailure.MEMORY_CHANGED to "当前编辑结果",
            AgentMemoryOperationVerificationFailure.MEMORY_DISABLED to "启用该记忆",
            AgentMemoryOperationVerificationFailure.MEMORY_EXPIRED to "更新过期时间",
        )
        val call = ToolCall(
            id = "tool-call-memory-failure",
            name = "memory.remember",
            arguments = mapOf("note" to "用户喜欢紧凑界面"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "memory-failure",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )

        expectedSuggestions.forEach { (reason, expectedSuggestion) ->
            val registry = testRegistry(
                memoryStore = object : InMemoryAgentMemoryStore() {
                    override suspend fun verifyRememberedOperation(
                        idempotencyKey: String,
                        memoryId: String,
                        request: AgentMemoryWriteRequest,
                        nowMillis: Long,
                    ): AgentMemoryOperationVerification = AgentMemoryOperationVerification.Failed(reason)
                },
            )

            val result = requireNotNull(registry.verifyCommittedEffect(call, receipt))

            assertEquals(reason.name, result.recoveryFailure?.code)
            assertTrue(result.recoveryFailure?.reason.orEmpty().isNotBlank())
            assertTrue(result.recoveryFailure?.suggestedAction.orEmpty().contains(expectedSuggestion))
            assertTrue(result.recoveryFailure?.suggestedAction.orEmpty().contains("新 Run"))
        }
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
        knowledgeStore: KnowledgeDocumentStore = InMemoryKnowledgeDocumentStore(),
    ): XiaoLingToolRegistry {
        return XiaoLingToolRegistry(
            clock = FakeAgentClock(),
            conversationStore = conversationStore,
            noteStore = noteStore,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore,
        )
    }
}

private class InMemoryKnowledgeDocumentStore : KnowledgeDocumentStore {
    var lastQuery: String? = null
    var lastLimit: Int? = null
    var lastConversationId: String? = null
    var lastRunId: String? = null

    override suspend fun search(
        query: String,
        limit: Int,
        sourceConversationId: String?,
        sourceRunId: String?,
    ): KnowledgeSearchResult {
        lastQuery = query
        lastLimit = limit
        lastConversationId = sourceConversationId
        lastRunId = sourceRunId
        return KnowledgeSearchResult(
            hits = listOf(
                KnowledgeSearchHit(
                    chunkId = "chunk-release-r3-0",
                    documentId = "document-release",
                    documentRevision = 3,
                    documentName = "发布检查清单.md",
                    sequence = 0,
                    startOffset = 12,
                    endOffset = 38,
                    text = "必须在 Redmi 真机完成验收。",
                ),
            ).take(limit),
            retrieval = KnowledgeRetrievalRecord(
                id = "knowledge-retrieval-1",
                query = query,
                chunkIds = listOf("chunk-release-r3-0"),
                documentIds = listOf("document-release"),
                sourceConversationId = sourceConversationId,
                sourceRunId = sourceRunId,
                createdAt = 1_784_252_245_000,
            ),
        )
    }

    override suspend fun importUtf8Document(displayName: String, mimeType: String, bytes: ByteArray): KnowledgeDocumentRecord =
        error("测试不支持导入")

    override suspend fun replaceUtf8Document(
        documentId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord = error("测试不支持替换")

    override suspend fun getDocument(documentId: String): KnowledgeDocumentRecord? = null
    override suspend fun listDocuments(): List<KnowledgeDocumentSummary> = emptyList()
    override suspend fun getDocumentDetail(documentId: String): KnowledgeDocumentDetail? = null
    override suspend fun getChunks(documentId: String): List<KnowledgeChunkRecord> = emptyList()
    override suspend fun retainCurrentReferences(references: List<KnowledgeReference>): List<KnowledgeReference> = references
    override suspend fun recentRetrievals(limit: Int): List<KnowledgeRetrievalRecord> = emptyList()
    override suspend fun setEnabled(documentId: String, enabled: Boolean): KnowledgeDocumentRecord? = null
    override suspend fun delete(documentId: String): Boolean = false
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
    private val recordsByIdempotencyKey = mutableMapOf<String, AgentNoteRecord>()

    override suspend fun list(limit: Int): List<AgentNoteRecord> = records.take(limit)

    override suspend fun search(query: String, limit: Int): List<AgentNoteRecord> {
        return records
            .filter { it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
            .take(limit)
    }

    override suspend fun create(title: String, content: String, idempotencyKey: String): AgentNoteRecord {
        recordsByIdempotencyKey[idempotencyKey]?.let { existing ->
            if (existing.title != title || existing.content != content) {
                throw AgentNoteIdempotencyConflictException()
            }
            return existing
        }
        return AgentNoteRecord(
            id = "note-${records.size + 1}",
            title = title,
            content = content,
            createdAt = 1_784_252_245_000 + records.size,
            updatedAt = 1_784_252_245_000 + records.size,
        ).also {
            records += it
            recordsByIdempotencyKey[idempotencyKey] = it
        }
    }

    open override suspend fun get(id: String): AgentNoteRecord? = records.firstOrNull { it.id == id }
}

private open class InMemoryAgentMemoryStore : AgentMemoryStore {
    val records = mutableListOf<AgentMemoryRecord>()
    val searchQueries = mutableListOf<String>()
    var rememberCallCount = 0
    var verificationCallCount = 0
    private val recordsByIdempotencyKey = mutableMapOf<String, AgentMemoryRecord>()
    private val requestsByIdempotencyKey = mutableMapOf<String, AgentMemoryWriteRequest>()

    override suspend fun remember(
        content: String,
        tags: String,
        type: String,
        source: AgentMemorySource,
        confidence: Double,
        idempotencyKey: String?,
    ): AgentMemoryRecord {
        rememberCallCount += 1
        idempotencyKey?.let { key ->
            recordsByIdempotencyKey[key]?.let { existing ->
                if (
                    existing.content != content || existing.tags != tags || existing.type != type ||
                    existing.sourceConversationId != source.conversationId || existing.sourceRunId != source.runId ||
                    existing.sourceSummary != source.summary || existing.confidence != confidence
                ) {
                    throw AgentMemoryIdempotencyConflictException()
                }
                return existing
            }
        }
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
        idempotencyKey?.let {
            recordsByIdempotencyKey[it] = record
            requestsByIdempotencyKey[it] = AgentMemoryWriteRequest(content, tags, type, source, confidence)
        }
        return record
    }

    override suspend fun verifyRememberedOperation(
        idempotencyKey: String,
        memoryId: String,
        request: AgentMemoryWriteRequest,
        nowMillis: Long,
    ): AgentMemoryOperationVerification {
        verificationCallCount += 1
        val originalRequest = requestsByIdempotencyKey[idempotencyKey]
            ?: return AgentMemoryOperationVerification.Failed(AgentMemoryOperationVerificationFailure.OPERATION_NOT_FOUND)
        if (originalRequest != request) {
            return AgentMemoryOperationVerification.Failed(AgentMemoryOperationVerificationFailure.PAYLOAD_MISMATCH)
        }
        val memory = recordsByIdempotencyKey[idempotencyKey]
            ?.takeIf { it.id == memoryId }
            ?: return AgentMemoryOperationVerification.Failed(AgentMemoryOperationVerificationFailure.OPERATION_MISMATCH)
        return AgentMemoryOperationVerification.Verified(memory)
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
