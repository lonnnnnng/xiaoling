package com.longdev.xiaoling.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AgentRunRecoveryExecutionGateTest {
    @Test fun sameRunWaitsAndDifferentRunProgresses() = runBlocking {
        val id = UUID.randomUUID().toString()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEntered = false
        val first = async { AgentRunRecoveryExecutionGate.withRun(id) { entered.complete(Unit); release.await() } }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { AgentRunRecoveryExecutionGate.withRun(id) { secondEntered = true } }
        assertFalse(secondEntered)
        withTimeout(1_000) { AgentRunRecoveryExecutionGate.withRun("other-$id") { } }
        release.complete(Unit)
        first.await(); second.await()
        assertTrue(secondEntered)
    }

    @Test fun cancelledWaiterAndFailedOwnerReleaseOnlyTheirOwnReferences() = runBlocking {
        val id = UUID.randomUUID().toString()
        val release = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) { AgentRunRecoveryExecutionGate.withRun(id) { release.await() } }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { AgentRunRecoveryExecutionGate.withRun(id) { fail("已取消的等待者不能进入恢复") } }
        waiter.cancelAndJoin()
        val next = async(start = CoroutineStart.UNDISPATCHED) { AgentRunRecoveryExecutionGate.withRun(id) { "next" } }
        assertFalse(next.isCompleted)
        release.complete(Unit); owner.await()
        assertEquals("next", next.await())
        assertTrue(runCatching { AgentRunRecoveryExecutionGate.withRun(id) { error("fixture") } }.isFailure)
        assertEquals("retry", withTimeout(1_000) { AgentRunRecoveryExecutionGate.withRun(id) { "retry" } })
    }
}
