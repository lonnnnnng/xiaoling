package com.longdev.xiaoling.ui.localnotes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.xiaoling.agent.AgentNoteRecord
import com.longdev.xiaoling.agent.AgentNoteStore
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
    val error: String? = null,
)

internal interface LocalNoteManagementActions {
    fun refresh()
    fun updateSearchQuery(value: String)
    fun search()
    fun clearSearch()
    fun selectNote(noteId: String)
    fun closeDetail()
}

internal class LocalNoteManagementViewModel internal constructor(
    application: Application,
    private val store: AgentNoteStore,
) : AndroidViewModel(application), LocalNoteManagementActions {
    constructor(application: Application) : this(
        application = application,
        store = RoomAgentNoteStore(application),
    )

    var uiState by mutableStateOf(LocalNoteManagementUiState())
        private set

    private var listJob: Job? = null
    private var detailJob: Job? = null

    init {
        loadNotes(query = null)
    }

    override fun refresh() {
        val activeQuery = uiState.searchQuery.trim().takeIf { uiState.showingSearchResults && it.isNotBlank() }
        loadNotes(activeQuery)
    }

    override fun updateSearchQuery(value: String) {
        uiState = uiState.copy(searchQuery = value, error = null)
    }

    override fun search() {
        val query = uiState.searchQuery.trim()
        if (query.isBlank()) {
            clearSearch()
            return
        }
        loadNotes(query)
    }

    override fun clearSearch() {
        uiState = uiState.copy(searchQuery = "", error = null)
        loadNotes(query = null)
    }

    override fun selectNote(noteId: String) {
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
        detailJob?.cancel()
        uiState = uiState.copy(
            selectedNoteId = null,
            selectedNote = null,
            loadingDetail = false,
        )
    }

    private fun loadNotes(query: String?) {
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
                uiState = uiState.copy(
                    loading = false,
                    notes = emptyList(),
                    showingSearchResults = query != null,
                    error = error.message ?: "无法读取本地笔记",
                )
            }
        }
    }

    private companion object {
        const val NOTE_PAGE_LIMIT = 10
    }
}
