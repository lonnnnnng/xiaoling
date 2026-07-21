package com.longdev.xiaoling.automation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class StartupRecoveryCandidateIds(
    val agentRunIds: Set<String>,
    val workflowRunIds: Set<String>,
    val scheduledTaskIds: Set<String>,
)

internal data class WorkflowStartupRecoveryCandidates(
    val activeWorkflowRunIds: Set<String>,
    val runningScheduledTaskIds: Set<String>,
    val currentProcessWorkflowRunIds: Set<String>,
    val currentProcessAgentRunIds: Set<String>,
)

internal class ScheduledWorkflowProcessExecutionRegistry {
    private val boundaryMutex = Mutex()
    private val activeTaskCounts = mutableMapOf<String, Int>()

    suspend fun <T> withScheduledTask(taskId: String, block: suspend () -> T): T {
        boundaryMutex.withLock {
            // long: WorkManager 极端情况下可能让同一任务出现重叠调用；使用引用计数，避免先结束的调用过早移除仍在执行的任务所有权。
            activeTaskCounts[taskId] = activeTaskCounts.getOrDefault(taskId, 0) + 1
        }
        return try {
            block()
        } finally {
            boundaryMutex.withLock {
                val remaining = activeTaskCounts.getOrDefault(taskId, 0) - 1
                if (remaining > 0) {
                    activeTaskCounts[taskId] = remaining
                } else {
                    activeTaskCounts.remove(taskId)
                }
            }
        }
    }

    suspend fun <T> captureRecoveryBoundary(loader: suspend (Set<String>) -> T): T {
        boundaryMutex.lock()
        return try {
            // long: 候选读取期间禁止新 Worker 注册；快照完成后才允许它访问 Room，保证新执行不会被误归入本次“旧进程遗留”集合。
            loader(activeTaskCounts.keys.toSet())
        } finally {
            boundaryMutex.unlock()
        }
    }

    companion object {
        val process = ScheduledWorkflowProcessExecutionRegistry()
    }
}

internal class StartupRecoveryCoordinator(
    private val processExecutionRegistry: ScheduledWorkflowProcessExecutionRegistry,
    private val loadAgentRunIds: suspend () -> Set<String>,
    private val loadWorkflowCandidates: suspend (Set<String>) -> WorkflowStartupRecoveryCandidates,
) {
    suspend fun capture(): StartupRecoveryCandidateIds {
        return processExecutionRegistry.captureRecoveryBoundary { currentProcessTaskIds ->
            val agentRunIds = loadAgentRunIds()
            val workflowCandidates = loadWorkflowCandidates(currentProcessTaskIds)
            StartupRecoveryCandidateIds(
                agentRunIds = agentRunIds - workflowCandidates.currentProcessAgentRunIds,
                workflowRunIds = workflowCandidates.activeWorkflowRunIds -
                    workflowCandidates.currentProcessWorkflowRunIds,
                scheduledTaskIds = workflowCandidates.runningScheduledTaskIds - currentProcessTaskIds,
            )
        }
    }
}
