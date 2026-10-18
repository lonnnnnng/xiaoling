package com.longdev.xiaoling.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** long: UUID 私有夹具与独立 Room 验证生产终端控制面，正式配置、用户数据库与撤销文件不参与测试。 */
class WorkspaceSessionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = WorkspaceSessionOwner("device-workspace-run", "device-chat", "device-message", "device-runtime")
    private fun root() = File(context.cacheDir, "stage290-workspace-${UUID.randomUUID()}").apply {
        assertEquals("begonia", android.os.Build.DEVICE); mkdirs()
    }
    private fun registry(db: XiaoLingDatabase, sandbox: WorkspaceSandbox, directory: File) = XiaoLingToolRegistry(
        SystemAgentClock(), RoomAgentConversationStore(context, db), noteStore = RoomAgentNoteStore(context, db),
        memoryStore = RoomAgentMemoryStore(context, db, File(directory, "undo.json")),
        knowledgeStore = RoomKnowledgeDocumentStore(context, db), workspaceSandbox = sandbox)

    @Test fun runtimeApprovalAndTypedLedgerMatchPersistentTranscript() = runBlocking {
        val directory = root()
        val db = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        try {
            for (command in listOf("true", "false")) {
                val file = File(directory, "$command/control/transcript.json")
                val sandbox = AndroidWorkspaceSandbox(File(directory, "$command/workspace"), journalFile = file)
                val native = registry(db, sandbox, directory)
                val repo = RoomAgentRunRepository(context, db)
                var approvals = 0
                var actualRunId = ""
                val model = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) =
                        ToolCall(name = "terminal.execute", arguments = mapOf("command_id" to command), risk = ToolRisk.REQUIRES_APPROVAL)
                    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) =
                        """{"style":"compact","tone":"neutral"}"""
                }
                val gate = object : ApprovalGate {
                    override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                        approvals++; actualRunId = runId; return ApprovalDecision(true, "独立夹具逐次确认")
                    }
                }
                val outcome = runCatching { MinimalAgentRuntime(repo, native, model, gate, processSessionId = "device-runtime")
                    .run("device-chat", "device-message", "终端联合验收", memoryRecallEnabled = false) }
                assertEquals(command == "true", outcome.isSuccess)
                assertEquals(if (command == "true") AgentRunStatus.COMPLETED else AgentRunStatus.FAILED, repo.snapshot(actualRunId).run.status)
                assertEquals(1, approvals)
                val row = WorkspaceSessionJournal.forFile(file).read { it.single() }
                assertEquals(owner.copy(runId = actualRunId), row.owner)
                assertTrue(row.runRevoked); assertEquals(WorkspaceSessionState.CLOSED, row.state)
                val ledger = repo.toolLedger(actualRunId)
                assertEquals(row.executions.single().toolCallId, ledger.calls.single().id)
                assertEquals(command == "true", ledger.results.single().success)
                assertEquals(command == "true", ledger.results.single().executorVerified)
                if (command == "true") assertEquals(ToolVerificationStatus.PASSED, ledger.results.single().verificationStatus)
                else assertNotEquals(ToolVerificationStatus.PASSED, ledger.results.single().verificationStatus)
            }
            println("DEVICE_WORKSPACE_RUNTIME approval=true typed_ledger=true owner=true nonzero_failed=true run_revoked=true")
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun realArgvTimeoutCancellationAndRunCloseConverge() = runBlocking {
        val directory = root()
        try {
            val sandbox = AndroidWorkspaceSandbox(File(directory, "workspace"), journalFile = File(directory, "control/transcript.json"))
            val session = sandbox.openTerminal("nested", owner)
            val output = sandbox.writeTerminal(session.id, "printf", listOf("%1000000000s"), 1, owner, "timeout")
            assertTrue(output.timedOut); assertEquals(WorkspaceExecutionState.TIMED_OUT, output.state)
            val job = async { sandbox.writeTerminal(session.id, "printf", listOf("%1000000000s"), 20_000, owner, "cancel") }
            val journal = WorkspaceSessionJournal.forFile(File(directory, "control/transcript.json"))
            val process = withTimeout(5_000) {
                var found: Process? = null
                while (found == null) { found = journal.active.values.firstOrNull()?.process; if (found == null) delay(5) }
                found
            }
            sandbox.finishRun(owner.runId)
            assertTrue(runCatching { withTimeout(3_000) { job.await() } }.exceptionOrNull() is CancellationException)
            assertFalse(process.isAlive)
            val row = sandbox.transcript(session.id, owner)
            assertEquals(listOf(WorkspaceExecutionState.TIMED_OUT, WorkspaceExecutionState.CANCELLED), row.executions.map { it.state })
            assertTrue(row.runRevoked)
            assertTrue(runCatching { sandbox.openTerminal(".", owner) }.isFailure)
            println("DEVICE_WORKSPACE_PROCESS argv=true timeout=true cancellation=true process_dead=true receipt_preserved=true")
        } finally { directory.deleteRecursively() }
    }

    @Test fun controlIsolationAndCorruptionFailClosed() = runBlocking {
        val directory = root()
        try {
            val file = File(directory, "control/transcript.json")
            val sandbox = AndroidWorkspaceSandbox(File(directory, "workspace"), journalFile = file)
            val session = sandbox.openTerminal(".", owner)
            sandbox.writeTerminal(session.id, "printf", listOf("bounded"), 2_000, owner, "first")
            assertTrue(runCatching { sandbox.readTerminal(session.id, owner.copy(runId = "foreign")) }.isFailure)
            assertTrue(runCatching { sandbox.write("../control/transcript.json", "tamper") }.isFailure)
            assertEquals("bounded", sandbox.readTerminal(session.id, owner).stdout)
            file.writeText("{damaged")
            assertTrue(runCatching { sandbox.writeTerminal(session.id, "true", emptyList(), 2_000, owner, "blocked") }.isFailure)
            assertEquals("{damaged", file.readText())
            println("DEVICE_WORKSPACE_CONTROL owner_rejected=true traversal_rejected=true corruption_frozen=true")
        } finally { directory.deleteRecursively() }
    }
}
