package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkspaceSessionOwner(
    val runId: String,
    val conversationId: String,
    val userMessageId: String,
    val processSessionId: String,
) {
    companion object {
        fun from(context: AgentToolExecutionContext) = WorkspaceSessionOwner(
            context.runId, context.conversationId, context.userMessageId, context.processSessionId,
        )
    }
}

enum class WorkspaceSessionState { OPEN, CLOSED, EXPIRED, INTERRUPTED }
enum class WorkspaceExecutionState { RUNNING, COMPLETED, FAILED, TIMED_OUT, CANCELLED, INTERRUPTED }

data class WorkspaceExecutionRecord(
    val executionId: String,
    val toolCallId: String,
    val commandId: String,
    val args: List<String>,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val state: WorkspaceExecutionState = WorkspaceExecutionState.RUNNING,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val exitCode: Int? = null,
)

data class WorkspaceSessionRecord(
    val sessionId: String,
    val owner: WorkspaceSessionOwner,
    val processToken: String,
    val cwd: String,
    val createdAt: Long,
    val lastActivityAt: Long,
    val state: WorkspaceSessionState = WorkspaceSessionState.OPEN,
    val executions: List<WorkspaceExecutionRecord> = emptyList(),
    val runRevoked: Boolean = false,
)

/** long: 控制账本位于工作区外，文件工具不能修改 owner、执行状态或历史；容量耗尽保留旧证据并拒绝新命令。 */
internal class WorkspaceSessionJournal private constructor(private val file: File) {
    val active = ConcurrentHashMap<String, WorkspaceActiveExecution>()
    // long: 尚无终端的 Run 没有可恢复副作用；同进程共享撤销集阻止另一个执行器重新授权，已有会话另存持久 runRevoked。
    val finishedRuns = ConcurrentHashMap.newKeySet<String>()
    private var poisoned = false
    private val gate = Any()

    fun <T> read(block: (List<WorkspaceSessionRecord>) -> T): T = access { rows -> rows to block(rows) }

    fun <T> update(block: (List<WorkspaceSessionRecord>) -> Pair<List<WorkspaceSessionRecord>, T>): T = access(block)

    private fun <T> access(block: (List<WorkspaceSessionRecord>) -> Pair<List<WorkspaceSessionRecord>, T>): T = synchronized(gate) {
        check(!poisoned) { "Workspace 会话账本不可用，已冻结终端执行" }
        var callerFailed = false
        try {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建 Workspace 账本目录" }
            RandomAccessFile(File(file.path + ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val loaded = load()
                    // long: 新进程只收敛旧控制状态；旧命令的实际退出码未知，不能伪造成功或恢复审批后重放。
                    val now = System.currentTimeMillis()
                    val recovered = loaded.map { row ->
                        if (row.processToken != PROCESS_TOKEN && row.state == WorkspaceSessionState.OPEN) {
                            row.copy(state = WorkspaceSessionState.INTERRUPTED, runRevoked = true, executions = row.executions.map { execution ->
                                if (execution.state == WorkspaceExecutionState.RUNNING) execution.copy(
                                    state = WorkspaceExecutionState.INTERRUPTED, finishedAt = now,
                                ) else execution
                            })
                        } else row
                    }
                    if (recovered != loaded) save(recovered)
                    callerFailed = true
                    val (changed, result) = block(recovered)
                    callerFailed = false
                    validate(changed)
                    if (changed != recovered) save(changed)
                    result
                }
            }
        } catch (error: WorkspaceJournalRejected) {
            throw error
        } catch (error: Throwable) {
            if (callerFailed) throw error
            poisoned = true
            active.values.forEach { it.cancel() }
            throw IllegalStateException("Workspace 会话账本不可用，已冻结终端执行", error)
        }
    }

