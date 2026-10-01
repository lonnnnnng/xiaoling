package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommand
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommandResult
import com.longdev.xiaoling.shared.agent.SharedWorkspaceRuntime
import java.io.File
import java.io.IOException
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
    suspend fun writeTerminal(sessionId: String, input: String): WorkspaceTerminalOutput
    suspend fun readTerminal(sessionId: String): WorkspaceTerminalOutput
    suspend fun closeTerminal(sessionId: String): Boolean
}

class AndroidWorkspaceSandbox(rootDirectory: File) : WorkspaceSandbox {
    private val root = rootDirectory.canonicalFile.apply { mkdirs() }
    private val terminalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminalSessions = ConcurrentHashMap<String, TerminalSession>()

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
        val process = ProcessBuilder(listOf(command.commandId) + command.args)
            .directory(workingDirectory)
            .redirectErrorStream(false)
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
        cleanupExpiredTerminalSessions()
        require(terminalSessions.size < WorkspacePolicy.MAX_TERMINAL_SESSIONS) {
            "终端会话数量已达到 ${WorkspacePolicy.MAX_TERMINAL_SESSIONS} 个上限"
        }
        val workingDirectory = resolveDirectory(cwd)
        val process = ProcessBuilder("/system/bin/sh")
            .directory(workingDirectory)
            .redirectErrorStream(false)
            .start()
        val session = TerminalSession(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            cwd = relative(workingDirectory),
            process = process,
        )
        terminalSessions[session.id] = session
        terminalScope.launch { collectSessionOutput(process.inputStream, session.stdout) }
        terminalScope.launch { collectSessionOutput(process.errorStream, session.stderr) }
        WorkspaceTerminalSession(session.id, session.cwd)
    }

    override suspend fun writeTerminal(sessionId: String, input: String): WorkspaceTerminalOutput = withContext(Dispatchers.IO) {
        require(input.length <= WorkspacePolicy.MAX_SESSION_INPUT_CHARS) { "终端输入超过限制" }
        val session = terminalSessions[sessionId] ?: throw IllegalArgumentException("终端会话不存在或已关闭")
        require(session.process.isAlive) { "终端会话已经退出" }
        session.lastActivityAt = System.currentTimeMillis()
        session.process.outputStream.write(input.toByteArray(Charsets.UTF_8))
        session.process.outputStream.flush()
        session.drain()
    }

    override suspend fun readTerminal(sessionId: String): WorkspaceTerminalOutput = withContext(Dispatchers.IO) {
        cleanupExpiredTerminalSessions()
        val session = terminalSessions[sessionId] ?: throw IllegalArgumentException("终端会话不存在或已关闭")
        session.lastActivityAt = System.currentTimeMillis()
        session.drain()
    }

    override suspend fun closeTerminal(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        cleanupExpiredTerminalSessions()
        val session = terminalSessions.remove(sessionId) ?: return@withContext false
        runCatching { session.process.outputStream.close() }
        if (session.process.isAlive) session.process.destroyForcibly()
        true
    }

    private fun cleanupExpiredTerminalSessions() {
        val cutoff = System.currentTimeMillis() - WorkspacePolicy.TERMINAL_SESSION_TTL_MILLIS
        terminalSessions.forEach { (id, session) ->
            if (session.lastActivityAt < cutoff && terminalSessions.remove(id, session)) {
                runCatching { session.process.outputStream.close() }
                if (session.process.isAlive) session.process.destroyForcibly()
            }
        }
    }

    private suspend fun collectSessionOutput(input: java.io.InputStream, buffer: SessionOutputBuffer) = withContext(Dispatchers.IO) {
        val chunk = ByteArray(8_192)
        try {
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (count > 0) buffer.append(chunk, count)
            }
        } catch (_: IOException) {
            // long: 关闭终端时 Android 会先中断阻塞的 pipe read；进程已由 closeTerminal 回收，保留已采集输出即可。
        } finally {
            runCatching { input.close() }
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
        val process: Process,
        @Volatile var lastActivityAt: Long = System.currentTimeMillis(),
        val stdout: SessionOutputBuffer = SessionOutputBuffer(),
        val stderr: SessionOutputBuffer = SessionOutputBuffer(),
    ) {
        fun drain(): WorkspaceTerminalOutput {
            val out = stdout.drain()
            val err = stderr.drain()
            return WorkspaceTerminalOutput(out.text, err.text, out.truncated, err.truncated)
        }
    }

    private class SessionOutputBuffer {
        private val output = java.io.ByteArrayOutputStream()
        private var truncated = false

        @Synchronized
        fun append(bytes: ByteArray, count: Int) {
            val remaining = WorkspacePolicy.MAX_OUTPUT_CHARS - output.size()
            if (remaining > 0) output.write(bytes, 0, minOf(count, remaining))
            if (count > remaining) truncated = true
        }

        @Synchronized
        fun drain(): LimitedOutput {
            val result = LimitedOutput(output.toByteArray().toString(Charsets.UTF_8), truncated)
            output.reset()
            truncated = false
            return result
        }
    }

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
    override suspend fun writeTerminal(sessionId: String, input: String): WorkspaceTerminalOutput = unavailable()
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
    const val MAX_SESSION_INPUT_CHARS = 4_000
    const val TERMINAL_SESSION_TTL_MILLIS = 10 * 60 * 1_000L
}
