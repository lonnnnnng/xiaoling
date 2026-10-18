package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommand
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommandResult
import com.longdev.xiaoling.shared.agent.SharedWorkspaceRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID

data class WorkspaceEntry(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
)

data class WorkspaceCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val sessionId: String = "",
    val executionId: String = "",
)

data class WorkspaceCommandSpec(
    val commandId: String,
    val args: List<String> = emptyList(),
) {
    init {
        WorkspaceCommandPolicy.validate(commandId, args)
    }

    fun displayCommand(): String = buildString {
        append(commandId)
        args.forEach { arg ->
            append(' ')
            append(arg)
        }
    }
}

object WorkspaceCommandPolicy {
    const val MAX_ARGUMENTS = 8
    const val MAX_ARGUMENT_LENGTH = 512

    private val argumentCounts = mapOf(
        "pwd" to 0..0,
        "date" to 0..0,
        "true" to 0..0,
        "false" to 0..0,
        "echo" to 1..8,
        "printf" to 1..1,
        "uname" to 0..1,
    )

    fun validate(commandId: String, args: List<String>) {
        require(commandId.matches(Regex("[a-z][a-z0-9._-]{0,31}"))) { "命令 ID 格式无效" }
        val allowedCount = argumentCounts[commandId]
            ?: throw IllegalArgumentException("命令未在固定白名单中：$commandId")
        require(args.size in allowedCount) { "命令 $commandId 的参数数量不符合限制" }
        require(args.size <= MAX_ARGUMENTS) { "命令参数数量超过限制" }
        args.forEach { arg ->
            require(arg.isNotEmpty() && arg.length <= MAX_ARGUMENT_LENGTH) { "命令参数为空或过长" }
            require(arg.none { it == '\u0000' || it == '\n' || it == '\r' }) { "命令参数包含控制字符" }
        }
        if (commandId == "uname") {
            require(args.isEmpty() || args.single() in setOf("-s", "-a")) { "uname 只允许 -s 或 -a" }
        }
    }
}

data class WorkspaceTerminalSession(
    val id: String,
    val cwd: String,
)

data class WorkspaceTerminalOutput(
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val sessionId: String = "",
    val executionId: String = "",
    val state: WorkspaceExecutionState? = null,
    val transcript: List<WorkspaceExecutionRecord> = emptyList(),
    val cwd: String = "",
)

interface WorkspaceSandbox : SharedWorkspaceRuntime {
    suspend fun list(path: String): List<WorkspaceEntry>
    suspend fun read(path: String, maxChars: Int = WorkspacePolicy.DEFAULT_MAX_CHARS): String
    suspend fun write(path: String, content: String): WorkspaceEntry
    suspend fun execute(commandId: String, args: List<String>, cwd: String, timeoutMs: Long,
        owner: WorkspaceSessionOwner? = null, toolCallId: String = ""): WorkspaceCommandResult
    override suspend fun execute(
        command: SharedWorkspaceCommand,
        cwd: String,
        timeoutMs: Long,
    ): SharedWorkspaceCommandResult {
        val result = execute(command.commandId, command.args, cwd, timeoutMs)
        return SharedWorkspaceCommandResult(
            commandId = command.commandId,
            args = command.args,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
        )
    }
    suspend fun openTerminal(cwd: String, owner: WorkspaceSessionOwner? = null): WorkspaceTerminalSession
    suspend fun writeTerminal(sessionId: String, commandId: String, args: List<String>, timeoutMs: Long,
        owner: WorkspaceSessionOwner? = null, toolCallId: String = ""): WorkspaceTerminalOutput
    suspend fun readTerminal(sessionId: String, owner: WorkspaceSessionOwner? = null): WorkspaceTerminalOutput
    suspend fun closeTerminal(sessionId: String, owner: WorkspaceSessionOwner? = null): Boolean
    suspend fun transcript(sessionId: String, owner: WorkspaceSessionOwner? = null): WorkspaceSessionRecord
    fun finishRun(runId: String) {}
}

