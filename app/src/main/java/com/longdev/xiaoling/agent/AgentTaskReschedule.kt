package com.longdev.xiaoling.agent

import java.time.OffsetDateTime
import java.time.ZoneId

internal const val TASK_RESCHEDULE_TOOL_NAME = "tasks.reschedule"

data class AgentTaskOneTimeScheduleRecord(val plannedAt: Long, val token: String)

data class AgentTaskRescheduleRequest(
    val name: String,
    val expectedPlannedAt: String,
    val plannedAt: String,
    val timeZone: String,
    val expectedScheduleToken: String,
) {
    val expectedPlannedAtMillis: Long get() = OffsetDateTime.parse(expectedPlannedAt).toInstant().toEpochMilli()
    val plannedAtMillis: Long get() = OffsetDateTime.parse(plannedAt).toInstant().toEpochMilli()

    fun matches(schedule: AgentTaskOneTimeScheduleRecord): Boolean =
        schedule.plannedAt == expectedPlannedAtMillis && schedule.token == expectedScheduleToken

    fun isFutureTimeAllowed(now: Long): Boolean = plannedAtMillis - now in 60_000L..604_800_000L

    fun resultText(systemCancellationFailed: Boolean = false): String = buildString {
        appendLine("任务“$name”：一次性提醒已改期。")
        appendLine("原时间：$expectedPlannedAt")
        appendLine("新时间：$plannedAt")
        appendLine("时区：$timeZone")
        append("系统可能延迟执行；旧 Run 和已执行实例保持不变。")
        if (systemCancellationFailed) append(" 旧系统工作项取消失败，但旧实例已取消，残留工作会安全跳过。")
    }

    companion object {
        val argumentNames = setOf("name", "expected_planned_at", "planned_at", "time_zone", "expected_schedule_token")

        fun parse(arguments: Map<String, String>): AgentTaskRescheduleRequest? = runCatching {
            require(arguments.keys == argumentNames)
            val name = arguments.getValue("name")
            require(name == name.trim() && name.isNotEmpty() && name.length <= 100 && name.none(Char::isISOControl))
            val oldTime = arguments.getValue("expected_planned_at")
            val newTime = arguments.getValue("planned_at")
            val zoneName = arguments.getValue("time_zone")
            require(zoneName in ZoneId.getAvailableZoneIds())
            val zone = ZoneId.of(zoneName)
            val old = OffsetDateTime.parse(oldTime)
            val new = OffsetDateTime.parse(newTime)
            old.toInstant().toEpochMilli()
            new.toInstant().toEpochMilli()
            // long: 明确偏移与命名时区必须一致，避免模型把墙上时间按另一时区解释，导致审批内容与真正执行时间不同。
            require(old.offset == zone.rules.getOffset(old.toInstant()) && new.offset == zone.rules.getOffset(new.toInstant()))
            require(old.nano % 1_000_000 == 0 && new.nano % 1_000_000 == 0 && old.toInstant() != new.toInstant())
            val token = arguments.getValue("expected_schedule_token")
            require(token.matches(Regex("one-time-v1-[a-f0-9]{64}")))
            AgentTaskRescheduleRequest(name, oldTime, newTime, zoneName, token)
        }.getOrNull()
    }
}

sealed interface AgentTaskRescheduleResult {
    data class Committed(val taskId: String, val verified: Boolean, val systemCancellationFailed: Boolean) : AgentTaskRescheduleResult
    data class Rejected(val reason: String) : AgentTaskRescheduleResult
}

internal class AgentTaskRescheduleTools(private val clock: AgentClock, private val store: AgentTaskStore) {
    private var listedNames: Set<String> = emptySet()
    private var candidate: Pair<String, AgentTaskOneTimeScheduleRecord>? = null
    private var approvedCall: ToolCall? = null

