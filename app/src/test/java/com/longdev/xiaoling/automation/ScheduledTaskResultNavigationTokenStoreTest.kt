package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledTaskResultNavigationTokenStoreTest {
    @Test
    fun validTokenCanBeConsumedOnlyOnce() {
        val backend = InMemoryNavigationTokenBackend()
        val store = ScheduledTaskResultNavigationTokenStore(
            backend = backend,
            tokenGenerator = { TOKEN_A },
            clock = { 1_000L },
        )
        val target = navigationTarget()

        assertEquals(TOKEN_A, store.issue(target))
        assertEquals(target, store.consume(TOKEN_A))
        assertNull(store.consume(TOKEN_A))
    }

    @Test
    fun expiredTokenIsRejected() {
        val backend = InMemoryNavigationTokenBackend()
        var now = 2_000L
        val store = ScheduledTaskResultNavigationTokenStore(
            backend = backend,
            tokenGenerator = { TOKEN_A },
            clock = { now },
            timeToLiveMillis = 500L,
        )

        assertEquals(TOKEN_A, store.issue(navigationTarget()))
        now = 2_500L

        assertNull(store.consume(TOKEN_A))
    }

    @Test
    fun forgedTokenCannotConsumeOrReplaceValidNavigation() {
        val backend = InMemoryNavigationTokenBackend()
        val store = ScheduledTaskResultNavigationTokenStore(
            backend = backend,
            tokenGenerator = { TOKEN_A },
            clock = { 3_000L },
        )
        val target = navigationTarget()
        store.issue(target)

        assertNull(store.consume(TOKEN_B))
        assertNull(store.consume("你".repeat(43)))
        assertEquals(target, store.consume(TOKEN_A))
    }

    @Test
    fun newerNotificationForSameTaskRevokesOlderToken() {
        val backend = InMemoryNavigationTokenBackend()
        val tokens = ArrayDeque(listOf(TOKEN_A, TOKEN_B))
        val store = ScheduledTaskResultNavigationTokenStore(
            backend = backend,
            tokenGenerator = { tokens.removeFirst() },
            clock = { 4_000L },
        )
        val target = navigationTarget()

        assertEquals(TOKEN_A, store.issue(target))
        assertEquals(TOKEN_B, store.issue(target))

        assertNull(store.consume(TOKEN_A))
        assertEquals(target, store.consume(TOKEN_B))
    }

    private fun navigationTarget() = ScheduledTaskResultNavigationTarget(
        workflowId = "workflow-stage233",
        scheduledTaskId = "scheduled-task-stage233",
        workflowRunId = "workflow-run-stage233",
    )

    private class InMemoryNavigationTokenBackend : ScheduledTaskResultNavigationTokenBackend {
        private var rawState: String? = null

        override fun read(): String? = rawState

        override fun write(rawState: String?): Boolean {
            this.rawState = rawState
            return true
        }
    }

    private companion object {
        const val TOKEN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val TOKEN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
