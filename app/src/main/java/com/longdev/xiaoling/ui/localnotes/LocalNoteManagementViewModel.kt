package com.longdev.xiaoling.ui.localnotes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.xiaoling.agent.AgentNoteManagementStore
import com.longdev.xiaoling.agent.AgentNoteRecord
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LocalNoteManagementUiState(
    val loading: Boolean = false,
    val notes: List<AgentNoteRecord> = emptyList(),
    val searchQuery: String = "",
    val showingSearchResults: Boolean = false,
    val selectedNoteId: String? = null,
    val selectedNote: AgentNoteRecord? = null,
    val loadingDetail: Boolean = false,
    val pendingDeleteNote: AgentNoteRecord? = null,
    val deleting: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

internal interface LocalNoteManagementActions {
    fun refresh()
    fun updateSearchQuery(value: String)
    fun search()
    fun clearSearch()
    fun selectNote(noteId: String)
    fun closeDetail()
    fun requestDelete(noteId: String)
    fun cancelDelete()
    fun confirmDelete()
}

internal class LocalNoteManagementViewModel internal constructor(
    application: Application,
    private val store: AgentNoteManagementStore,
) : AndroidViewModel(application), LocalNoteManagementActions {
    constructor(application: Application) : this(
        application = application,
        store = RoomAgentNoteStore(application),
    )

    var uiState by mutableStateOf(LocalNoteManagementUiState())
        private set

    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null

    init {
        loadNotes(query = null)
    }

    override fun refresh() {
        if (uiState.deleting) return
        val activeQuery = uiState.searchQuery.trim().takeIf { uiState.showingSearchResults && it.isNotBlank() }
        loadNotes(activeQuery)
    }

    override fun updateSearchQuery(value: String) {
        if (uiState.deleting) return
        uiState = uiState.copy(searchQuery = value, error = null)
    }

    override fun search() {
        if (uiState.deleting) return
        val query = uiState.searchQuery.trim()
        if (query.isBlank()) {
            clearSearch()
            return
        }
        loadNotes(query)
    }

    override fun clearSearch() {
        if (uiState.deleting) return
        uiState = uiState.copy(searchQuery = "", error = null)
        loadNotes(query = null)
    }

    override fun selectNote(noteId: String) {
        if (uiState.deleting) return
        if (uiState.selectedNoteId == noteId && uiState.selectedNote != null) return
        detailJob?.cancel()
        uiState = uiState.copy(
            selectedNoteId = noteId,
            selectedNote = null,
            loadingDetail = true,
            error = null,
        )
        detailJob = viewModelScope.launch {
            try {
                val note = withContext(Dispatchers.IO) { store.get(noteId) }
                if (uiState.selectedNoteId != noteId) return@launch
                uiState = uiState.copy(
                    selectedNote = note,
                    loadingDetail = false,
                    error = if (note == null) "笔记已不存在" else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (uiState.selectedNoteId != noteId) return@launch
                uiState = uiState.copy(
                    loadingDetail = false,
                    error = error.message ?: "无法读取笔记详情",
                )
            }
        }
    }

    override fun closeDetail() {
        if (uiState.deleting) return
        detailJob?.cancel()
        uiState = uiState.copy(
            selectedNoteId = null,
            selectedNote = null,
            loadingDetail = false,
            pendingDeleteNote = null,
            error = null,
        )
    }

    override fun requestDelete(noteId: String) {
        if (uiState.deleting) return
        val note = uiState.selectedNote?.takeIf { it.id == noteId } ?: return
        uiState = uiState.copy(pendingDeleteNote = note, error = null)
    }

    override fun cancelDelete() {
        if (uiState.deleting) return
        uiState = uiState.copy(pendingDeleteNote = null, error = null)
    }

    override fun confirmDelete() {
        if (mutationJob?.isActive == true) return
        val note = uiState.pendingDeleteNote ?: return
        listJob?.cancel()
        detailJob?.cancel()
        uiState = uiState.copy(deleting = true, error = null, notice = null)
        mutationJob = viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { store.delete(note.id) }
                val remainingNotes = uiState.notes.filterNot { it.id == note.id }
                if (!deleted) {
                    uiState = uiState.copy(
                        notes = remainingNotes,
                        selectedNoteId = null,
                        selectedNote = null,
                        loadingDetail = false,
                        pendingDeleteNote = null,
                        deleting = false,
                        error = "笔记已不存在",
                    )
                    return@launch
                }
                val activeQuery = uiState.searchQuery.trim()
                    .takeIf { uiState.showingSearchResults && it.isNotBlank() }
                uiState = uiState.copy(
                    notes = remainingNotes,
                    selectedNoteId = null,
                    selectedNote = null,
                    loadingDetail = false,
                    pendingDeleteNote = null,
                    deleting = false,
                    notice = "已删除笔记：${note.title}",
                    error = null,
                )
                loadNotes(query = activeQuery, refreshFailurePrefix = "笔记已删除")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    deleting = false,
                    error = error.message ?: "删除本地笔记失败",
                )
            }
        }
    }

    private fun loadNotes(
        query: String?,
        refreshFailurePrefix: String? = null,
    ) {
        listJob?.cancel()
        uiState = uiState.copy(loading = true, error = null)
        listJob = viewModelScope.launch {
            try {
                // long: 管理页复用 Agent 工具的既有 10 条读取边界，避免只读入口悄悄扩大数据暴露面；后续分页需单独设计。
                val notes = withContext(Dispatchers.IO) {
                    if (query == null) store.list(limit = NOTE_PAGE_LIMIT) else store.search(query, limit = NOTE_PAGE_LIMIT)
                }
                uiState = uiState.copy(
                    loading = false,
                    notes = notes,
                    showingSearchResults = query != null,
                    error = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "无法读取本地笔记"
                // long: 删除已提交后只能移除目标笔记；刷新失败不应让其余本地笔记也从当前页面消失。
                val visibleNotes = if (refreshFailurePrefix == null) emptyList() else uiState.notes
                uiState = uiState.copy(
                    loading = false,
                    notes = visibleNotes,
                    showingSearchResults = query != null,
                    error = refreshFailurePrefix?.let { "$it，但列表刷新失败：$message" } ?: message,
                )
            }
        }
    }

    private companion object {
        const val NOTE_PAGE_LIMIT = 10
    }
}
