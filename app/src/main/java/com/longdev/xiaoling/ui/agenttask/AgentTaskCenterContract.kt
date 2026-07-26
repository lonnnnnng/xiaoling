package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.agent.AgentRunDetailRecord

interface AgentTaskCenterActions {
    fun refreshAgentRunHistory()

    fun selectAgentRun(runId: String)

    fun requestAgentRunRetry(runId: String)
}

internal data class AgentTaskCenterUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val runs: List<AgentTaskCenterRunUiState> = emptyList(),
)

internal data class AgentTaskCenterRunUiState(
    val detail: AgentRunDetailRecord,
    val selected: Boolean,
    val retrying: Boolean,
)

internal object AgentTaskCenterProjection {
    fun project(
        loading: Boolean,
        error: String?,
        history: List<AgentRunDetailRecord>,
        selectedRunId: String?,
        retryingRunId: String?,
    ): AgentTaskCenterUiState {
        // long: 选中态与重试态只在模块入口按稳定 Run ID 绑定，页面筛选后不会因为列表位置变化而把操作状态投影到其他任务。
        return AgentTaskCenterUiState(
            loading = loading,
            error = error,
            runs = history.map { detail ->
                val runId = detail.snapshot.run.id
                AgentTaskCenterRunUiState(
                    detail = detail,
                    selected = runId == selectedRunId,
                    retrying = runId == retryingRunId,
                )
            },
        )
    }
}
