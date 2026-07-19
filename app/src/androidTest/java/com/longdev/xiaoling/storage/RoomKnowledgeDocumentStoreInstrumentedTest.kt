package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.agent.AgentToolExecutionContext
import com.longdev.xiaoling.agent.SystemAgentClock
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.XiaoLingToolRegistry
import com.longdev.xiaoling.knowledge.KnowledgeReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomKnowledgeDocumentStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: XiaoLingDatabase
    private lateinit var store: RoomKnowledgeDocumentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE_NAME)
        database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, TEST_DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        store = RoomKnowledgeDocumentStore(context, database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun importedDocumentCanBeRetrievedAndAuditedAfterStoreRecreation() = runBlocking {
        val document = store.importUtf8Document(
            displayName = "产品手册.md",
            mimeType = "text/markdown",
            bytes = buildString {
                append("# 本地知识库\n\n")
                repeat(400) { append("小灵使用确定性分块和本地索引。\n\n") }
            }.toByteArray(Charsets.UTF_8),
        )

        assertEquals(1, document.revision)
        assertEquals(1, document.parserVersion)
        assertTrue(store.getChunks(document.id).size > 1)
        val summaries = store.listDocuments()
        assertEquals(listOf(document.id), summaries.map { it.id })
        assertEquals(document.displayName, summaries.single().displayName)
        assertTrue(summaries.single().chunkCount > 1)
        val detail = store.getDocumentDetail(document.id)
        assertEquals(document.characterCount, detail?.characterCount)
        assertEquals(document.normalizedText.take(4_000), detail?.previewText)
        assertTrue(detail?.previewTruncated == true)

        val firstSearch = store.search(
            query = "本地 索引",
            limit = 5,
            sourceConversationId = "conversation-rag",
            sourceRunId = "run-rag",
        )
        assertTrue(firstSearch.hits.isNotEmpty())
        assertTrue(firstSearch.hits.all { it.documentId == document.id })
        assertTrue(firstSearch.hits.all { it.documentRevision == 1 })
        assertTrue(firstSearch.hits.all { hit ->
            document.normalizedText.substring(hit.startOffset, hit.endOffset) == hit.text
        })
        assertEquals(firstSearch.hits.map { it.chunkId }, firstSearch.retrieval.chunkIds)
        assertEquals(listOf(document.id), firstSearch.retrieval.documentIds)
        assertEquals("conversation-rag", firstSearch.retrieval.sourceConversationId)
        assertEquals("run-rag", firstSearch.retrieval.sourceRunId)

        database.close()
        database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, TEST_DATABASE_NAME)
            .allowMainThreadQueries()
            .addMigrations(*XiaoLingDatabase.migrations())
            .build()
        val recreated = RoomKnowledgeDocumentStore(context, database)
        assertEquals(document, recreated.getDocument(document.id))
        assertTrue(recreated.search("确定性分块", 3).hits.isNotEmpty())
        assertTrue(recreated.recentRetrievals(10).any { it.id == firstSearch.retrieval.id })
    }

    @Test
    fun detailPreviewUsesUtf16BudgetWithoutSplittingSurrogatePair() = runBlocking {
        val normalizedText = buildString {
            repeat(3_999) { append('a') }
            append("😀")
            append("tail")
        }
        val document = store.importUtf8Document(
            displayName = "emoji.txt",
            mimeType = "text/plain",
            bytes = normalizedText.toByteArray(Charsets.UTF_8),
        )

        val detail = store.getDocumentDetail(document.id)

        assertEquals(3_999, detail?.previewText?.length)
        assertEquals("a".repeat(3_999), detail?.previewText)
        assertTrue(detail?.previewTruncated == true)
    }

    @Test
    fun replacementCreatesNewRevisionAndInvalidatesEveryOldChunkReference() = runBlocking {
        val original = store.importUtf8Document(
            displayName = "规则.txt",
            mimeType = "text/plain",
            bytes = "旧规则只允许蓝色主题。".toByteArray(Charsets.UTF_8),
        )
        val oldChunkIds = store.getChunks(original.id).map { it.id }

        val replaced = store.replaceUtf8Document(
            documentId = original.id,
            displayName = "规则-v2.txt",
            mimeType = "text/plain",
            bytes = "新规则允许绿色主题，并废止旧规则。".toByteArray(Charsets.UTF_8),
        )
        val newChunks = store.getChunks(original.id)

        assertEquals(2, replaced.revision)
        assertEquals("规则-v2.txt", replaced.displayName)
        assertTrue(oldChunkIds.toSet().intersect(newChunks.map { it.id }.toSet()).isEmpty())
        assertTrue(newChunks.all { it.documentRevision == 2 })
        assertTrue(store.search("绿色主题", 5).hits.isNotEmpty())
        assertTrue(store.search("蓝色主题", 5).hits.isEmpty())
        assertTrue(oldChunkIds.all { database.knowledgeDao().getChunk(it) == null })
    }

    @Test
    fun failedReplacementRollsBackDocumentChunksAndSearchIndexTogether() {
        val original = runBlocking {
            store.importUtf8Document(
                displayName = "atomic.txt",
                mimeType = "text/plain",
                bytes = "事务前仍可检索的旧正文。".toByteArray(Charsets.UTF_8),
            )
        }
        val oldChunks = runBlocking { store.getChunks(original.id) }
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_knowledge_revision_two
            BEFORE INSERT ON knowledge_chunks
            WHEN NEW.documentRevision = 2
            BEGIN
                SELECT RAISE(ABORT, 'forced replacement failure');
            END
            """.trimIndent(),
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                store.replaceUtf8Document(
                    documentId = original.id,
                    displayName = "atomic-v2.txt",
                    mimeType = "text/plain",
                    bytes = "不应留下的全新正文。".toByteArray(Charsets.UTF_8),
                )
            }
        }

        runBlocking {
            assertEquals(original, store.getDocument(original.id))
            assertEquals(oldChunks, store.getChunks(original.id))
            assertTrue(store.search("旧正文", 5).hits.isNotEmpty())
            assertTrue(store.search("全新正文", 5).hits.isEmpty())
        }
    }

    @Test
    fun disabledAndDeletedDocumentsLeaveSearchImmediately() = runBlocking {
        val document = store.importUtf8Document(
            displayName = "literal.txt",
            mimeType = "text/plain",
            bytes = "覆盖率是 100%_verified，中文子串可以检索。".toByteArray(Charsets.UTF_8),
        )
        assertTrue(store.search("100%_", 5).hits.isNotEmpty())
        assertTrue(store.search("中文子串", 5).hits.isNotEmpty())

        assertEquals(false, store.setEnabled(document.id, false)?.enabled)
        val disabledSearch = store.search("中文子串", 5)
        assertTrue(disabledSearch.hits.isEmpty())
        assertTrue(disabledSearch.retrieval.chunkIds.isEmpty())

        assertEquals(true, store.setEnabled(document.id, true)?.enabled)
        assertTrue(store.search("中文子串", 5).hits.isNotEmpty())
        assertTrue(store.delete(document.id))
        assertNull(store.getDocument(document.id))
        assertTrue(store.getChunks(document.id).isEmpty())
        assertTrue(store.search("中文子串", 5).hits.isEmpty())
        assertFalse(store.delete(document.id))
    }

    @Test
    fun knowledgeSearchToolNeverReusesDisabledReplacedOrDeletedReferencesInNewRuns() = runBlocking {
        val original = store.importUtf8Document(
            displayName = "Agent 规则.md",
            mimeType = "text/markdown",
            bytes = "旧版规则要求使用蓝色主题。".toByteArray(Charsets.UTF_8),
        )
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context, database),
            noteStore = RoomAgentNoteStore(context, database),
            memoryStore = RoomAgentMemoryStore(context, database),
            knowledgeStore = store,
        )

        registry.bindRunContext(context("run-knowledge-original"))
        val first = registry.execute(knowledgeCall("蓝色主题"))
        val firstReference = first.knowledgeReferences.single()
        assertEquals(original.id, firstReference.documentId)
        assertEquals(1, firstReference.documentRevision)

        store.setEnabled(original.id, false)
        registry.bindRunContext(context("run-knowledge-disabled"))
        assertTrue(registry.execute(knowledgeCall("蓝色主题")).knowledgeReferences.isEmpty())

        store.setEnabled(original.id, true)
        val replaced = store.replaceUtf8Document(
            documentId = original.id,
            displayName = "Agent 规则-v2.md",
            mimeType = "text/markdown",
            bytes = "新版规则要求使用绿色主题。".toByteArray(Charsets.UTF_8),
        )
        registry.bindRunContext(context("run-knowledge-replaced"))
        assertTrue(registry.execute(knowledgeCall("蓝色主题")).knowledgeReferences.isEmpty())
        val currentReference = registry.execute(knowledgeCall("绿色主题")).knowledgeReferences.single()
        assertEquals(2, replaced.revision)
        assertEquals(2, currentReference.documentRevision)
        assertTrue(currentReference.chunkId != firstReference.chunkId)

        store.delete(original.id)
        registry.bindRunContext(context("run-knowledge-deleted"))
        assertTrue(registry.execute(knowledgeCall("绿色主题")).knowledgeReferences.isEmpty())
    }

    @Test
    fun currentReferenceProjectionRejectsDisabledReplacedDeletedOrTamperedChunks() = runBlocking {
        val original = store.importUtf8Document(
            displayName = "引用生命周期.md",
            mimeType = "text/markdown",
            bytes = "发布前必须只在 Redmi 真机完成验收。".toByteArray(Charsets.UTF_8),
        )
        val hit = store.search("Redmi 真机", 3).hits.single()
        val reference = hit.toReference("retrieval-current")

        assertEquals(listOf(reference), store.retainCurrentReferences(listOf(reference)))
        assertTrue(store.retainCurrentReferences(listOf(reference.copy(endOffset = reference.endOffset - 1))).isEmpty())

        store.setEnabled(original.id, false)
        assertTrue(store.retainCurrentReferences(listOf(reference)).isEmpty())

        store.setEnabled(original.id, true)
        store.replaceUtf8Document(
            documentId = original.id,
            displayName = "引用生命周期-v2.md",
            mimeType = "text/markdown",
            bytes = "新版本要求发布前检查 crash buffer。".toByteArray(Charsets.UTF_8),
        )
        assertTrue(store.retainCurrentReferences(listOf(reference)).isEmpty())

        val currentHit = store.search("crash buffer", 3).hits.single()
        val currentReference = currentHit.toReference("retrieval-v2")
        assertEquals(listOf(currentReference), store.retainCurrentReferences(listOf(currentReference)))

        store.delete(original.id)
        assertTrue(store.retainCurrentReferences(listOf(currentReference)).isEmpty())
    }

    @Test
    fun projectDocumentationCorpusMeetsGoldenQueryRecallGate() = runBlocking {
        val documentNames = listOf(
            "requirements.md",
            "implementation-notes.md",
            "verification-report.md",
            "personal-agent-roadmap.md",
            "reference-apps-analysis.md",
        )
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        documentNames.forEach { name ->
            val bytes = testAssets.open(name).use { it.readBytes() }
            store.importUtf8Document(name, "text/markdown", bytes)
        }

        val goldenQueries = listOf(
            "当前启用 工具 模型 权限" to "requirements.md",
            "附件 BLOB 轻量 快照 回写" to "implementation-notes.md",
            "历史引用 保留 临时文档 删除" to "verification-report.md",
            "并行调用 通用原地断点恢复" to "personal-agent-roadmap.md",
            "最小状态机 WAITING_APPROVAL BLOCKED" to "reference-apps-analysis.md",
        )
        goldenQueries.forEach { (query, expectedDocument) ->
            val result = store.search(query, limit = 5)
            assertTrue("query=$query hits=${result.hits.map { it.documentName }}", result.hits.any {
                it.documentName == expectedDocument
            })
            assertTrue(result.hits.size <= 5)
        }

        val firstRanked = store.search("模型 自行声明权限 任意命令", limit = 1)
        assertEquals("requirements.md", firstRanked.hits.single().documentName)
        assertTrue(store.search("NONEXISTENT_STAGE31_NEGATIVE_9A8B7C", limit = 5).hits.isEmpty())
    }

    private fun com.longdev.xiaoling.knowledge.KnowledgeSearchHit.toReference(retrievalId: String) = KnowledgeReference(
        retrievalId = retrievalId,
        documentId = documentId,
        documentName = documentName,
        documentRevision = documentRevision,
        chunkId = chunkId,
        chunkSequence = sequence,
        startOffset = startOffset,
        endOffset = endOffset,
    )

    private fun context(runId: String) = AgentToolExecutionContext(
        conversationId = "conversation-$runId",
        userMessageId = "message-$runId",
        runId = runId,
        goal = "验证知识引用失效",
    )

    private fun knowledgeCall(query: String) = ToolCall(
        name = "knowledge.search",
        arguments = mapOf("query" to query, "limit" to "3"),
        risk = ToolRisk.SAFE,
    )

    companion object {
        private const val TEST_DATABASE_NAME = "xiaoling-knowledge-store-test.db"
    }
}
