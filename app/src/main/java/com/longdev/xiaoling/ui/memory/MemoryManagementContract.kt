package com.longdev.xiaoling.ui.memory

import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
import com.longdev.xiaoling.agent.AgentMemoryExpiryOption
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryRecord

interface MemoryManagementActions {
    fun refreshMemories()

    fun updateMemoryCandidatesEnabled(enabled: Boolean)

    fun updateMemorySearchQuery(query: String)

    fun updateMemoryFilter(filter: AgentMemoryFilter)

    fun acceptMemoryCandidate(candidateId: String)

    fun rejectMemoryCandidate(candidateId: String)

    fun undoMemoryDelete()

    fun selectMemory(memoryId: String)

    fun setMemoryPinned(memoryId: String, pinned: Boolean)

    fun setMemoryEnabled(memoryId: String, enabled: Boolean)

    fun setMemoryExpiry(memoryId: String, option: AgentMemoryExpiryOption)

    fun openMemoryEdit(memoryId: String)

    fun requestMemoryDelete(memoryId: String)

    fun openMemorySourceConversation(memoryId: String)

    fun openMemorySourceRun(memoryId: String)
}

internal data class MemoryManagementUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val memories: List<MemoryManagementItemUiState> = emptyList(),
    val candidatesEnabled: Boolean = false,
    val loadingCandidates: Boolean = false,
    val candidates: List<MemoryManagementCandidateUiState> = emptyList(),
    val searchQuery: String = "",
    val filter: AgentMemoryFilter = AgentMemoryFilter.ALL,
    val deletedMemoryForUndo: AgentMemoryRecord? = null,
)

internal data class MemoryManagementItemUiState(
    val record: AgentMemoryRecord,
    val selected: Boolean,
    val mutating: Boolean,
)

internal data class MemoryManagementCandidateUiState(
    val record: AgentMemoryCandidateRecord,
    val mutating: Boolean,
    val statusLabel: String,
    val acceptLabel: String,
    val conflict: Boolean,
)

internal object MemoryManagementProjection {
    fun project(
        loading: Boolean,
        error: String?,
        memories: List<AgentMemoryRecord>,
        candidatesEnabled: Boolean,
        loadingCandidates: Boolean,
        candidates: List<AgentMemoryCandidateRecord>,
        searchQuery: String,
        filter: AgentMemoryFilter,
        selectedMemoryId: String?,
        mutatingMemoryIds: Set<String>,
        mutatingCandidateIds: Set<String>,
        deletedMemoryForUndo: AgentMemoryRecord?,
    ): MemoryManagementUiState {
        // long: 管理页只展示仍需用户决定的候选；已保存、已忽略和敏感阻断记录继续留在审计数据中，但不能重新出现可操作按钮。
        val actionableCandidates = candidates.filter { candidate ->
            candidate.status == AgentMemoryCandidateStatus.PENDING ||
                candidate.status == AgentMemoryCandidateStatus.CONFLICT
        }
        // long: 选中态和变更态在模块入口按稳定 ID 绑定，搜索或筛选导致列表重排时不会把按钮禁用状态投影到其他记忆。
        return MemoryManagementUiState(
            loading = loading,
            error = error,
            memories = memories.map { memory ->
                MemoryManagementItemUiState(
                    record = memory,
                    selected = memory.id == selectedMemoryId,
                    mutating = memory.id in mutatingMemoryIds,
                )
            },
            candidatesEnabled = candidatesEnabled,
            loadingCandidates = loadingCandidates,
            candidates = actionableCandidates.map { candidate ->
                val conflict = candidate.status == AgentMemoryCandidateStatus.CONFLICT
                MemoryManagementCandidateUiState(
                    record = candidate,
                    mutating = candidate.id in mutatingCandidateIds,
                    statusLabel = if (conflict) "与旧记忆冲突" else "待确认",
                    acceptLabel = if (conflict) "另存为新记忆" else "保存",
                    conflict = conflict,
                )
            },
            searchQuery = searchQuery,
            filter = filter,
            deletedMemoryForUndo = deletedMemoryForUndo,
        )
    }
}
