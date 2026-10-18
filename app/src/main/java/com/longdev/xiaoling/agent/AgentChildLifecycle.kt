package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AgentChildSlot(val id: String, val runId: String)

data class AgentChildCheckpoint(
    val parentRunId: String,
    val profile: AgentProfileSnapshot,
    val options: AgentRuntimeOptions,
    val slots: List<AgentChildSlot>,
    val ownerSessionId: String = AgentChildLifecyclePolicy.processSessionId,
)

object AgentChildLifecyclePolicy {
    val processSessionId: String = UUID.randomUUID().toString()
    const val INVOCATION_EVENT = "run.invocation.selected"
    const val BATCH_EVENT = "multi_agent.batch.prepared"
    const val CHILD_EVENT = "multi_agent.child.prepared"
    const val CLAIM_EVENT = "multi_agent.child.claimed"
    const val MAX_RESULT_CHARS = 20_000
    const val MAX_TOOL_CALLS = 3
    const val MAX_RUN_MS = 60_000L
    const val MAX_MODEL_MS = 20_000L
    const val MAX_TOOL_MS = 10_000L
    val toolNames = setOf("app.current_time", "app.get_info", "app.get_battery", "app.get_connectivity",
        "app.get_storage", "app.list_conversations", "app.search_conversations", "notes.list", "notes.search",
        "notes.get", "memory.search", "memory.get", "knowledge.search")

    fun isManagedRun(detail: AgentRunDetailRecord): Boolean =
        detail.snapshot.events.any { it.type == BATCH_EVENT || it.type == CHILD_EVENT || it.type == AgentChildBatchPolicy.SELECTION_EVENT }

    fun requireFreshInvocation(detail: AgentRunDetailRecord) {
        // long: checkpoint 只记录旧派生事实；单独重试 child 会绕过父批次的槽位、预算和取消边界。
        require(!isManagedRun(detail)) { "多 Agent 旧批次只保留证据，请从新的父 Run 显式派生" }
    }

    fun requireCurrentOwner(checkpoint: AgentChildCheckpoint) {
        // long: 新进程可能早于启动对账收到直接执行请求；连未领取槽位也不能继承旧进程的前台批次授权。
        require(checkpoint.ownerSessionId == processSessionId) { "child 批次所属进程已失效" }
    }

    fun invocation(origin: AgentExecutionOrigin, source: AgentInvocationSource, externalInput: Boolean): String =
        JSONObject().put("schemaVersion", 1).put("origin", origin.name).put("source", source.name)
            .put("externalInput", externalInput).put("ownerSessionId", processSessionId).toString()

    fun validateParent(detail: AgentRunDetailRecord, profile: AgentProfileSnapshot) {
        val run = detail.snapshot.run
        require(!run.status.isTerminal && run.cancelRequestedAt == null && run.parentRunId == null) {
            "父 Run 已结束、取消或不属于根任务"
        }
        require(!AgentPluginExecutionPolicy.isPluginRun(detail)) { "插件任务不允许派生 child" }
        require(detail.agentProfileSnapshotOrNull() == profile) { "派生配置与父 Run 冻结 Profile 不一致" }
        val evidence = detail.snapshot.events.filter { it.type == INVOCATION_EVENT }.singleOrNull()
        val json = JSONObject((evidence?.metadata as? RunEventMetadata.Reason)?.reason ?: error("父 Run 缺少唯一来源审计"))
        require(json.getString("ownerSessionId") == processSessionId) { "父 Run 所属进程已失效" }
        require(json.getInt("schemaVersion") == 1 && json.getString("origin") == "FOREGROUND" &&
            json.getString("source") == "DIRECT" && !json.getBoolean("externalInput")) {
            "只读 child 仅允许本机前台 Direct 根任务派生"
        }
    }

