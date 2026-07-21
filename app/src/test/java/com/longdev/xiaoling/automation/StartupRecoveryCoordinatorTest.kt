package com.longdev.xiaoling.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecoveryCoordinatorTest {
    @Test
    fun startupRecoveryKeepsOldCandidatesAndExcludesCurrentWorkerChain() = runTest {
        val registry = ScheduledWorkflowProcessExecutionRegistry()

        registry.withScheduledTask("current-task") {
            val coordinator = StartupRecoveryCoordinator(
                processExecutionRegistry = registry,
                loadAgentRunIds = { setOf("old-agent", "current-agent") },
                loadWorkflowCandidates = { currentProcessTaskIds ->
                    assertEquals(setOf("current-task"), currentProcessTaskIds)
                    WorkflowStartupRecoveryCandidates(
                        activeWorkflowRunIds = setOf("old-workflow", "current-workflow"),
                        runningScheduledTaskIds = setOf("old-task", "current-task"),
                        currentProcessWorkflowRunIds = setOf("current-workflow"),
                        currentProcessAgentRunIds = setOf("current-agent"),
                    )
                },
            )

            assertEquals(
                StartupRecoveryCandidateIds(
                    agentRunIds = setOf("old-agent"),
                    workflowRunIds = setOf("old-workflow"),
                    scheduledTaskIds = setOf("old-task"),
                ),
                coordinator.capture(),
            )
        }
    }

    @Test
    fun workerStartingDuringRecoveryWaitsUntilCandidateSnapshotCompletes() = runTest {
        val registry = ScheduledWorkflowProcessExecutionRegistry()
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val workerEntered = CompletableDeferred<Unit>()

        val snapshot = async {
            registry.captureRecoveryBoundary { currentProcessTaskIds ->
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                currentProcessTaskIds
            }
        }
        snapshotStarted.await()
        val worker = launch {
            registry.withScheduledTask("new-task") {
                workerEntered.complete(Unit)
            }
        }
        yield()

        assertFalse(workerEntered.isCompleted)
        releaseSnapshot.complete(Unit)
        assertEquals(emptySet<String>(), snapshot.await())
        worker.join()
        assertTrue(workerEntered.isCompleted)
    }
}
