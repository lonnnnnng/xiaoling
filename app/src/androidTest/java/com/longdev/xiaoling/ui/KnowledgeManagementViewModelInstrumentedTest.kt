package com.longdev.xiaoling.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.knowledge.KnowledgeChunkRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.knowledge.KnowledgeSearchResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KnowledgeManagementViewModelInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun staleDetailCannotOverwriteLatestDocumentSelection() {
        val store = ControlledKnowledgeStore()
        val viewModel = createViewModel(store)
        awaitState(viewModel) { it.selectedDocument?.id == DOCUMENT_A }

        store.blockNextDocumentBRead()
        onMain { viewModel.selectDocument(DOCUMENT_B) }
        assertTrue(store.awaitBlockedDocumentBRead())
        onMain { viewModel.selectDocument(DOCUMENT_A) }
        awaitState(viewModel) { it.selectedDocumentId == DOCUMENT_A && it.selectedDocument?.id == DOCUMENT_A }

        store.completeBlockedDocumentBRead()
        Thread.sleep(200)
        val finalState = onMain { viewModel.uiState }
        assertEquals(DOCUMENT_A, finalState.selectedDocumentId)
        assertEquals(DOCUMENT_A, finalState.selectedDocument?.id)
    }

    @Test
    fun staleRefreshCannotOverwriteSelectionMadeWhileSnapshotLoads() {
        val store = ControlledKnowledgeStore()
        val viewModel = createViewModel(store)
        awaitState(viewModel) { it.selectedDocument?.id == DOCUMENT_A }

        store.blockNextListDocuments()
        onMain { viewModel.refresh(DOCUMENT_A) }
        assertTrue(store.awaitBlockedListDocuments())
        onMain { viewModel.selectDocument(DOCUMENT_B) }
        awaitState(viewModel) { it.selectedDocumentId == DOCUMENT_B && it.selectedDocument?.id == DOCUMENT_B }

        store.completeBlockedListDocuments()
        Thread.sleep(200)
        val finalState = onMain { viewModel.uiState }
        assertEquals(DOCUMENT_B, finalState.selectedDocumentId)
        assertEquals(DOCUMENT_B, finalState.selectedDocument?.id)
    }

    @Test
    fun refreshSelectsPreferredDocumentForCitationNavigation() {
        val store = ControlledKnowledgeStore()
        val viewModel = createViewModel(store)
        awaitState(viewModel) { it.selectedDocument?.id == DOCUMENT_A }

        onMain { viewModel.refresh(DOCUMENT_B) }
        val state = awaitState(viewModel) {
            it.selectedDocumentId == DOCUMENT_B && it.selectedDocument?.id == DOCUMENT_B
        }

        assertEquals(DOCUMENT_B, state.selectedDocumentId)
        assertEquals(DOCUMENT_B, state.selectedDocument?.id)
    }

    @Test
    fun disablingDocumentInvalidatesSearchAlreadyInFlight() {
        val store = ControlledKnowledgeStore()
        val viewModel = createViewModel(store)
        awaitState(viewModel) { it.selectedDocument?.id == DOCUMENT_A }

        store.blockNextSearch()
        onMain {
            viewModel.updateSearchQuery("旧引用")
            viewModel.search()
        }
        assertTrue(store.awaitBlockedSearch())
        onMain { viewModel.setEnabled(DOCUMENT_A, false) }
        awaitState(viewModel) { state ->
            state.documents.firstOrNull { it.id == DOCUMENT_A }?.enabled == false &&
                state.selectedDocument?.enabled == false
        }

        store.completeBlockedSearch()
        Thread.sleep(200)
        val finalState = onMain { viewModel.uiState }
        assertTrue(finalState.searchHits.isEmpty())
        assertNull(finalState.lastRetrieval)
        assertEquals(false, finalState.searching)
    }

    @Test
    fun committedMutationIsNotReportedAsFailedWhenSnapshotReloadFails() {
        val store = ControlledKnowledgeStore()
        val viewModel = createViewModel(store)
        awaitState(viewModel) { it.selectedDocument?.id == DOCUMENT_A }

        store.blockNextListDocuments()
        onMain { viewModel.setEnabled(DOCUMENT_A, false) }
        assertTrue(store.awaitBlockedListDocuments())
        val reloadingState = onMain { viewModel.uiState }
        assertTrue(DOCUMENT_A in reloadingState.mutatingDocumentIds)
        assertNull(reloadingState.selectedDocument)
        store.failBlockedListDocuments()
        val state = awaitState(viewModel) { it.error?.contains("操作已完成，但刷新知识库失败") == true }

        assertEquals(true, state.notice?.success)
        assertEquals("文档已停用", state.notice?.title)
        assertTrue(state.mutatingDocumentIds.isEmpty())
    }

    private fun createViewModel(store: KnowledgeDocumentStore): KnowledgeManagementViewModel {
        return onMain {
            KnowledgeManagementViewModel(
                application = application,
                store = store,
                readImport = { error("本测试不会读取 SAF 文档") },
            )
        }
    }

    private fun awaitState(
        viewModel: KnowledgeManagementViewModel,
        timeoutMillis: Long = 5_000,
        predicate: (KnowledgeManagementUiState) -> Boolean,
    ): KnowledgeManagementUiState {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val state = onMain { viewModel.uiState }
            if (predicate(state)) return state
            Thread.sleep(20)
        }
        error("等待知识库状态超时：${onMain { viewModel.uiState }}")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private class ControlledKnowledgeStore : KnowledgeDocumentStore {
        private val lock = Any()
        private var documentBRead: CompletableDeferred<KnowledgeDocumentDetail?>? = null
        private var documentBReadStarted = CountDownLatch(0)
        private var pendingSearch: CompletableDeferred<KnowledgeSearchResult>? = null
        private var searchStarted = CountDownLatch(0)
        private var pendingList: CompletableDeferred<List<KnowledgeDocumentSummary>>? = null
        private var listStarted = CountDownLatch(0)
        private var summaries = listOf(summary(DOCUMENT_A, true), summary(DOCUMENT_B, true))
        private var details = mapOf(
            DOCUMENT_A to detail(DOCUMENT_A, true),
            DOCUMENT_B to detail(DOCUMENT_B, true),
        )

        fun blockNextDocumentBRead() = synchronized(lock) {
            documentBRead = CompletableDeferred()
            documentBReadStarted = CountDownLatch(1)
        }

        fun awaitBlockedDocumentBRead(): Boolean = documentBReadStarted.await(3, TimeUnit.SECONDS)

        fun completeBlockedDocumentBRead() = synchronized(lock) {
            documentBRead?.complete(details[DOCUMENT_B])
        }

        fun blockNextSearch() = synchronized(lock) {
            pendingSearch = CompletableDeferred()
            searchStarted = CountDownLatch(1)
        }

        fun awaitBlockedSearch(): Boolean = searchStarted.await(3, TimeUnit.SECONDS)

        fun completeBlockedSearch() = synchronized(lock) {
            val hit = KnowledgeSearchHit(
                chunkId = "stale-chunk",
                documentId = DOCUMENT_A,
                documentRevision = 1,
                documentName = "a.md",
                sequence = 0,
                startOffset = 0,
                endOffset = 4,
                text = "旧引用",
            )
            pendingSearch?.complete(
                KnowledgeSearchResult(
                    hits = listOf(hit),
                    retrieval = KnowledgeRetrievalRecord(
                        id = "stale-retrieval",
                        query = "旧引用",
                        chunkIds = listOf(hit.chunkId),
                        documentIds = listOf(hit.documentId),
                        sourceConversationId = null,
                        sourceRunId = null,
                        createdAt = 1L,
                    ),
                ),
            )
        }

        fun blockNextListDocuments() = synchronized(lock) {
            pendingList = CompletableDeferred()
            listStarted = CountDownLatch(1)
        }

        fun awaitBlockedListDocuments(): Boolean = listStarted.await(3, TimeUnit.SECONDS)

        fun completeBlockedListDocuments() = synchronized(lock) {
            pendingList?.complete(summaries)
        }

        fun failBlockedListDocuments() = synchronized(lock) {
            pendingList?.completeExceptionally(IllegalStateException("forced snapshot reload failure"))
        }

        override suspend fun getDocumentDetail(documentId: String): KnowledgeDocumentDetail? {
            val blockedRead = synchronized(lock) {
                if (documentId == DOCUMENT_B) documentBRead else null
            }
            if (blockedRead != null) {
                documentBReadStarted.countDown()
                return blockedRead.await()
            }
            return synchronized(lock) { details[documentId] }
        }

        override suspend fun listDocuments(): List<KnowledgeDocumentSummary> {
            val blockedList = synchronized(lock) { pendingList }
            if (blockedList != null) {
                listStarted.countDown()
                return blockedList.await()
            }
            return synchronized(lock) { summaries }
        }

        override suspend fun search(
            query: String,
            limit: Int,
            sourceConversationId: String?,
            sourceRunId: String?,
        ): KnowledgeSearchResult {
            val blockedSearch = synchronized(lock) { pendingSearch }
            checkNotNull(blockedSearch)
            searchStarted.countDown()
            return blockedSearch.await()
        }

        override suspend fun setEnabled(documentId: String, enabled: Boolean): KnowledgeDocumentRecord? {
            synchronized(lock) {
                summaries = summaries.map { if (it.id == documentId) it.copy(enabled = enabled) else it }
                details = details.mapValues { (id, value) -> if (id == documentId) value.copy(enabled = enabled) else value }
            }
            return record(documentId, enabled)
        }

        override suspend fun importUtf8Document(
            displayName: String,
            mimeType: String,
            bytes: ByteArray,
        ): KnowledgeDocumentRecord = error("未使用")

        override suspend fun replaceUtf8Document(
            documentId: String,
            displayName: String,
            mimeType: String,
            bytes: ByteArray,
        ): KnowledgeDocumentRecord = error("未使用")

        override suspend fun getDocument(documentId: String): KnowledgeDocumentRecord? =
            synchronized(lock) { details[documentId]?.let { record(it.id, it.enabled) } }

        override suspend fun getChunks(documentId: String): List<KnowledgeChunkRecord> = emptyList()

        override suspend fun retainCurrentReferences(references: List<KnowledgeReference>): List<KnowledgeReference> = references

        override suspend fun recentRetrievals(limit: Int): List<KnowledgeRetrievalRecord> = emptyList()

        override suspend fun delete(documentId: String): Boolean = error("未使用")
    }

    companion object {
        private const val DOCUMENT_A = "document-a"
        private const val DOCUMENT_B = "document-b"

        private fun summary(id: String, enabled: Boolean) = KnowledgeDocumentSummary(
            id = id,
            displayName = if (id == DOCUMENT_A) "a.md" else "b.md",
            mimeType = "text/markdown",
            contentHash = "hash-$id",
            revision = 1,
            parserVersion = 1,
            byteSize = 4,
            characterCount = 4,
            enabled = enabled,
            createdAt = 1L,
            updatedAt = 1L,
            chunkCount = 1,
        )

        private fun detail(id: String, enabled: Boolean) = KnowledgeDocumentDetail(
            id = id,
            displayName = if (id == DOCUMENT_A) "a.md" else "b.md",
            mimeType = "text/markdown",
            contentHash = "hash-$id",
            revision = 1,
            parserVersion = 1,
            byteSize = 4,
            characterCount = 4,
            previewText = "正文",
            previewTruncated = false,
            enabled = enabled,
            createdAt = 1L,
            updatedAt = 1L,
        )

        private fun record(id: String, enabled: Boolean) = KnowledgeDocumentRecord(
            id = id,
            displayName = if (id == DOCUMENT_A) "a.md" else "b.md",
            mimeType = "text/markdown",
            contentHash = "hash-$id",
            revision = 1,
            parserVersion = 1,
            byteSize = 4,
            characterCount = 4,
            normalizedText = "正文",
            enabled = enabled,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
}