    fun options(detail: AgentRunDetailRecord): AgentRuntimeOptions {
        val budget = (AgentExecutionBudgetEvidencePolicy.read(detail) as? AgentExecutionBudgetEvidenceAssessment.Available)
            ?.snapshot ?: error("父 Run 缺少可信预算")
        val remaining = (budget.totalTimeoutMs - budget.consumedMs).coerceAtMost(MAX_RUN_MS)
        require(remaining > 0) { "父 Run 预算已耗尽" }
        // long: child 不获得超过父最新持久预算的执行时长；第一片不恢复旧时钟或返还已消耗预算。
        return AgentRuntimeOptions(MAX_TOOL_CALLS, remaining, minOf(MAX_MODEL_MS, remaining), minOf(MAX_TOOL_MS, remaining))
    }

    fun validateCheckpoint(checkpoint: AgentChildCheckpoint) {
        AgentProfilePolicy.validateRunnable(checkpoint.profile)
        require(checkpoint.parentRunId.isNotBlank())
        require(UUID.fromString(checkpoint.ownerSessionId).toString() == checkpoint.ownerSessionId)
        require(checkpoint.slots.size in 1..MultiAgentPolicy.MAX_CHILDREN)
        require(checkpoint.slots.map { it.id }.distinct().size == checkpoint.slots.size)
        require(checkpoint.slots.map { it.runId }.distinct().size == checkpoint.slots.size)
        require(checkpoint.slots.all { it.id.matches(Regex("child-[12]")) && it.runId.isNotBlank() })
        require(!checkpoint.profile.memoryEnabled && checkpoint.profile.allowedSkillIds.isEmpty())
        require(checkpoint.profile.allowedToolNames.size <= toolNames.size &&
            checkpoint.profile.allowedToolNames.distinct().size == checkpoint.profile.allowedToolNames.size)
        require(checkpoint.profile.allowedToolNames.all { it in toolNames })
        require(checkpoint.options.maxToolCalls in 1..MAX_TOOL_CALLS && checkpoint.options.runTimeoutMs in 1..MAX_RUN_MS &&
            checkpoint.options.modelStepTimeoutMs in 1..MAX_MODEL_MS && checkpoint.options.toolStepTimeoutMs in 1..MAX_TOOL_MS)
    }

    fun encode(checkpoint: AgentChildCheckpoint): String {
        validateCheckpoint(checkpoint)
        return JSONObject().put("schemaVersion", 1).put("parentRunId", checkpoint.parentRunId).put("ownerSessionId", checkpoint.ownerSessionId)
            .put("profile", JSONObject(RunEventMetadataCodec.encode(RunEventMetadata.AgentProfileSelection(checkpoint.profile))))
            .put("maxToolCalls", checkpoint.options.maxToolCalls).put("runTimeoutMs", checkpoint.options.runTimeoutMs)
            .put("modelStepTimeoutMs", checkpoint.options.modelStepTimeoutMs).put("toolStepTimeoutMs", checkpoint.options.toolStepTimeoutMs)
            .put("slots", JSONArray(checkpoint.slots.map { JSONObject().put("id", it.id).put("runId", it.runId) })).toString()
    }

    fun decode(raw: String): AgentChildCheckpoint {
        require(raw.length <= 32_768) { "child checkpoint 超出容量" }
        val json = JSONObject(raw)
        require(json.getInt("schemaVersion") == 1) { "未知 child checkpoint 版本" }
        val profile = (RunEventMetadataCodec.decode(AgentEventTypes.PROFILE_SELECTED, json.getJSONObject("profile").toString())
            as? RunEventMetadata.AgentProfileSelection)?.profile ?: error("child Profile 无法解析")
        val slots = json.getJSONArray("slots")
        require(slots.length() in 1..MultiAgentPolicy.MAX_CHILDREN)
        return AgentChildCheckpoint(json.getString("parentRunId"), profile,
            AgentRuntimeOptions(json.getInt("maxToolCalls"), json.getLong("runTimeoutMs"),
                json.getLong("modelStepTimeoutMs"), json.getLong("toolStepTimeoutMs")),
            (0 until slots.length()).map { slots.getJSONObject(it).let { slot -> AgentChildSlot(slot.getString("id"), slot.getString("runId")) } },
            json.getString("ownerSessionId"))
            .also(::validateCheckpoint)
    }
}