    private fun load(): List<WorkspaceSessionRecord> {
        if (!file.exists()) {
            check(!File(file.path + ".new").exists()) { "Workspace 初始账本写入中断" }
            return emptyList()
        }
        check(file.length() in 1..WorkspacePolicy.MAX_JOURNAL_BYTES) { "Workspace 账本大小无效" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        check(json.getInt("version") == 1) { "Workspace 账本版本不支持" }
        val rows = json.getJSONArray("sessions")
        check(rows.length() <= WorkspacePolicy.MAX_HISTORY_SESSIONS)
        return List(rows.length()) { index -> decode(rows.getJSONObject(index)) }.also(::validate)
    }

    private fun save(rows: List<WorkspaceSessionRecord>) {
        val bytes = JSONObject().put("version", 1).put("sessions", JSONArray(rows.map(::encode)))
            .toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= WorkspacePolicy.MAX_JOURNAL_BYTES) { "Workspace 账本容量不足" }
        val temporary = File(file.path + ".new")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        // long: 不支持原子替换就拒绝执行，不能用普通覆盖把半份 transcript 当成完整审批证据。
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun validate(rows: List<WorkspaceSessionRecord>) {
        check(rows.size <= WorkspacePolicy.MAX_HISTORY_SESSIONS)
        check(rows.map { it.sessionId }.distinct().size == rows.size)
        check(rows.flatMap { it.executions }.map { it.executionId }.distinct().size == rows.sumOf { it.executions.size })
        check(rows.count { it.state == WorkspaceSessionState.OPEN } <= WorkspacePolicy.MAX_TERMINAL_SESSIONS)
        rows.forEach { row ->
            check(row.sessionId.matches(Regex("[a-f0-9]{16}")))
            check(row.processToken.length in 1..128 && row.cwd.length in 1..512)
            check(!File(row.cwd).isAbsolute && !row.cwd.contains('\u0000') && row.cwd.split(File.separatorChar).none { it == ".." })
            check(listOf(row.owner.runId, row.owner.conversationId, row.owner.userMessageId, row.owner.processSessionId)
                .all { it.length <= 256 && !it.contains('\u0000') })
            check(row.owner.runId.isNotBlank() && row.createdAt > 0 && row.lastActivityAt >= row.createdAt)
            check(!row.runRevoked || row.state != WorkspaceSessionState.OPEN)
            check(row.executions.size <= WorkspacePolicy.MAX_EXECUTIONS_PER_SESSION)
            check(row.executions.count { it.state == WorkspaceExecutionState.RUNNING } <= 1)
            row.executions.forEach { execution ->
                check(execution.executionId.matches(Regex("[a-f0-9]{32}")) && execution.toolCallId.length <= 256)
                WorkspaceCommandPolicy.validate(execution.commandId, execution.args)
                check(execution.startedAt >= row.createdAt)
                check(execution.stdout.toByteArray(Charsets.UTF_8).size <= WorkspacePolicy.MAX_OUTPUT_CHARS * 3)
                check(execution.stderr.toByteArray(Charsets.UTF_8).size <= WorkspacePolicy.MAX_OUTPUT_CHARS * 3)
                check((execution.state == WorkspaceExecutionState.RUNNING) == (execution.finishedAt == null))
                check(execution.finishedAt == null || execution.finishedAt >= execution.startedAt)
                check(execution.state != WorkspaceExecutionState.COMPLETED || execution.exitCode == 0)
                check(execution.state != WorkspaceExecutionState.FAILED || execution.exitCode == null || execution.exitCode != 0)
                check(execution.state !in setOf(WorkspaceExecutionState.RUNNING, WorkspaceExecutionState.CANCELLED,
                    WorkspaceExecutionState.TIMED_OUT, WorkspaceExecutionState.INTERRUPTED) || execution.exitCode == null)
                check(row.state == WorkspaceSessionState.OPEN || execution.state != WorkspaceExecutionState.RUNNING)
            }
        }
    }

    private fun encode(row: WorkspaceSessionRecord) = JSONObject()
        .put("sessionId", row.sessionId).put("runId", row.owner.runId)
        .put("conversationId", row.owner.conversationId).put("userMessageId", row.owner.userMessageId)
        .put("processSessionId", row.owner.processSessionId).put("processToken", row.processToken)
        .put("cwd", row.cwd).put("createdAt", row.createdAt).put("lastActivityAt", row.lastActivityAt)
        .put("state", row.state.name).put("runRevoked", row.runRevoked).put("executions", JSONArray(row.executions.map { execution ->
            JSONObject().put("executionId", execution.executionId).put("toolCallId", execution.toolCallId)
                .put("commandId", execution.commandId).put("args", JSONArray(execution.args))
                .put("startedAt", execution.startedAt).put("finishedAt", execution.finishedAt ?: JSONObject.NULL)
                .put("state", execution.state.name).put("stdout", execution.stdout).put("stderr", execution.stderr)
                .put("stdoutTruncated", execution.stdoutTruncated).put("stderrTruncated", execution.stderrTruncated)
                .put("exitCode", execution.exitCode ?: JSONObject.NULL)
        }))

    private fun decode(json: JSONObject): WorkspaceSessionRecord {
        val executions = json.getJSONArray("executions")
        check(executions.length() <= WorkspacePolicy.MAX_EXECUTIONS_PER_SESSION)
        return WorkspaceSessionRecord(
            json.getString("sessionId"), WorkspaceSessionOwner(json.getString("runId"), json.getString("conversationId"),
                json.getString("userMessageId"), json.getString("processSessionId")),
            json.getString("processToken"), json.getString("cwd"), json.getLong("createdAt"), json.getLong("lastActivityAt"),
            WorkspaceSessionState.valueOf(json.getString("state")), List(executions.length()) { index ->
                val execution = executions.getJSONObject(index)
                val args = execution.getJSONArray("args")
                check(args.length() <= WorkspaceCommandPolicy.MAX_ARGUMENTS)
                WorkspaceExecutionRecord(
                    execution.getString("executionId"), execution.getString("toolCallId"), execution.getString("commandId"),
                    List(args.length()) { args.getString(it) }, execution.getLong("startedAt"),
                    if (execution.isNull("finishedAt")) null else execution.getLong("finishedAt"),
                    WorkspaceExecutionState.valueOf(execution.getString("state")), execution.getString("stdout"), execution.getString("stderr"),
                    execution.getBoolean("stdoutTruncated"), execution.getBoolean("stderrTruncated"),
                    if (execution.isNull("exitCode")) null else execution.getInt("exitCode"),
                )
            }, json.getBoolean("runRevoked"),
        )
    }

    companion object {
        val PROCESS_TOKEN: String = UUID.randomUUID().toString()
        private val stores = ConcurrentHashMap<String, WorkspaceSessionJournal>()
        fun forFile(file: File): WorkspaceSessionJournal = stores.computeIfAbsent(file.canonicalPath) { WorkspaceSessionJournal(file.canonicalFile) }
    }
}

internal class WorkspaceJournalRejected(message: String) : IllegalArgumentException(message)

internal class WorkspaceActiveExecution(val owner: WorkspaceSessionOwner) {
    @Volatile var cancelled = false
    @Volatile var stopping = false
    @Volatile var process: Process? = null
    fun stopProcess() { stopping = true; process?.destroyForcibly() }
    fun cancel() { cancelled = true; stopProcess() }
}
