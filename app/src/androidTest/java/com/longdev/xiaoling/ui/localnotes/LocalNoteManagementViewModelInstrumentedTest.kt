package com.longdev.xiaoling.ui.localnotes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.AgentNoteManagementStore
import com.longdev.xiaoling.agent.AgentNoteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LocalNoteManagementViewModelInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun recentSearchDetailAndClearReuseTheBoundedNoteStore() {
        val store = FakeAgentNoteStore()
        val viewModel = onMain {
            LocalNoteManagementViewModel(application = application, store = store)
        }

        val initial = awaitState(viewModel) {
            !it.loading && it.notes.map(AgentNoteRecord::id) == listOf(NOTE_A, NOTE_C)
        }
        assertFalse(initial.showingSearchResults)
        assertEquals(listOf(10), store.listLimits)

        onMain {
            viewModel.updateSearchQuery("  第二条  ")
            viewModel.search()
        }
        val search = awaitState(viewModel) {
            !it.loading && it.showingSearchResults && it.notes.map(AgentNoteRecord::id) == listOf(NOTE_B)
        }
        assertEquals("  第二条  ", search.searchQuery)
        assertEquals(listOf("第二条" to 10), store.searchCalls)

        onMain { viewModel.selectNote(NOTE_B) }
        val detail = awaitState(viewModel) { !it.loadingDetail && it.selectedNote?.id == NOTE_B }
        assertEquals("完整正文 B", detail.selectedNote?.content)
        assertEquals(listOf(NOTE_B), store.getCalls)

        onMain { viewModel.closeDetail() }
        assertEquals(null, onMain { viewModel.uiState.selectedNoteId })

        onMain { viewModel.clearSearch() }
        val cleared = awaitState(viewModel) { !it.loading && !it.showingSearchResults && it.searchQuery.isEmpty() }
        assertEquals(listOf(NOTE_A, NOTE_C), cleared.notes.map(AgentNoteRecord::id))
        assertEquals(listOf(10, 10), store.listLimits)
    }

    @Test
    fun deleteRequiresConfirmationAndKeepsCommittedResultWhenReloadFails() {
        val store = FakeAgentNoteStore()
        val viewModel = onMain {
            LocalNoteManagementViewModel(application = application, store = store)
        }
        awaitState(viewModel) { !it.loading && it.notes.any { note -> note.id == NOTE_A } }

        onMain { viewModel.selectNote(NOTE_A) }
        awaitState(viewModel) { it.selectedNote?.id == NOTE_A }
        onMain { viewModel.requestDelete(NOTE_A) }
        val pending = onMain { viewModel.uiState }
        assertEquals(NOTE_A, pending.pendingDeleteNote?.id)
        assertTrue(store.deleteCalls.isEmpty())

        store.failNextList = true
        onMain { viewModel.confirmDelete() }
        val deleted = awaitState(viewModel) {
            !it.deleting && it.notice == "已删除笔记：第一条" && it.error?.contains("列表刷新失败") == true
        }

        assertEquals(listOf(NOTE_A), store.deleteCalls)
        assertEquals(listOf(NOTE_C), deleted.notes.map(AgentNoteRecord::id))
        assertEquals(null, deleted.selectedNoteId)
        assertEquals(null, deleted.pendingDeleteNote)
    }

    private fun awaitState(
        viewModel: LocalNoteManagementViewModel,
        timeoutMillis: Long = 5_000,
        predicate: (LocalNoteManagementUiState) -> Boolean,
    ): LocalNoteManagementUiState {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val state = onMain { viewModel.uiState }
            if (predicate(state)) return state
            Thread.sleep(20)
        }
        error("等待本地笔记状态超时：${onMain { viewModel.uiState }}")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private class FakeAgentNoteStore : AgentNoteManagementStore {
        val listLimits = mutableListOf<Int>()
        val searchCalls = mutableListOf<Pair<String, Int>>()
        val getCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val deletedIds = mutableSetOf<String>()
        var failNextList = false

        override suspend fun list(limit: Int): List<AgentNoteRecord> {
            listLimits += limit
            if (failNextList) {
                failNextList = false
                error("模拟删除提交后的列表刷新失败")
            }
            return listOf(
                note(NOTE_A, "第一条", "完整正文 A"),
                note(NOTE_C, "保留条目", "刷新失败后仍应展示"),
            ).filterNot { it.id in deletedIds }
        }

        override suspend fun search(query: String, limit: Int): List<AgentNoteRecord> {
            searchCalls += query to limit
            return listOf(note(NOTE_B, "第二条", "完整正文 B")).filterNot { it.id in deletedIds }
        }

        override suspend fun create(title: String, content: String, idempotencyKey: String): AgentNoteRecord {
            error("只读管理页不应创建笔记")
        }

        override suspend fun get(id: String): AgentNoteRecord? {
            getCalls += id
            return when (id) {
                NOTE_A -> note(NOTE_A, "第一条", "完整正文 A")
                NOTE_B -> note(NOTE_B, "第二条", "完整正文 B")
                else -> null
            }?.takeUnless { it.id in deletedIds }
        }

        override suspend fun delete(id: String): Boolean {
            deleteCalls += id
            val exists = get(id) != null
            if (exists) deletedIds += id
            return exists
        }
    }

    private companion object {
        const val NOTE_A = "note-a"
        const val NOTE_B = "note-b"
        const val NOTE_C = "note-c"

        fun note(id: String, title: String, content: String) = AgentNoteRecord(
            id = id,
            title = title,
            content = content,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_100_000L,
        )
    }
}