class AndroidWorkspaceSandbox(
    rootDirectory: File,
    private val commandDirectory: File = File("/system/bin"),
    journalFile: File = File(rootDirectory.parentFile, rootDirectory.name + "-sessions/transcript.json"),
) : WorkspaceSandbox {
    private val root = rootDirectory.canonicalFile.apply { mkdirs() }
    private val journal = WorkspaceSessionJournal.forFile(journalFile.also {
        require(it.canonicalPath != root.path && !it.canonicalPath.startsWith(root.path + File.separator)) {
            "Workspace 控制账本必须位于工作区外"
        }
    })
    private val localOwner = WorkspaceSessionOwner("local-${UUID.randomUUID()}", "", "", "")
    private val finishedRuns = journal.finishedRuns

    override suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val directory = resolveDirectory(path)
        directory.listFiles().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }).map { file ->
            WorkspaceEntry(relative(file), file.isDirectory, if (file.isFile) file.length() else 0L)
        }
    }

    override suspend fun read(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        require(maxChars in 1..WorkspacePolicy.MAX_MAX_CHARS) { "max_chars 超出限制" }
        val file = resolveFile(path)
        require(file.length() <= WorkspacePolicy.MAX_FILE_BYTES) { "文件超过 ${WorkspacePolicy.MAX_FILE_BYTES} 字节读取上限" }
        file.readText(Charsets.UTF_8).take(maxChars)
    }

    override suspend fun write(path: String, content: String): WorkspaceEntry = withContext(Dispatchers.IO) {
        val file = resolveFile(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= WorkspacePolicy.MAX_FILE_BYTES) { "文件超过 ${WorkspacePolicy.MAX_FILE_BYTES} 字节写入上限" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        WorkspaceEntry(relative(file), directory = false, sizeBytes = bytes.size.toLong())
    }

    override suspend fun execute(
        commandId: String, args: List<String>, cwd: String, timeoutMs: Long,
        owner: WorkspaceSessionOwner?, toolCallId: String,
    ): WorkspaceCommandResult {
        WorkspaceCommandPolicy.validate(commandId, args)
        require(timeoutMs in 1..WorkspacePolicy.MAX_COMMAND_TIMEOUT_MS) { "终端超时时间超出限制" }
        val session = openTerminal(cwd, owner)
        try {
            val output = writeTerminal(session.id, commandId, args, timeoutMs, owner, toolCallId)
            return WorkspaceCommandResult(WorkspaceCommandSpec(commandId, args).displayCommand(), output.exitCode ?: -1,
                output.stdout, output.stderr, output.timedOut, output.stdoutTruncated, output.stderrTruncated,
                session.id, output.executionId)
        } finally {
            // long: 一次性 execute 同样保留 transcript，但不占用后续可写会话；取消状态不能被关闭覆盖。
            closeSession(session.id, owner ?: localOwner, WorkspaceSessionState.CLOSED)
        }
    }

    override suspend fun openTerminal(cwd: String, owner: WorkspaceSessionOwner?): WorkspaceTerminalSession = withContext(Dispatchers.IO) {
        val identity = owner ?: localOwner
        require(identity.runId.isNotBlank() && listOf(identity.runId, identity.conversationId, identity.userMessageId,
            identity.processSessionId).all { it.length <= 256 && !it.contains('\u0000') }) { "终端 owner 无效" }
        val directory = relative(resolveDirectory(cwd))
        require(directory.length <= 512) { "终端目录过长" }
        cleanupExpired()
        journal.update { rows ->
            rejectUnless(identity.runId !in finishedRuns && rows.none { it.owner.runId == identity.runId &&
                (it.runRevoked || it.processToken != WorkspaceSessionJournal.PROCESS_TOKEN) }, "Run 已结束或属于旧进程，不能打开终端")
            rejectUnless(rows.size < WorkspacePolicy.MAX_HISTORY_SESSIONS, "终端历史容量已满，旧记录保留")
            rejectUnless(rows.count { it.state == WorkspaceSessionState.OPEN } < WorkspacePolicy.MAX_TERMINAL_SESSIONS,
                "终端会话数量已达到 ${WorkspacePolicy.MAX_TERMINAL_SESSIONS} 个上限")
            val now = System.currentTimeMillis()
            val row = WorkspaceSessionRecord(UUID.randomUUID().toString().replace("-", "").take(16), identity,
                WorkspaceSessionJournal.PROCESS_TOKEN, directory, now, now)
            (rows + row) to WorkspaceTerminalSession(row.sessionId, row.cwd)
        }
    }

    override suspend fun writeTerminal(
        sessionId: String, commandId: String, args: List<String>, timeoutMs: Long,
        owner: WorkspaceSessionOwner?, toolCallId: String,
    ): WorkspaceTerminalOutput = withContext(Dispatchers.IO) {
        WorkspaceCommandPolicy.validate(commandId, args)
        require(timeoutMs in 1..WorkspacePolicy.MAX_COMMAND_TIMEOUT_MS && toolCallId.length <= 256) { "终端执行参数超出限制" }
        val identity = owner ?: localOwner
        cleanupExpired()
        val executionId = UUID.randomUUID().toString().replace("-", "")
        val active = WorkspaceActiveExecution(identity)
        journal.active[executionId] = active
        try {
            val cwd = journal.update { rows ->
                val row = owned(rows, sessionId, identity)
                rejectUnless(row.state == WorkspaceSessionState.OPEN && identity.runId !in finishedRuns, "终端会话已关闭或 Run 已结束")
                rejectUnless(row.executions.none { it.state == WorkspaceExecutionState.RUNNING }, "终端已有命令执行中")
                rejectUnless(row.executions.size < WorkspacePolicy.MAX_EXECUTIONS_PER_SESSION, "会话 transcript 容量已满，旧记录保留")
                rejectUnless(toolCallId.isBlank() || rows.filter { it.owner == identity }.flatMap { it.executions }
                    .none { it.toolCallId == toolCallId }, "工具调用已经执行，不能重放")
                val now = maxOf(System.currentTimeMillis(), row.lastActivityAt)
                val execution = WorkspaceExecutionRecord(executionId, toolCallId, commandId, args.toList(), now)
                rows.map { if (it.sessionId == sessionId) row.copy(lastActivityAt = now, executions = row.executions + execution) else it } to row.cwd
            }
            // long: RUNNING 已原子落盘后才允许启动；关闭与启动共用账本锁，避免关闭后迟到的命令逃过撤销。
            currentCoroutineContext().ensureActive()
            val process = journal.read { rows ->
                val row = owned(rows, sessionId, identity)
                if (row.state != WorkspaceSessionState.OPEN || active.cancelled || identity.runId in finishedRuns)
                    throw WorkspaceJournalRejected("命令启动前终端已撤销")
                val absolute = commandDirectory.isDirectory
                val executable = if (absolute) File(commandDirectory, commandId).path else commandId
                ProcessBuilder(listOf(executable) + args).directory(resolveDirectory(cwd)).redirectErrorStream(false)
                    .apply { if (absolute) environment().clear() }.start().also { active.process = it }
            }
            process.outputStream.close()
            val stdout = OutputCollector()
            val stderr = OutputCollector()
            try {
                val output = coroutineScope {
                    val outReader = async(Dispatchers.IO) { stdout.read(process.inputStream, active) }
                    val errReader = async(Dispatchers.IO) { stderr.read(process.errorStream, active) }
                    try {
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                        var timedOut = false
                        while (process.isAlive) {
                            currentCoroutineContext().ensureActive()
                            if (active.cancelled) throw CancellationException("终端会话已撤销")
                            if (System.nanoTime() >= deadline) { timedOut = true; break }
                            delay(10)
                        }
                        if (active.cancelled) throw CancellationException("终端会话已撤销")
                        if (timedOut) {
                            active.stopProcess()
                            check(process.waitFor(1_000, TimeUnit.MILLISECONDS)) { "终端进程终止未确认" }
                        }
                        outReader.await()
                        errReader.await()
                        val out = stdout.snapshot()
                        val err = stderr.snapshot()
                        WorkspaceTerminalOutput(out.text, err.text, out.truncated, err.truncated,
                            if (timedOut) null else process.exitValue(), timedOut, sessionId, executionId,
                            if (timedOut) WorkspaceExecutionState.TIMED_OUT else if (process.exitValue() == 0)
                                WorkspaceExecutionState.COMPLETED else WorkspaceExecutionState.FAILED)
                    } finally {
                        // long: 协程取消先杀进程再等待读流子协程退出，避免阻塞 read 阻止取消回执结算。
                        active.stopProcess()
                    }
                }
                val recorded = settle(sessionId, executionId, output)
                if (recorded?.state == WorkspaceExecutionState.CANCELLED) throw CancellationException("终端会话已撤销")
                output
            } catch (error: Throwable) {
                active.cancel()
                process.waitFor(1_000, TimeUnit.MILLISECONDS)
                val out = stdout.snapshot()
                val err = stderr.snapshot()
                settle(sessionId, executionId, WorkspaceTerminalOutput(out.text, err.text, out.truncated, err.truncated,
                    sessionId = sessionId, executionId = executionId, state = if (error is CancellationException)
                        WorkspaceExecutionState.CANCELLED else WorkspaceExecutionState.FAILED))
                throw error
            } finally {
                active.cancel()
                process.inputStream.close()
                process.errorStream.close()
            }
        } catch (error: Throwable) {
            // long: 启动失败也留下一次失败回执；取消继续向 Runtime 传播，不能转成普通工具成功。
            settle(sessionId, executionId, WorkspaceTerminalOutput("", "命令启动或执行中断", false, false,
                sessionId = sessionId, executionId = executionId, state = if (error is CancellationException)
                    WorkspaceExecutionState.CANCELLED else WorkspaceExecutionState.FAILED), fillCancelled = false)
            throw error
        } finally {
            active.cancel()
            active.process?.waitFor(1_000, TimeUnit.MILLISECONDS)
            journal.active.remove(executionId)
        }
    }

    override suspend fun transcript(sessionId: String, owner: WorkspaceSessionOwner?): WorkspaceSessionRecord = withContext(Dispatchers.IO) {
        cleanupExpired()
        journal.update { rows ->
            val row = owned(rows, sessionId, owner ?: localOwner)
            val touched = if (row.state == WorkspaceSessionState.OPEN) row.copy(lastActivityAt = maxOf(System.currentTimeMillis(), row.lastActivityAt)) else row
            rows.map { if (it.sessionId == sessionId) touched else it } to touched
        }
    }

    override suspend fun readTerminal(sessionId: String, owner: WorkspaceSessionOwner?): WorkspaceTerminalOutput {
        val row = transcript(sessionId, owner)
        val last = row.executions.lastOrNull()
        return WorkspaceTerminalOutput(last?.stdout.orEmpty(), last?.stderr.orEmpty(), last?.stdoutTruncated ?: false,
            last?.stderrTruncated ?: false, last?.exitCode, last?.state == WorkspaceExecutionState.TIMED_OUT,
            row.sessionId, last?.executionId.orEmpty(), last?.state, row.executions, row.cwd)
    }

    override suspend fun closeTerminal(sessionId: String, owner: WorkspaceSessionOwner?): Boolean = withContext(Dispatchers.IO) {
        closeSession(sessionId, owner ?: localOwner, WorkspaceSessionState.CLOSED)
    }

    override fun finishRun(runId: String) {
        finishedRuns.add(runId)
        try {
            journal.update { rows -> rows.map { row ->
                if (row.owner.runId == runId) {
                    val finished = if (row.state == WorkspaceSessionState.OPEN) closed(row, WorkspaceSessionState.CLOSED) else row
                    finished.copy(runRevoked = true)
                } else row
            } to Unit }
        } catch (_: IllegalStateException) {
            // long: 写盘故障已冻结账本，Run 仍须结算；撤销实际进程不能依赖终态落盘成功。
        } finally {
            journal.active.values.filter { it.owner.runId == runId }.forEach { it.cancel() }
        }
    }

    private fun cleanupExpired() {
        journal.update { rows -> rows.map { row ->
            if (row.state == WorkspaceSessionState.OPEN && row.lastActivityAt < System.currentTimeMillis() - WorkspacePolicy.TERMINAL_SESSION_TTL_MILLIS)
                closed(row, WorkspaceSessionState.EXPIRED) else row
        } to Unit }
    }

    private fun closeSession(id: String, identity: WorkspaceSessionOwner, state: WorkspaceSessionState): Boolean = journal.update { rows ->
        val row = owned(rows, id, identity)
        rows.map { if (it.sessionId == id && it.state == WorkspaceSessionState.OPEN) closed(it, state) else it } to
            (row.state == WorkspaceSessionState.OPEN)
    }

    private fun closed(row: WorkspaceSessionRecord, state: WorkspaceSessionState): WorkspaceSessionRecord {
        val now = maxOf(System.currentTimeMillis(), row.lastActivityAt)
        row.executions.filter { it.state == WorkspaceExecutionState.RUNNING }.forEach { journal.active[it.executionId]?.cancel() }
        return row.copy(state = state, executions = row.executions.map { execution ->
            if (execution.state == WorkspaceExecutionState.RUNNING) execution.copy(state = WorkspaceExecutionState.CANCELLED,
                finishedAt = now) else execution
        })
    }

    private fun settle(sessionId: String, executionId: String, output: WorkspaceTerminalOutput,
        fillCancelled: Boolean = true): WorkspaceExecutionRecord? =
        journal.update { rows ->
            val changed = rows.map { row ->
                if (row.sessionId != sessionId) row else row.copy(executions = row.executions.map { execution ->
                    if (execution.executionId != executionId || (execution.state != WorkspaceExecutionState.RUNNING &&
                            !(fillCancelled && execution.state == WorkspaceExecutionState.CANCELLED))) execution
                    else execution.copy(state = if (execution.state == WorkspaceExecutionState.CANCELLED) execution.state
                            else output.state ?: WorkspaceExecutionState.FAILED,
                        finishedAt = execution.finishedAt ?: maxOf(System.currentTimeMillis(), execution.startedAt),
                        stdout = if (output.stdout.isEmpty()) execution.stdout else output.stdout,
                        stderr = if (output.stderr.isEmpty()) execution.stderr else output.stderr,
                        stdoutTruncated = execution.stdoutTruncated || output.stdoutTruncated,
                        stderrTruncated = execution.stderrTruncated || output.stderrTruncated,
                        exitCode = if (execution.state == WorkspaceExecutionState.CANCELLED) null else output.exitCode)
                })
            }
            changed to changed.find { it.sessionId == sessionId }?.executions?.find { it.executionId == executionId }
        }

    private fun owned(rows: List<WorkspaceSessionRecord>, id: String, identity: WorkspaceSessionOwner): WorkspaceSessionRecord {
        val row = rows.find { it.sessionId == id } ?: throw WorkspaceJournalRejected("终端会话不存在")
        rejectUnless(row.owner == identity, "终端会话不属于当前 Run")
        return row
    }

    private fun rejectUnless(condition: Boolean, message: String) { if (!condition) throw WorkspaceJournalRejected(message) }

    private class OutputCollector {
        private val output = java.io.ByteArrayOutputStream()
        private var truncated = false
        fun read(input: java.io.InputStream, active: WorkspaceActiveExecution) {
            val buffer = ByteArray(8_192)
            while (true) {
                val count = try { input.read(buffer) } catch (error: java.io.IOException) {
                    // long: Android 杀进程会关闭管道并中断 read；仅已请求终止时保留已收集字节，正常 I/O 故障仍须失败。
                    if (active.stopping) break else throw error
                }
                if (count < 0) break
                synchronized(this) {
                    val remaining = WorkspacePolicy.MAX_OUTPUT_CHARS - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                }
            }
        }
        @Synchronized fun snapshot() = LimitedOutput(output.toByteArray().toString(Charsets.UTF_8), truncated)
    }

    private data class LimitedOutput(val text: String, val truncated: Boolean)

    private fun resolveFile(path: String): File {
        val file = resolve(path)
        require(!file.exists() || file.isFile) { "目标不是普通文件" }
        return file
    }

    private fun resolveDirectory(path: String): File {
        val directory = resolve(path)
        require(!directory.exists() || directory.isDirectory) { "目标不是目录" }
        directory.mkdirs()
        return directory
    }

    private fun resolve(path: String): File {
        val normalized = path.trim().ifBlank { "." }
        require(!normalized.contains('\u0000')) { "路径包含非法字符" }
        val candidate = File(root, normalized).canonicalFile
        require(candidate == root || candidate.path.startsWith(root.path + File.separator)) { "路径必须位于工作区目录内" }
        return candidate
    }

    private fun relative(file: File): String = file.relativeTo(root).path.ifBlank { "." }
}

