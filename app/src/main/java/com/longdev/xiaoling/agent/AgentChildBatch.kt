package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** long: 用户点击新批次按钮时捕获一次许可；暂停再返回前台不能复用旧选择。 */
class AgentChildBatchSelection private constructor(
    private val foreground: AgentPluginForegroundState,
    val foregroundRevision: Long,
) {
    private var claimed = false

    @Synchronized internal fun claim(state: AgentPluginForegroundState) {
        require(state === foreground && state.isCurrent(foregroundRevision)) { "只读多 Agent 前台许可已失效" }
        check(!claimed) { "只读多 Agent 选择只能创建一个父任务" }
        claimed = true
    }

    internal suspend fun <T> guard(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            foreground.state.first { !foreground.isCurrent(foregroundRevision) }
            job.cancel(CancellationException("只读多 Agent 已离开授权前台"))
        }
        try {
            currentCoroutineContext().ensureActive()
            block().also { currentCoroutineContext().ensureActive() }
        } finally { watcher.cancel() }
    }

    companion object {
        fun capture(foreground: AgentPluginForegroundState): AgentChildBatchSelection {
            require(foreground.state.value.resumed) { "只读多 Agent 只允许在应用前台发起" }
            return AgentChildBatchSelection(foreground, foreground.capture())
        }
    }
}

data class AgentChildConsumedResult(
    val slotId: String,
    val runId: String,
    val goal: String,
    val status: AgentRunStatus,
    val completedAt: Long,
    val responseText: String?,
    val errorMessage: String?,
    val verifiedToolResults: Int,
)

data class AgentChildBatchOutcome(val parentRunId: String, val children: List<AgentChildConsumedResult>) {
    val status: AgentRunStatus get() = if (children.all { it.status == AgentRunStatus.COMPLETED })
        AgentRunStatus.COMPLETED else AgentRunStatus.FAILED

    fun summary(): String = buildString {
        val completed = children.count { it.status == AgentRunStatus.COMPLETED }
        append("只读多 Agent：完成 $completed/${children.size}")
        children.forEachIndexed { index, child ->
            append("\n\n目标 ${index + 1}：${child.goal}\n状态：${child.status.name} · 已验证工具结果 ${child.verifiedToolResults}\n")
            append(child.responseText ?: child.errorMessage ?: "未产生结果")
            if (child.responseText != null && child.errorMessage != null) append("\n失败原因：${child.errorMessage}")
        }
    }
}

object AgentChildBatchPolicy {
    const val SELECTION_EVENT = "multi_agent.foreground.selected"
    const val CONSUMED_EVENT = "multi_agent.results.consumed"
    const val PARENT_TIMEOUT_MS = 60_000L
    const val MAX_OUTCOME_CHARS = 300_000

    fun goals(goals: List<String>): List<String> = goals.map(String::trim).also { normalized ->
        MultiAgentPolicy.validateChildren(normalized.mapIndexed { index, goal -> ReadOnlyChildAgentSpec("child-${index + 1}", goal) })
    }

    fun encode(outcome: AgentChildBatchOutcome): String {
        require(outcome.parentRunId.isNotBlank() && outcome.children.size in 1..MultiAgentPolicy.MAX_CHILDREN)
        require(outcome.children.map { it.runId }.distinct().size == outcome.children.size)
        require(outcome.children.map { it.slotId } == outcome.children.indices.map { "child-${it + 1}" })
        return JSONObject().put("schemaVersion", 1).put("parentRunId", outcome.parentRunId).put("status", outcome.status.name)
            .put("children", JSONArray(outcome.children.map { child ->
                require(child.status.isTerminal && child.completedAt > 0 && child.verifiedToolResults in 0..AgentChildLifecyclePolicy.MAX_TOOL_CALLS)
                require(child.runId.isNotBlank())
                require(child.goal.isNotBlank() && child.goal.length <= MultiAgentPolicy.MAX_GOAL_LENGTH)
                require((child.responseText?.length ?: 0) <= AgentChildLifecyclePolicy.MAX_RESULT_CHARS && (child.errorMessage?.length ?: 0) <= 500)
                JSONObject().put("slotId", child.slotId).put("runId", child.runId).put("goal", child.goal)
                    .put("status", child.status.name).put("completedAt", child.completedAt)
                    .put("responseText", child.responseText ?: JSONObject.NULL).put("errorMessage", child.errorMessage ?: JSONObject.NULL)
                    .put("verifiedToolResults", child.verifiedToolResults)
            })).toString().also { require(it.length <= MAX_OUTCOME_CHARS) }
    }

    fun decode(raw: String): AgentChildBatchOutcome {
        require(raw.length <= MAX_OUTCOME_CHARS) { "child 汇总超出容量" }
        val json = JSONObject(raw)
        require(json.getInt("schemaVersion") == 1)
        val children = json.getJSONArray("children")
        require(children.length() in 1..MultiAgentPolicy.MAX_CHILDREN)
        return AgentChildBatchOutcome(json.getString("parentRunId"), (0 until children.length()).map { index ->
            val child = children.getJSONObject(index)
            AgentChildConsumedResult(child.getString("slotId"), child.getString("runId"), child.getString("goal"),
                AgentRunStatus.valueOf(child.getString("status")), child.getLong("completedAt"),
                child.getStringOrNull("responseText"), child.getStringOrNull("errorMessage"), child.getInt("verifiedToolResults"))
        }).also { outcome -> encode(outcome); require(outcome.status.name == json.getString("status")) }
    }

    private fun JSONObject.getStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
