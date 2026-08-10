package com.longdev.xiaoling.automation

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.longdev.xiaoling.MainActivity
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduledTaskResultNavigationTarget(
    val workflowId: String,
    val scheduledTaskId: String,
    val workflowRunId: String?,
)

internal object ScheduledTaskResultNavigationPolicy {
    private val navigableStatuses = setOf(
        ScheduledTaskStatus.BLOCKED,
        ScheduledTaskStatus.COMPLETED,
        ScheduledTaskStatus.FAILED,
        ScheduledTaskStatus.CANCELLED,
    )

    fun targetFor(task: ScheduledTaskRecord): ScheduledTaskResultNavigationTarget? {
        if (task.status !in navigableStatuses) return null
        return ScheduledTaskResultNavigationTarget(
            workflowId = task.workflowId,
            scheduledTaskId = task.id,
            workflowRunId = task.workflowRunId,
        ).takeIf(ScheduledTaskResultNavigationTarget::isValid)
    }

    fun matchesCurrentState(
        target: ScheduledTaskResultNavigationTarget,
        task: ScheduledTaskRecord,
        workflowExists: Boolean,
        workflowRun: WorkflowRunRecord?,
    ): Boolean {
        // long: 私有令牌只是入口授权，最终落点仍以当前 Room 的任务关系为准；删除、重建或身份漂移后都不能跳到同名但不同的业务对象。
        val taskMatches = workflowExists &&
            task.status in navigableStatuses &&
            task.id == target.scheduledTaskId &&
            task.workflowId == target.workflowId &&
            task.workflowRunId == target.workflowRunId
        if (!taskMatches) return false

        val workflowRunId = target.workflowRunId ?: return workflowRun == null
        // long: Task 上保存的 Run ID 可能因异常清理或损坏数据变成悬空引用；只有 Run 本身仍存在并反向绑定同一 Workflow/Task 才允许精确高亮。
        return workflowRun?.id == workflowRunId &&
            workflowRun.workflowId == target.workflowId &&
            workflowRun.scheduledTaskId == target.scheduledTaskId
    }
}

internal object ScheduledTaskResultNavigationIntent {
    private const val ACTION_OPEN_RESULT = "com.longdev.xiaoling.action.OPEN_SCHEDULED_TASK_RESULT"
    private const val EXTRA_NAVIGATION_TOKEN = "com.longdev.xiaoling.extra.SCHEDULED_TASK_NAVIGATION_TOKEN"
    private const val FORBIDDEN_URI_PERMISSION_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun create(context: Context, token: String): Intent? {
        if (!isValidToken(token)) return null
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_RESULT
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATION_TOKEN, token)
        }
    }

    fun readToken(intent: Intent): String? {
        if (intent.action != ACTION_OPEN_RESULT) return null
        if ((intent.flags and FORBIDDEN_URI_PERMISSION_FLAGS) != 0) return null
        if (intent.data != null || intent.clipData != null || intent.selector != null || intent.type != null) return null
        // long: MainActivity 对外导出以接收系统分享，因此这里不把 action 或普通 extra 当身份；随机令牌必须再经私有 Store 原子消费才有导航资格。
        return runCatching { intent.getStringExtra(EXTRA_NAVIGATION_TOKEN) }
            .getOrNull()
            ?.takeIf(::isValidToken)
    }
}

internal interface ScheduledTaskResultNavigationTokenBackend {
    fun read(): String?

    fun write(rawState: String?): Boolean
}