    val definition = ToolDefinition(
        name = TASK_RESCHEDULE_TOOL_NAME,
        description = "修改尚未开始的唯一一次性提醒时间；审批确认原时间、新时间和时区，旧 Run 与已执行实例不变。",
        risk = ToolRisk.REQUIRES_APPROVAL,
        permissionPolicy = ToolPermissionPolicy(supportsBackground = false),
        inputSchema = listOf(
            field("name", "tasks.inspect 返回的精确任务名称。", 100),
            field("expected_planned_at", "tasks.inspect 返回的原时间，原样传递带偏移 ISO-8601 值。", 64),
            field("planned_at", "新时间，带偏移 ISO-8601 值，距当前时间至少 1 分钟且最多 7 天。", 64),
            field("time_zone", "本次审批使用的 IANA 时区，与原时间和新时间的 UTC 偏移一致。", 100),
            field("expected_schedule_token", "tasks.inspect 返回的当前一次性计划指纹，必须原样传递。", 100),
        ),
        businessValidators = listOf(ToolBusinessValidator { arguments ->
            if (AgentTaskRescheduleRequest.parse(arguments) != null) emptyList() else listOf("任务改期参数或时区不合法")
        }),
        ephemeralBusinessValidators = listOf(ToolBusinessValidator { arguments ->
            val request = AgentTaskRescheduleRequest.parse(arguments)
            val current = candidate
            if (request != null && current?.first == request.name && request.matches(current.second)) emptyList()
            else listOf("改期前必须在同一 Run 通过 tasks.list 和 tasks.inspect 唯一读取当前一次性计划")
        }),
        verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
        replaySafety = ToolReplaySafety.RESTART_REQUIRED,
        timeoutMs = 10_000,
    )

    fun clear() {
        listedNames = emptySet()
        candidate = null
        approvedCall = null
    }

    fun listed(tasks: List<AgentTaskRecord>) {
        clear()
        listedNames = tasks.groupingBy { it.name }.eachCount().filterValues { it == 1 }.keys
    }

    fun inspected(name: String, schedule: AgentTaskOneTimeScheduleRecord?) {
        candidate = schedule?.takeIf { name in listedNames }?.let { name to it }
    }

    fun approved(call: ToolCall) {
        approvedCall = call.copy(arguments = call.arguments.toMap())
    }

    suspend fun execute(call: ToolCall, context: AgentToolExecutionContext?): ToolExecutionResult {
        val approved = approvedCall == call
        val request = AgentTaskRescheduleRequest.parse(call.arguments)
        // long: 一次批准只消费一次改期；恢复仅接受 Runtime 已验证并再次批准的原调用，存储层仍核对当前计划指纹。
        clear()
        if (context?.executionOrigin != AgentExecutionOrigin.FOREGROUND || context.invocationSource != AgentInvocationSource.DIRECT) {
            return ToolExecutionResult(false, "提醒改期只允许前台直接 Agent 执行。", false)
        }
        if (!approved || request == null) return ToolExecutionResult(false, "提醒改期缺少有效的逐次审批。", false)
        if (!request.isFutureTimeAllowed(clock.nowMillis())) {
            return ToolExecutionResult(false, "新时间必须距当前时间至少 1 分钟且最多 7 天，请重新确认。", false)
        }
        return when (val result = store.reschedule(request, call.id)) {
            is AgentTaskRescheduleResult.Rejected -> ToolExecutionResult(false, result.reason, false)
            is AgentTaskRescheduleResult.Committed -> ToolExecutionResult(
                success = result.verified,
                verified = result.verified,
                content = if (result.verified) request.resultText(result.systemCancellationFailed)
                else "改期已提交，但当前调度回读发生变化，请查看任务；不能重复执行原调用。",
                executionReceipt = ToolExecutionReceipt(call.id, result.taskId, null, ToolExecutionReceiptStatus.COMMITTED),
            )
        }
    }

    private fun field(name: String, description: String, maxLength: Int) = ToolInputField(
        name = name, description = description, required = true, type = ToolInputType.STRING, minLength = 1, maxLength = maxLength,
    )
}
