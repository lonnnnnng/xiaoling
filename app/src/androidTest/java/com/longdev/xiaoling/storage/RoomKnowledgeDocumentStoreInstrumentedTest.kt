package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.data.XiaoLingDatabase
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
                repeat(180) { append("小灵使用确定性分块和本地索引。\n\n") }
            }.toByteArray(Charsets.UTF_8),
        )

        assertEquals(1, document.revision)
        assertEquals(1, document.parserVersion)
        assertTrue(store.getChunks(document.id).size > 1)

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

    companion object {
        private const val TEST_DATABASE_NAME = "xiaoling-knowledge-store-test.db"
    }
}