object DisabledWorkspaceSandbox : WorkspaceSandbox {
    private fun unavailable(): Nothing = error("工作区/终端尚未初始化")
    override suspend fun list(path: String): List<WorkspaceEntry> = unavailable()
    override suspend fun read(path: String, maxChars: Int): String = unavailable()
    override suspend fun write(path: String, content: String): WorkspaceEntry = unavailable()
    override suspend fun execute(commandId: String, args: List<String>, cwd: String, timeoutMs: Long, owner: WorkspaceSessionOwner?, toolCallId: String): WorkspaceCommandResult = unavailable()
    override suspend fun openTerminal(cwd: String, owner: WorkspaceSessionOwner?): WorkspaceTerminalSession = unavailable()
    override suspend fun writeTerminal(sessionId: String, commandId: String, args: List<String>, timeoutMs: Long, owner: WorkspaceSessionOwner?, toolCallId: String): WorkspaceTerminalOutput = unavailable()
    override suspend fun readTerminal(sessionId: String, owner: WorkspaceSessionOwner?): WorkspaceTerminalOutput = unavailable()
    override suspend fun closeTerminal(sessionId: String, owner: WorkspaceSessionOwner?): Boolean = unavailable()
    override suspend fun transcript(sessionId: String, owner: WorkspaceSessionOwner?): WorkspaceSessionRecord = unavailable()
}

object WorkspacePolicy {
    const val MAX_FILE_BYTES = 512 * 1024L
    const val DEFAULT_MAX_CHARS = 20_000
    const val MAX_MAX_CHARS = 100_000
    const val MAX_COMMAND_TIMEOUT_MS = 30_000L
    const val MAX_OUTPUT_CHARS = 20_000
    const val MAX_TERMINAL_SESSIONS = 4
    const val MAX_HISTORY_SESSIONS = 16
    const val MAX_EXECUTIONS_PER_SESSION = 8
    const val MAX_JOURNAL_BYTES = 24 * 1024 * 1024L
    const val TERMINAL_SESSION_TTL_MILLIS = 10 * 60 * 1_000L
}