internal class ScheduledTaskResultNavigationTokenStore(
    private val backend: ScheduledTaskResultNavigationTokenBackend,
    private val tokenGenerator: () -> String = ::newNavigationToken,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeToLiveMillis: Long = DEFAULT_TIME_TO_LIVE_MILLIS,
) {
    constructor(context: Context) : this(
        backend = SharedPreferencesNavigationTokenBackend(context.applicationContext),
    )

    init {
        require(timeToLiveMillis > 0L) { "导航令牌有效期必须大于 0" }
    }

    fun issue(target: ScheduledTaskResultNavigationTarget): String? = synchronized(PROCESS_LOCK) {
        if (!target.isValid()) return@synchronized null
        val now = clock()
        val expiresAt = runCatching { Math.addExact(now, timeToLiveMillis) }.getOrNull()
            ?: return@synchronized null
        val retained = decode(backend.read())
            .filter { entry -> entry.expiresAt > now && entry.target.scheduledTaskId != target.scheduledTaskId }
            .associateByTo(linkedMapOf()) { entry -> entry.token }
        val token = generateUniqueToken(retained.keys) ?: return@synchronized null
        retained[token] = StoredNavigationToken(
            token = token,
            target = target,
            issuedAt = now,
            expiresAt = expiresAt,
        )
        if (!backend.write(encode(retained.values))) return@synchronized null
        token
    }

    fun consume(token: String): ScheduledTaskResultNavigationTarget? = synchronized(PROCESS_LOCK) {
        if (!isValidToken(token)) return@synchronized null
        val now = clock()
        val stored = decode(backend.read())
        val matched = stored.singleOrNull { entry -> entry.token == token && entry.expiresAt > now }
        val retained = stored.filter { entry -> entry.token != token && entry.expiresAt > now }
        // long: 只有令牌删除已经同步落盘才返回导航目标；写入失败时保持拒绝，避免一次点击在多个 Activity 生命周期中重复授权。
        if (!backend.write(encode(retained))) return@synchronized null
        matched?.target
    }

    private fun generateUniqueToken(existingTokens: Set<String>): String? {
        repeat(MAX_TOKEN_GENERATION_ATTEMPTS) {
            val candidate = runCatching(tokenGenerator).getOrNull().orEmpty()
            if (isValidToken(candidate) && candidate !in existingTokens) return candidate
        }
        return null
    }

    private fun decode(rawState: String?): List<StoredNavigationToken> {
        if (rawState.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawState)
            if (root.optInt(KEY_VERSION, -1) != STATE_VERSION) return@runCatching emptyList()
            val entries = root.optJSONArray(KEY_ENTRIES) ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until entries.length()) {
                    val json = entries.optJSONObject(index) ?: continue
                    val workflowRunId = json.optString(KEY_WORKFLOW_RUN_ID)
                        .trim()
                        .takeIf(String::isNotBlank)
                    val entry = StoredNavigationToken(
                        token = json.optString(KEY_TOKEN),
                        target = ScheduledTaskResultNavigationTarget(
                            workflowId = json.optString(KEY_WORKFLOW_ID),
                            scheduledTaskId = json.optString(KEY_SCHEDULED_TASK_ID),
                            workflowRunId = workflowRunId,
                        ),
                        issuedAt = json.optLong(KEY_ISSUED_AT, Long.MIN_VALUE),
                        expiresAt = json.optLong(KEY_EXPIRES_AT, Long.MIN_VALUE),
                    )
                    if (
                        isValidToken(entry.token) &&
                        entry.target.isValid() &&
                        entry.issuedAt >= 0L &&
                        entry.expiresAt > entry.issuedAt
                    ) {
                        add(entry)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: Collection<StoredNavigationToken>): String? {
        if (entries.isEmpty()) return null
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(KEY_TOKEN, entry.token)
                    .put(KEY_WORKFLOW_ID, entry.target.workflowId)
                    .put(KEY_SCHEDULED_TASK_ID, entry.target.scheduledTaskId)
                    .put(KEY_ISSUED_AT, entry.issuedAt)
                    .put(KEY_EXPIRES_AT, entry.expiresAt)
                    .apply {
                        entry.target.workflowRunId?.let { workflowRunId ->
                            put(KEY_WORKFLOW_RUN_ID, workflowRunId)
                        }
                    },
            )
        }
        return JSONObject()
            .put(KEY_VERSION, STATE_VERSION)
            .put(KEY_ENTRIES, array)
            .toString()
    }

    private data class StoredNavigationToken(
        val token: String,
        val target: ScheduledTaskResultNavigationTarget,
        val issuedAt: Long,
        val expiresAt: Long,
    )

    private companion object {
        const val DEFAULT_TIME_TO_LIVE_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_TOKEN_GENERATION_ATTEMPTS = 4
        const val STATE_VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_ENTRIES = "entries"
        const val KEY_TOKEN = "token"
        const val KEY_WORKFLOW_ID = "workflowId"
        const val KEY_SCHEDULED_TASK_ID = "scheduledTaskId"
        const val KEY_WORKFLOW_RUN_ID = "workflowRunId"
        const val KEY_ISSUED_AT = "issuedAt"
        const val KEY_EXPIRES_AT = "expiresAt"
        val PROCESS_LOCK = Any()
    }
}

private class SharedPreferencesNavigationTokenBackend(context: Context) :
    ScheduledTaskResultNavigationTokenBackend {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString(KEY_STATE, null)

    override fun write(rawState: String?): Boolean {
        val editor = preferences.edit()
        if (rawState == null) {
            editor.remove(KEY_STATE)
        } else {
            editor.putString(KEY_STATE, rawState)
        }
        return editor.commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "xiaoling_scheduled_task_navigation"
        const val KEY_STATE = "navigation_tokens"
    }
}

private fun ScheduledTaskResultNavigationTarget.isValid(): Boolean {
    return workflowId.startsWith("workflow-") &&
        workflowId.length > "workflow-".length &&
        scheduledTaskId.startsWith("scheduled-task-") &&
        scheduledTaskId.length > "scheduled-task-".length &&
        (workflowRunId == null || (
            workflowRunId.startsWith("workflow-run-") &&
                workflowRunId.length > "workflow-run-".length
            ))
}

private fun isValidToken(token: String): Boolean {
    return token.length == NAVIGATION_TOKEN_LENGTH && token.all { character ->
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
    }
}

private fun newNavigationToken(): String {
    val bytes = ByteArray(NAVIGATION_TOKEN_BYTES).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private const val NAVIGATION_TOKEN_BYTES = 32
private const val NAVIGATION_TOKEN_LENGTH = 43
