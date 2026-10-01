package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommand
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommandResult
import com.longdev.xiaoling.shared.agent.SharedWorkspaceRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
)

interface WorkspaceSandbox : SharedWorkspaceRuntime {
    suspend fun list(path: String): List<WorkspaceEntry>
    suspend fun read(path: String, maxChars: Int = WorkspacePolicy.DEFAULT_MAX_CHARS): String
    suspend fun write(path: String, content: String): WorkspaceEntry
    suspend fun execute(commandId: String, args: List<String>, cwd: String, timeoutMs: Long): WorkspaceCommandResult
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
    suspend fun openTerminal(cwd: String): WorkspaceTerminalSession
    suspend fun writeTerminal(sessionId: String, commandId: String, args: List<String>, timeoutMs: Long): WorkspaceTerminalOutput
    suspend fun readTerminal(sessionId: String): WorkspaceTerminalOutput
    suspend fun closeTerminal(sessionId: String): Boolean
}

class AndroidWorkspaceSandbox(
    rootDirectory: File,
    private val commandDirectory: File = File("/system/bin"),
) : WorkspaceSandbox {
    private val root = rootDirectory.canonicalFile.apply { mkdirs() }
    private val terminalSessions = ConcurrentHashMap<String, TerminalSession>()
    private val terminalSessionLock = Any()

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

    override suspend fun execute(commandId: String, args: List<String>, cwd: String, timeoutMs: Long): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val command = WorkspaceCommandSpec(commandId, args)
        require(timeoutMs in 1..WorkspacePolicy.MAX_COMMAND_TIMEOUT_MS) { "终端超时时间超出限制" }
        val workingDirectory = resolveDirectory(cwd)
        // long: 这里直接传 argv，避免 shell 解析管道、重定向、子 Shell 和命令替换；可执行命令必须先进入固定白名单。
        val useAbsoluteSystemCommand = commandDirectory.isDirectory
        val executable = if (useAbsoluteSystemCommand) {
            File(commandDirectory, command.commandId).path
        } else {
            command.commandId
        }
        val process = ProcessBuilder(listOf(executable) + command.args)
            .directory(workingDirectory)
            .redirectErrorStream(false)
            .apply {
                // long: 固定系统命令使用绝对路径并清空应用进程环境，避免 PATH 或继承变量改变本次白名单命令的行为。
                if (useAbsoluteSystemCommand) environment().clear()
            }
            .start()
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) process.destroyForcibly()
        }
        try {
            val stdout = async { readLimited(process.inputStream) }
            val stderr = async { readLimited(process.errorStream) }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdout.cancel()
                stderr.cancel()
                return@withContext WorkspaceCommandResult(command.displayCommand(), -1, "", "命令执行超时", timedOut = true)
            }
            val stdoutResult = stdout.await()
            val stderrResult = stderr.await()
            WorkspaceCommandResult(
                command = command.displayCommand(),
                exitCode = process.exitValue(),
                stdout = stdoutResult.text,
                stderr = stderrResult.text,
                timedOut = false,
                stdoutTruncated = stdoutResult.truncated,
                stderrTruncated = stderrResult.truncated,
            )
        } finally {
            cancellation.dispose()
        }
    }

    override suspend fun openTerminal(cwd: String): WorkspaceTerminalSession = withContext(Dispatchers.IO) {
        val workingDirectory = resolveDirectory(cwd)
        synchronized(terminalSessionLock) {
            cleanupExpiredTerminalSessions()
            require(terminalSessions.size < WorkspacePolicy.MAX_TERMINAL_SESSIONS) {
                "终端会话数量已达到 ${WorkspacePolicy.MAX_TERMINAL_SESSIONS} 个上限"
            }
            // long: 会话只保存私有工作区目录和最近结果，不保持 shell 进程，后续命令仍须逐次审批并受固定白名单限制。
            val session = TerminalSession(
                id = UUID.randomUUID().toString().replace("-", "").take(16),
                cwd = relative(workingDirectory),
            )
            terminalSessions[session.id] = session
            WorkspaceTerminalSession(session.id, session.cwd)
        }
    }

    override suspend fun writeTerminal(
        sessionId: String,
        commandId: String,
        args: List<String>,
        timeoutMs: Long,
    ): WorkspaceTerminalOutput = withContext(Dispatchers.IO) {
        val session = terminalSessions[sessionId] ?: throw IllegalArgumentException("终端会话不存在或已关闭")
        session.mutex.withLock {
            require(terminalSessions[sessionId] === session) { "终端会话不存在或已关闭" }
            val result = execute(commandId, args, session.cwd, timeoutMs)
            session.lastActivityAt = System.currentTimeMillis()
            WorkspaceTerminalOutput(
                stdout = result.stdout,
                stderr = result.stderr,
                stdoutTruncated = result.stdoutTruncated,
                stderrTruncated = result.stderrTruncated,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
            ).also { session.lastOutput = it }
        }
    }

    override suspend fun readTerminal(sessionId: String): WorkspaceTerminalOutput = withContext(Dispatchers.IO) {
        cleanupExpiredTerminalSessions()
        val session = terminalSessions[sessionId] ?: throw IllegalArgumentException("终端会话不存在或已关闭")
        session.lastActivityAt = System.currentTimeMillis()
        session.lastOutput
    }

    override suspend fun closeTerminal(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        cleanupExpiredTerminalSessions()
        terminalSessions.remove(sessionId) != null
    }

    private fun cleanupExpiredTerminalSessions() {
        val cutoff = System.currentTimeMillis() - WorkspacePolicy.TERMINAL_SESSION_TTL_MILLIS
        terminalSessions.forEach { (id, session) ->
            if (session.lastActivityAt < cutoff) terminalSessions.remove(id, session)
        }
    }

    private suspend fun readLimited(input: java.io.InputStream): LimitedOutput = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8_192)
        val output = java.io.ByteArrayOutputStream()
        var truncated = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val remaining = WorkspacePolicy.MAX_OUTPUT_CHARS - output.size()
            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
            if (count > remaining) truncated = true
        }
        LimitedOutput(output.toByteArray().toString(Charsets.UTF_8), truncated)
    }

    private data class LimitedOutput(val text: String, val truncated: Boolean)

    private class TerminalSession(
        val id: String,
        val cwd: String,
        val mutex: Mutex = Mutex(),
        @Volatile var lastActivityAt: Long = System.currentTimeMillis(),
        @Volatile var lastOutput: WorkspaceTerminalOutput = WorkspaceTerminalOutput("", "", false, false),
    )

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
    override suspend fun execute(commandId: String, args: List<String>, cwd: String, timeoutMs: Long): WorkspaceCommandResult = unavailable()
    override suspend fun openTerminal(cwd: String): WorkspaceTerminalSession = unavailable()
    override suspend fun writeTerminal(sessionId: String, commandId: String, args: List<String>, timeoutMs: Long): WorkspaceTerminalOutput = unavailable()
    override suspend fun readTerminal(sessionId: String): WorkspaceTerminalOutput = unavailable()
    override suspend fun closeTerminal(sessionId: String): Boolean = unavailable()
}

object WorkspacePolicy {
    const val MAX_FILE_BYTES = 512 * 1024L
    const val DEFAULT_MAX_CHARS = 20_000
    const val MAX_MAX_CHARS = 100_000
    const val MAX_COMMAND_TIMEOUT_MS = 30_000L
    const val MAX_OUTPUT_CHARS = 20_000
    const val MAX_TERMINAL_SESSIONS = 4
    const val TERMINAL_SESSION_TTL_MILLIS = 10 * 60 * 1_000L
}
