package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentTaskRecord
import com.longdev.xiaoling.agent.AgentTaskStore
import com.longdev.xiaoling.automation.ScheduledTaskPolicy

class RoomAgentTaskStore(
    context: Context,
    private val repository: RoomWorkflowRepository = RoomWorkflowRepository(context.applicationContext),
) : AgentTaskStore {
    override suspend fun list(limit: Int): List<AgentTaskRecord> {
        require(limit in 1..10) { "任务清单条数必须在 1 到 10 之间" }
        val workflows = repository.listWorkflows()
            .sortedByDescending { workflow -> workflow.updatedAt }
            .take(limit)
        if (workflows.isEmpty()) return emptyList()

        val workflowIds = workflows.mapTo(hashSetOf()) { workflow -> workflow.id }
        val latestRuns = repository.latestRunsForWorkflows(workflowIds.toList())
            .associateBy { run -> run.workflowId }
        val scheduledTasks = repository.listScheduledTasks()
            .asSequence()
            .filter { task -> task.workflowId in workflowIds && ScheduledTaskPolicy.isUnsettled(task.status) }
            .groupBy { task -> task.workflowId }
            .mapValues { (_, tasks) -> tasks.minBy { task -> task.plannedAt } }
        val schedules = repository.listWorkflowSchedules()
            .asSequence()
            .filter { schedule -> schedule.workflowId in workflowIds && schedule.enabled }
            .associateBy { schedule -> schedule.workflowId }

        return workflows.map { workflow ->
            val schedule = schedules[workflow.id]
            val scheduledTask = scheduledTasks[workflow.id]
            val schedulePlannedAt = schedule?.nextPlannedAt
            val taskPlannedAt = scheduledTask?.plannedAt
            val nextPlannedAt = listOfNotNull(schedulePlannedAt, taskPlannedAt).minOrNull()
            val scheduleType = when (nextPlannedAt) {
                schedulePlannedAt -> schedule?.type?.name
                taskPlannedAt -> scheduledTask?.type?.name
                else -> null
            }
            // long: Agent 只读取用户可理解的任务摘要；Room 内部 Run/Task/Schedule ID、错误详情和步骤输出不进入工具结果。
            AgentTaskRecord(
                name = workflow.name,
                goal = workflow.goal,
                enabled = workflow.enabled,
                stepCount = workflow.steps.size,
                updatedAt = workflow.updatedAt,
                latestRunStatus = latestRuns[workflow.id]?.status?.name,
                scheduleType = scheduleType,
                nextPlannedAt = nextPlannedAt,
            )
        }
    }
}
