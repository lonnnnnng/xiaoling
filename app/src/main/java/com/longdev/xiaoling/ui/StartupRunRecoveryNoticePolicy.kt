package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunStatus

internal suspend fun settleStartupInterruptedRuns(
    candidateRunIds: Set<String>,
    closeInterruptedRuns: suspend (Set<String>) -> Int,
    loadRun: suspend (String) -> AgentRunRecord?,
): OperationResult? {
    if (candidateRunIds.isEmpty()) return null
    val settledCount = closeInterruptedRuns(candidateRunIds)
    if (settledCount <= 0) return null
    // long: close 返回的数量只证明发生了收敛；提示分类必须在事务完成后回读 Run 终态，不能根据启动前中间态猜测失败或取消。
    val settledRuns = candidateRunIds.sorted().mapNotNull { runId -> loadRun(runId) }
    return projectStartupRunRecoveryNotice(settledRuns)
}

internal fun projectStartupRunRecoveryNotice(
    runs: List<AgentRunRecord>,
): OperationResult? {
    val failedCount = runs.count { run -> run.status == AgentRunStatus.FAILED }
    val cancelledCount = runs.count { run -> run.status == AgentRunStatus.CANCELLED }
    if (failedCount == 0 && cancelledCount == 0) return null
    val settlementSummary = buildList {
        if (failedCount > 0) add("失败 $failedCount 个")
        if (cancelledCount > 0) add("取消 $cancelledCount 个")
    }.joinToString(separator = "，")
    // long: 启动提示只汇总持久化终态数量；目标、原始错误和内部身份继续留在任务中心审计，避免锁屏或首屏通知泄露用户内容。
    return OperationResult(
        success = false,
        title = "已处理上次中断",
        message = "上次中断的 Agent 任务已安全收敛：$settlementSummary。不会重放工具，可在 Agent 任务中心查看详情。",
        action = OperationResultAction.OPEN_INTERRUPTED_AGENT_RUN_HISTORY,
    )
}
