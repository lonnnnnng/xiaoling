package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode

enum class AgentRetryConfirmationKind {
    EVIDENCE_RETRY,
    RESTART_REQUIRED_RELAUNCH,
    NOT_COMMITTED_CONTROLLED_REPLAY,
}

data class AgentRetryConfirmationUiState(
    val runId: String,
    val goal: String,
    val evidenceCode: AgentTaskRetryEvidenceCode,
    val evidenceFingerprint: String,
    val kind: AgentRetryConfirmationKind = AgentRetryConfirmationKind.EVIDENCE_RETRY,
    val expectedRestartDispositionCode: AgentRunRestartDispositionCode? = null,
)

interface AgentTaskCenterActions {
    fun refreshAgentRunHistory()

    fun selectAgentRun(runId: String)

    fun requestAgentRunRetry(runId: String)

    fun confirmAgentRunRetry()

    fun cancelAgentRunRetry()
}

internal data class AgentTaskCenterUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val runs: List<AgentTaskCenterRunUiState> = emptyList(),
    val pendingRetryConfirmation: AgentRetryConfirmationUiState? = null,
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
        pendingRetryConfirmation: AgentRetryConfirmationUiState? = null,
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
            pendingRetryConfirmation = pendingRetryConfirmation,
        )
    }
}
