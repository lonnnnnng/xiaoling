package com.longdev.xiaoling.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceSessionTest {
    private val owner = WorkspaceSessionOwner("workspace-run", "conversation", "message", "runtime-process")
    private data class Fixture(val sandbox: AndroidWorkspaceSandbox, val file: File, val commands: File)
    private fun fixture(slow: Boolean = false): Fixture {
        val directory = Files.createTempDirectory("workspace-transcript-").toFile()
        val commands = File(directory, "commands").apply { mkdirs() }
        if (slow) {
            // long: 仅夹具替换系统命令目录来制造确定的长命令；生产白名单和 argv 策略不开放 sleep 或 shell。
            File(commands, "printf").apply { writeText("#!/bin/sh\nprintf started\nexec /bin/sleep 20\n"); setExecutable(true) }
        }
        val file = File(directory, "control/transcript.json")
        return Fixture(AndroidWorkspaceSandbox(File(directory, "workspace"), if (slow) commands else File(directory, "absent"), file), file, commands)
    }
    private suspend fun running(f: Fixture): Process = withTimeout(5_000) {
        var process: Process? = null
        while (process == null) {
            process = WorkspaceSessionJournal.forFile(f.file).active.values.firstOrNull()?.process
            if (process == null) delay(10)
        }
        process
    }

    @Test fun transcriptsSurviveReopenAndRejectForeignOwnerAndReplay() = runBlocking {
        val f = fixture(); val session = f.sandbox.openTerminal("nested", owner)
        val first = f.sandbox.writeTerminal(session.id, "printf", listOf("first"), 2_000, owner, "call-1")
        val second = f.sandbox.writeTerminal(session.id, "false", emptyList(), 2_000, owner, "call-2")
        assertEquals(WorkspaceExecutionState.COMPLETED, first.state)
        assertEquals(WorkspaceExecutionState.FAILED, second.state)
        assertNotEquals(first.executionId, second.executionId)
        val reopened = AndroidWorkspaceSandbox(File(requireNotNull(f.file.parentFile).parentFile, "workspace"), journalFile = f.file)
        assertEquals(listOf("first", ""), reopened.transcript(session.id, owner).executions.map { it.stdout })
        assertEquals("nested", reopened.transcript(session.id, owner).cwd)
        assertTrue(runCatching { reopened.readTerminal(session.id, owner.copy(runId = "other")) }.isFailure)
        assertTrue(runCatching { reopened.writeTerminal(session.id, "printf", listOf("replay"), 2_000, owner, "call-1") }.isFailure)
        assertEquals(2, reopened.transcript(session.id, owner).executions.size)
        f.sandbox.finishRun(owner.runId)
        assertTrue(runCatching { reopened.openTerminal(".", owner) }.isFailure)
        assertEquals(WorkspaceSessionState.CLOSED, reopened.transcript(session.id, owner).state)
    }

    @Test fun finishingUnusedRunRevokesOtherInstancesInSameProcess() = runBlocking {
        val f = fixture()
        val reopened = AndroidWorkspaceSandbox(File(requireNotNull(f.file.parentFile).parentFile, "workspace"), journalFile = f.file)
        f.sandbox.finishRun(owner.runId)
        assertTrue(runCatching { reopened.openTerminal(".", owner) }.isFailure)
    }

    @Test fun timeoutIsRecordedAndActualProcessIsKilled() = runBlocking {
        val f = fixture(true); val session = f.sandbox.openTerminal(".", owner)
        val output = f.sandbox.writeTerminal(session.id, "printf", listOf("ignored"), 40, owner, "timeout")
        assertEquals(WorkspaceExecutionState.TIMED_OUT, output.state)
        assertTrue(output.timedOut); assertNull(output.exitCode)
        assertEquals(output.state, f.sandbox.transcript(session.id, owner).executions.single().state)
        assertTrue(WorkspaceSessionJournal.forFile(f.file).active.isEmpty())
    }

    @Test fun cancellationClosesActualProcessAndKeepsReceipt() = runBlocking {
        val f = fixture(true); val session = f.sandbox.openTerminal(".", owner)
        val job = launch { f.sandbox.writeTerminal(session.id, "printf", listOf("ignored"), 20_000, owner, "cancel") }
        val process = running(f)
        withTimeout(3_000) { job.cancelAndJoin() }
        assertFalse(process.isAlive)
        assertEquals(WorkspaceExecutionState.CANCELLED, f.sandbox.transcript(session.id, owner).executions.single().state)
    }

    @Test fun concurrentWriteAndCloseCannotReportLateSuccess() = runBlocking {
        val f = fixture(true); val session = f.sandbox.openTerminal(".", owner)
        val job = async { f.sandbox.writeTerminal(session.id, "printf", listOf("ignored"), 20_000, owner, "first") }
        val process = running(f)
        assertTrue(runCatching { f.sandbox.writeTerminal(session.id, "true", emptyList(), 2_000, owner, "second") }.isFailure)
        assertTrue(f.sandbox.closeTerminal(session.id, owner))
        assertTrue(runCatching { withTimeout(3_000) { job.await() } }.exceptionOrNull() is CancellationException)
        assertFalse(process.isAlive)
        val row = f.sandbox.transcript(session.id, owner)
        assertEquals(WorkspaceSessionState.CLOSED, row.state)
        assertEquals(WorkspaceExecutionState.CANCELLED, row.executions.single().state)
    }

    @Test fun runFinishCancelsAndPersistentlyRevokesOwner() = runBlocking {
        val f = fixture(true); val session = f.sandbox.openTerminal(".", owner)
        val job = async { f.sandbox.writeTerminal(session.id, "printf", listOf("ignored"), 20_000, owner, "first") }
        val process = running(f); f.sandbox.finishRun(owner.runId)
        assertTrue(runCatching { withTimeout(3_000) { job.await() } }.exceptionOrNull() is CancellationException)
        assertFalse(process.isAlive)
        assertTrue(f.sandbox.transcript(session.id, owner).runRevoked)
    }

    @Test fun fullTranscriptAndHistoryPreserveExistingEvidence() = runBlocking {
        val f = fixture(); val session = f.sandbox.openTerminal(".", owner)
        repeat(WorkspacePolicy.MAX_EXECUTIONS_PER_SESSION) { f.sandbox.writeTerminal(session.id, "true", emptyList(), 2_000, owner, "call-$it") }
        assertTrue(runCatching { f.sandbox.writeTerminal(session.id, "true", emptyList(), 2_000, owner, "overflow") }.isFailure)
        assertEquals(WorkspacePolicy.MAX_EXECUTIONS_PER_SESSION, f.sandbox.transcript(session.id, owner).executions.size)
        f.sandbox.closeTerminal(session.id, owner)
        repeat(WorkspacePolicy.MAX_HISTORY_SESSIONS - 1) { val s = f.sandbox.openTerminal(".", owner); f.sandbox.closeTerminal(s.id, owner) }
        assertTrue(runCatching { f.sandbox.openTerminal(".", owner) }.isFailure)
        assertEquals(WorkspacePolicy.MAX_HISTORY_SESSIONS, WorkspaceSessionJournal.forFile(f.file).read { it.size })
    }

    @Test fun diskFailureBeforeLaunchAndCorruptionFreezeExecution() = runBlocking {
        val f = fixture(true); val session = f.sandbox.openTerminal(".", owner)
        File(f.file.path + ".new").mkdir()
        assertTrue(runCatching { f.sandbox.writeTerminal(session.id, "printf", listOf("ignored"), 1_000, owner, "disk") }.isFailure)
        assertTrue(WorkspaceSessionJournal.forFile(f.file).active.isEmpty())
        assertFalse(f.file.readText().contains("disk"))
        val damaged = fixture(); damaged.sandbox.openTerminal(".", owner)
        damaged.file.writeText("{broken")
        assertTrue(runCatching { damaged.sandbox.execute("true", emptyList(), ".", 1_000, owner) }.isFailure)
        assertEquals("{broken", damaged.file.readText())
    }

    @Test fun oldProcessRunningRecordConvergesWithoutReplay() = runBlocking {
        val f = fixture(); val session = f.sandbox.openTerminal(".", owner)
        WorkspaceSessionJournal.forFile(f.file).update { rows -> rows.map { row -> row.copy(processToken = "old-process",
            executions = listOf(WorkspaceExecutionRecord("a".repeat(32), "old-call", "printf", listOf("must-not-replay"), row.createdAt))) } to Unit }
        val row = f.sandbox.transcript(session.id, owner)
        assertEquals(WorkspaceSessionState.INTERRUPTED, row.state)
        assertEquals(WorkspaceExecutionState.INTERRUPTED, row.executions.single().state)
        assertEquals("", row.executions.single().stdout)
        assertTrue(runCatching { f.sandbox.writeTerminal(session.id, "true", emptyList(), 1_000, owner) }.isFailure)
        assertTrue(runCatching { f.sandbox.openTerminal(".", owner) }.isFailure)
    }

    @Test fun expirationAndOutputBoundsStayInsideControlPolicy() = runBlocking {
        val f = fixture(); val session = f.sandbox.openTerminal(".", owner)
        val output = f.sandbox.writeTerminal(session.id, "printf", listOf("%100000s"), 2_000, owner, "large")
        assertTrue(output.stdoutTruncated)
        assertEquals(WorkspacePolicy.MAX_OUTPUT_CHARS, output.stdout.toByteArray().size)
        WorkspaceSessionJournal.forFile(f.file).update { rows -> rows.map { it.copy(createdAt = it.createdAt - WorkspacePolicy.TERMINAL_SESSION_TTL_MILLIS - 1_000,
            lastActivityAt = it.createdAt - WorkspacePolicy.TERMINAL_SESSION_TTL_MILLIS - 1_000) } to Unit }
        assertEquals(WorkspaceSessionState.EXPIRED, f.sandbox.transcript(session.id, owner).state)
        assertTrue(runCatching { f.sandbox.write("../control/transcript.json", "tamper") }.isFailure)
    }
}
