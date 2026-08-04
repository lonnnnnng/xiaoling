package com.longdev.xiaoling.ui

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalTaskCreationCommitTest {
    @Test
    fun capturesCommittedIdentityWhenCallerIsCancelledDuringCommitReturn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val captured = AtomicReference<String?>(null)

        val job = launch {
            capturePersonalTaskCommit(
                dispatcher = dispatcher,
                create = {
                    started.complete(Unit)
                    release.await()
                    "workflow-run-1"
                },
                onCommitted = captured::set,
            )
        }

        advanceUntilIdle()
        started.await()
        job.cancel()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("workflow-run-1", captured.get())
    }
}
