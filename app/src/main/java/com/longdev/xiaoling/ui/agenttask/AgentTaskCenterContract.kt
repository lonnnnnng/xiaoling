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
    val sourceRunNavigationId: String? = null,
    val linkedRetryRunNavigationId: String? = null,
    val parentRunNavigationId: String? = null,
    val childRunNavigationIds: List<String> = emptyList(),
    val lineageDepth: Int = 0,
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
        val historyByRunId = history.groupBy { it.snapshot.run.id }
        val retriesBySourceRunId = history
            .mapNotNull { detail ->
                val sourceRunId = detail.snapshot.run.retryOfRunId
                    ?.takeIf { it != detail.snapshot.run.id }
                    ?: return@mapNotNull null
                sourceRunId to detail
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val childrenByParentRunId = history
            .mapNotNull { detail ->
                val parentRunId = detail.snapshot.run.parentRunId
                    ?.takeIf { it != detail.snapshot.run.id }
                    ?: return@mapNotNull null
                parentRunId to detail.snapshot.run.id
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        fun lineageDepth(runId: String): Int {
            val visited = mutableSetOf<String>()
            var currentId = runId
            var depth = 0
            while (true) {
                if (!visited.add(currentId)) return 0
                val parentId = historyByRunId[currentId]
                    ?.singleOrNull()
                    ?.snapshot
                    ?.run
                    ?.parentRunId
                    ?.takeIf { it != currentId }
                    ?: return depth
                if (historyByRunId[parentId].isNullOrEmpty()) return depth
                depth += 1
                currentId = parentId
            }
        }
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
                    sourceRunNavigationId = detail.snapshot.run.retryOfRunId
                        ?.takeIf { sourceRunId ->
                            sourceRunId != runId && historyByRunId[sourceRunId]?.size == 1
                        },
                    linkedRetryRunNavigationId = latestUnambiguousRetryRunId(
                        retries = retriesBySourceRunId[runId].orEmpty(),
                    ),
                    parentRunNavigationId = detail.snapshot.run.parentRunId
                        ?.takeIf { parentId -> historyByRunId[parentId]?.size == 1 },
                    childRunNavigationIds = childrenByParentRunId[runId].orEmpty(),
                    lineageDepth = lineageDepth(runId),
                )
            },
            pendingRetryConfirmation = pendingRetryConfirmation,
        )
    }

    private fun latestUnambiguousRetryRunId(
        retries: List<AgentRunDetailRecord>,
    ): String? {
        if (retries.isEmpty() || retries.map { it.snapshot.run.id }.toSet().size != retries.size) {
            return null
        }
        val latestCreatedAt = retries.maxOf { it.snapshot.run.createdAt }
        val latestRetries = retries.filter { it.snapshot.run.createdAt == latestCreatedAt }
        // long: 一个来源 Run 可以多次创建关联 Run，但只有创建时间能唯一确定最新目标时才提供跳转，避免异常重复数据下猜测用户想看的记录。
        return latestRetries.singleOrNull()?.snapshot?.run?.id
    }
}
