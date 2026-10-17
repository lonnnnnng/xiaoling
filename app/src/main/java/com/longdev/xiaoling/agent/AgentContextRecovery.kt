package com.longdev.xiaoling.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** long: 缺少历史快照的旧 Run 沿用原资格；损坏的已捕获上下文不能伪装成旧版本。 */
enum class AgentContextRecoveryStatus { LEGACY_UNCAPTURED, VALIDATED, INVALID }

internal object AgentRunRecoveryExecutionGate {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withRun(runId: String, action: suspend () -> T): T {
        require(runId.isNotBlank())
        val entry = synchronized(entries) {
            entries.getOrPut(runId) { Entry() }.also { it.users++ }
        }
        try {
            // long: 不同 ViewModel/UseCase 可同时发现同一 marker；按 Run 串行执行并在取锁后回读，marker 幂等不能代替执行互斥。
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                entry.users--
                // long: 等锁时取消也必须归还引用，避免长期使用后把所有历史 Run 的锁留在进程中。
                if (entry.users == 0) entries.remove(runId)
            }
        }
    }
}
