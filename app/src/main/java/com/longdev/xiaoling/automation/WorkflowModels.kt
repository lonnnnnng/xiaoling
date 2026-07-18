package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentRunStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class WorkflowRecord(
    val id: String,
    val name: String,
    val goal: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class WorkflowRunRecord(
    val id: String,
    val workflowId: String,
    val trigger: WorkflowTrigger,
    val scheduledTaskId: String?,
    val plannedAt: Long?,
    val conversationId: String,
    val agentRunId: String?,
    val status: WorkflowRunStatus,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
)

data class WorkflowStepRecord(
    val id: String,
    val workflowRunId: String,
    val sequence: Int,
    val type: String,
    val status: WorkflowStepStatus,
    val title: String,
    val detail: String,
    val agentRunId: String?,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
)

data class WorkflowRunDetail(
    val run: WorkflowRunRecord,
    val steps: List<WorkflowStepRecord>,
)

enum class WorkflowTrigger {
    MANUAL,
    SCHEDULED,
}

enum class WorkflowRunStatus {
    QUEUED,
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class WorkflowStepStatus {
    PENDING,
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object WorkflowAgentRunStatusPolicy {
    fun terminalStatus(agentStatus: AgentRunStatus): WorkflowRunStatus? {
        // long: 前台执行、审批恢复和启动对账必须共享同一终态映射，新增 Agent 状态时不能让两条链路产生不同 Workflow 结论。
        return when (agentStatus) {
            AgentRunStatus.COMPLETED -> WorkflowRunStatus.COMPLETED
            AgentRunStatus.BLOCKED -> WorkflowRunStatus.BLOCKED
            AgentRunStatus.CANCELLED -> WorkflowRunStatus.CANCELLED
            AgentRunStatus.FAILED,
            AgentRunStatus.BUDGET_EXHAUSTED -> WorkflowRunStatus.FAILED
            else -> null
        }
    }
}

data class ScheduledTaskRecord(
    val id: String,
    val workflowId: String,
    val type: ScheduledTaskType,
    val scheduleId: String?,
    val status: ScheduledTaskStatus,
    val plannedAt: Long,
    val workRequestId: String?,
    val workflowRunId: String?,
    val actualStartedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ScheduledTaskType {
    ONE_TIME,
    RECURRING,
}

enum class ScheduledTaskStatus {
    SCHEDULED,
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object ScheduledTaskPolicy {
    const val MIN_DELAY_MINUTES = 1
    const val MAX_DELAY_MINUTES = 7 * 24 * 60

    fun plannedAt(now: Long, delayMinutes: Int): Long {
        require(delayMinutes in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES) {
            "一次性调度延迟必须在 $MIN_DELAY_MINUTES 到 $MAX_DELAY_MINUTES 分钟之间"
        }
        return Math.addExact(now, Math.multiplyExact(delayMinutes.toLong(), 60_000L))
    }
}

data class WorkflowScheduleRecord(
    val id: String,
    val workflowId: String,
    val type: WorkflowScheduleType,
    val timeOfDayMinutes: Int,
    val dayOfWeek: Int?,
    val zoneId: String,
    val enabled: Boolean,
    val nextTaskId: String?,
    val nextPlannedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class WorkflowScheduleType {
    DAILY,
    WEEKLY,
}

data class WorkflowSchedulePlan(
    val schedule: WorkflowScheduleRecord,
    val task: ScheduledTaskRecord,
    val replacedTaskId: String?,
)

data class WorkflowScheduleCancellation(
    val schedule: WorkflowScheduleRecord,
    val cancelledTaskId: String?,
)

object WorkflowSchedulePolicy {
    const val MINUTES_PER_DAY = 24 * 60

    fun nextPlannedAt(
        now: Long,
        type: WorkflowScheduleType,
        timeOfDayMinutes: Int,
        dayOfWeek: Int?,
        zoneId: String,
    ): Long {
        validate(type, timeOfDayMinutes, dayOfWeek, zoneId)
        val zone = ZoneId.of(zoneId)
        val current = Instant.ofEpochMilli(now).atZone(zone)
        val time = LocalTime.of(timeOfDayMinutes / 60, timeOfDayMinutes % 60)
        val candidate = when (type) {
            WorkflowScheduleType.DAILY -> current.toLocalDate().atTime(time).atZone(zone)
            WorkflowScheduleType.WEEKLY -> {
                val targetDay = DayOfWeek.of(requireNotNull(dayOfWeek))
                val daysUntilTarget = (targetDay.value - current.dayOfWeek.value + 7) % 7
                current.toLocalDate().plusDays(daysUntilTarget.toLong()).atTime(time).atZone(zone)
            }
        }
        // long: 周期规则表达的是用户所在时区的墙上时间；若本轮时间点已经过去，只推进一个完整周期，避免应用恢复时补跑多次历史任务。
        val next = if (candidate.toInstant().toEpochMilli() > now) {
            candidate
        } else {
            when (type) {
                WorkflowScheduleType.DAILY -> candidate.plusDays(1)
                WorkflowScheduleType.WEEKLY -> candidate.plusWeeks(1)
            }
        }
        return next.toInstant().toEpochMilli()
    }

    fun validate(
        type: WorkflowScheduleType,
        timeOfDayMinutes: Int,
        dayOfWeek: Int?,
        zoneId: String,
    ) {
        require(timeOfDayMinutes in 0 until MINUTES_PER_DAY) { "周期时间必须在 00:00 到 23:59 之间" }
        require(zoneId.isNotBlank()) { "周期时区不能为空" }
        runCatching { ZoneId.of(zoneId) }.getOrElse { error("无效周期时区：$zoneId") }
        when (type) {
            WorkflowScheduleType.DAILY -> require(dayOfWeek == null) { "每日规则不能设置周几" }
            WorkflowScheduleType.WEEKLY -> require(dayOfWeek in 1..7) { "每周规则必须设置周一到周日" }
        }
    }
}

object WorkflowDefinitionPolicy {
    const val MAX_NAME_LENGTH = 80
    const val MAX_GOAL_LENGTH = 2_000

    fun validate(name: String, goal: String) {
        require(name.isNotBlank()) { "工作流名称不能为空" }
        require(name.length <= MAX_NAME_LENGTH) { "工作流名称不能超过 $MAX_NAME_LENGTH 个字符" }
        require(goal.isNotBlank()) { "工作流目标不能为空" }
        require(goal.length <= MAX_GOAL_LENGTH) { "工作流目标不能超过 $MAX_GOAL_LENGTH 个字符" }
    }
}
