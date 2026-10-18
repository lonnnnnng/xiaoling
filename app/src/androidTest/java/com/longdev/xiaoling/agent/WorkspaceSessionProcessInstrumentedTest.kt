package com.longdev.xiaoling.agent

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** long: 两个独立 Android PID 只共享 UUID 账本；恢复夹具不模拟自然 LMK，也不重新执行旧命令。 */
class WorkspaceSessionProcessInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = WorkspaceSessionOwner("process-run", "process-chat", "process-message", "original-runtime")
    private fun directory(): File {
        assertEquals("begonia", android.os.Build.DEVICE)
        val uuid = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("workspaceUuid")))
        return File(context.filesDir, "stage290-workspace-process-$uuid")
    }
    private fun report(cleaned: Boolean = false) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("workspacePid", Process.myPid().toString())
        if (cleaned) putString("workspaceCleaned", "true")
    })
    @Test fun seedCompletedAndRunningTranscript() = runBlocking {
        val directory = directory(); assertFalse(directory.exists()); directory.mkdirs()
        val file = File(directory, "control/transcript.json")
        val sandbox = AndroidWorkspaceSandbox(File(directory, "workspace"), journalFile = file)
        val session = sandbox.openTerminal("nested", owner)
        sandbox.writeTerminal(session.id, "printf", listOf("completed-before-restart"), 2_000, owner, "completed-call")
        // long: 持久 RUNNING 边界由夹具注入；这里只验证重开控制状态，不声称真实遗留 OS 进程或自然系统回收。
        WorkspaceSessionJournal.forFile(file).update { rows -> rows.map { row -> row.copy(executions = row.executions +
            WorkspaceExecutionRecord("b".repeat(32), "running-call", "printf", listOf("must-not-replay"), System.currentTimeMillis())) } to Unit }
        File(directory, "session-id").writeText(session.id)
        assertEquals(WorkspaceExecutionState.RUNNING, sandbox.transcript(session.id, owner).executions.last().state)
        report()
    }
    @Test fun reopenInterruptsWithoutReplayAndPreservesCompletedFact() = runBlocking {
        val directory = directory(); assertTrue(directory.exists())
        try {
            val sandbox = AndroidWorkspaceSandbox(File(directory, "workspace"), journalFile = File(directory, "control/transcript.json"))
            val row = sandbox.transcript(File(directory, "session-id").readText(), owner)
            assertNotEquals(WorkspaceSessionJournal.PROCESS_TOKEN, row.processToken)
            assertEquals(WorkspaceSessionState.INTERRUPTED, row.state); assertTrue(row.runRevoked)
            assertEquals(WorkspaceExecutionState.COMPLETED, row.executions.first().state)
            assertEquals("completed-before-restart", row.executions.first().stdout)
            assertEquals(WorkspaceExecutionState.INTERRUPTED, row.executions.last().state)
            assertEquals("", row.executions.last().stdout); assertNull(row.executions.last().exitCode)
            assertTrue(runCatching { sandbox.writeTerminal(row.sessionId, "true", emptyList(), 2_000, owner, "replay") }.isFailure)
            assertEquals(2, sandbox.transcript(row.sessionId, owner).executions.size)
        } finally { assertTrue(directory.deleteRecursively()) }
        report(true)
    }
}
