package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.latestRecoveryMetadata

internal suspend fun settleStartupInterruptedRuns(
    candidateRunIds: Set<String>,
    closeInterruptedRuns: suspend (Set<String>) -> Int,
    loadRun: suspend (String) -> AgentRunDetailRecord?,
): OperationResult? {
    if (candidateRunIds.isEmpty()) return null
    val settledCount = closeInterruptedRuns(candidateRunIds)
    if (settledCount <= 0) return null
    // long: close 返回的数量只证明发生了收敛；提示分类必须在事务完成后回读 Run 终态，不能根据启动前中间态猜测失败或取消。
    val settledRuns = candidateRunIds.sorted().mapNotNull { runId -> loadRun(runId) }
    return projectStartupRunRecoveryNotice(settledRuns)
}

internal fun projectStartupRunRecoveryNotice(
    runs: List<AgentRunDetailRecord>,
): OperationResult? {
    val failedCount = runs.count { run -> run.snapshot.run.status == AgentRunStatus.FAILED }
    val cancelledCount = runs.count { run -> run.snapshot.run.status == AgentRunStatus.CANCELLED }
    if (failedCount == 0 && cancelledCount == 0) return null
    val restartRequiredCount = runs.count { detail ->
        detail.latestRecoveryMetadata()?.restartDisposition != null
    }
    val settlementSummary = buildList {
        if (failedCount > 0) add("失败 $failedCount 个")
        if (cancelledCount > 0) add("取消 $cancelledCount 个")
    }.joinToString(separator = "，")
    // long: 启动提示只汇总持久化终态数量；目标、原始错误和内部身份继续留在任务中心审计，避免锁屏或首屏通知泄露用户内容。
    val restartBoundary = if (restartRequiredCount > 0) {
        "其中 $restartRequiredCount 个 Run 无法原地恢复；如需继续，请在任务中心确认后创建关联新 Run，旧 Run 不会重放。"
    } else {
        "不会重放工具，可在 Agent 任务中心查看详情。"
    }
    return OperationResult(
        success = false,
        title = "已处理上次中断",
        message = "上次中断的 Agent 任务已安全收敛：$settlementSummary。$restartBoundary",
        action = OperationResultAction.OPEN_INTERRUPTED_AGENT_RUN_HISTORY,
    )
}
