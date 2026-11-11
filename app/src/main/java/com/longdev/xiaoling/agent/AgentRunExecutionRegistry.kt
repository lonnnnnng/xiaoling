package com.longdev.xiaoling.agent

internal object AgentRunExecutionRegistry {
    private val executions = mutableMapOf<String, Int>()

    @Synchronized fun isRunning(runId: String): Boolean = runId in executions

    private fun acquire(runId: String) = synchronized(this) {
        executions[runId] = (executions[runId] ?: 0) + 1
    }

    private fun release(runId: String) = synchronized(this) {
        val remaining = executions.getValue(runId) - 1
        if (remaining == 0) executions.remove(runId) else executions[runId] = remaining
    }

    suspend fun <T> track(runId: String, block: suspend () -> T): T {
        // long: 只登记本进程正在执行的身份，不持久化 Job；停止活跃 Run 时保留 Runtime 最后一次预算结算，恢复记录则可直接关闭。
        acquire(runId)
        try { return block() } finally { release(runId) }
    }
}
