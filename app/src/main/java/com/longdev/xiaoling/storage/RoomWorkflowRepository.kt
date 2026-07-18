package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.automation.WorkflowDefinitionPolicy
import com.longdev.xiaoling.automation.WorkflowAgentRunStatusPolicy
import com.longdev.xiaoling.automation.WorkflowScheduleCancellation
import com.longdev.xiaoling.automation.WorkflowSchedulePlan
import com.longdev.xiaoling.automation.WorkflowSchedulePolicy
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.ScheduledTaskPolicy
import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import com.longdev.xiaoling.data.WorkflowEntity
import com.longdev.xiaoling.data.WorkflowRunEntity
import com.longdev.xiaoling.data.WorkflowStepEntity
import com.longdev.xiaoling.data.WorkflowScheduleEntity
import com.longdev.xiaoling.data.ScheduledTaskEntity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.MessageEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.MessageOrigin
import java.time.ZoneId
import java.util.UUID

class RoomWorkflowRepository(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) {
    suspend fun listWorkflows(): List<WorkflowRecord> {
        return database.workflowDao().listWorkflows().map { it.toRecord() }
    }

    suspend fun createWorkflow(name: String, goal: String): WorkflowRecord {
        val normalizedName = name.trim()
        val normalizedGoal = goal.trim()
        WorkflowDefinitionPolicy.validate(normalizedName, normalizedGoal)
        val now = System.currentTimeMillis()
        val workflow = WorkflowEntity(
            id = "workflow-${UUID.randomUUID()}",
            name = normalizedName,
            goal = normalizedGoal,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        database.workflowDao().upsertWorkflow(workflow)
        return workflow.toRecord()
    }

    suspend fun setEnabled(workflowId: String, enabled: Boolean): WorkflowRecord? {
        return database.withTransaction {
            val dao = database.workflowDao()
            val now = System.currentTimeMillis()
            if (dao.setWorkflowEnabled(workflowId, enabled, now) == 0) return@withTransaction null
            if (!enabled) {
                // long: 停用工作流同时关闭周期规则，但保留当前待执行任务给调用方取消系统队列；Worker 即使抢先启动也会因工作流停用而拒绝领取。
                dao.disableWorkflowScheduleForWorkflow(workflowId, now)
            }
            dao.getWorkflow(workflowId)?.toRecord()
        }
    }

    suspend fun createManualRun(workflowId: String, conversationId: String): WorkflowRunDetail {
        // long: 定义校验、Run 和首个步骤必须在同一事务内建立；否则进程中断可能留下没有步骤的 Run，后续既无法展示也无法安全对账。
        return database.withTransaction {
            val dao = database.workflowDao()
            val workflow = dao.getWorkflow(workflowId) ?: error("工作流不存在：$workflowId")
            require(workflow.enabled) { "工作流已停用，不能执行" }
            require(dao.getActiveRun(workflowId) == null) { "这个工作流已有未完成的 Run" }
            val now = System.currentTimeMillis()
            val run = WorkflowRunEntity(
                id = "workflow-run-${UUID.randomUUID()}",
                workflowId = workflowId,
                trigger = WorkflowTrigger.MANUAL.name,
                scheduledTaskId = null,
                plannedAt = null,
                conversationId = conversationId,
                agentRunId = null,
                status = WorkflowRunStatus.QUEUED.name,
                result = null,
                errorMessage = null,
                createdAt = now,
                startedAt = null,
                completedAt = null,
            )
            val step = WorkflowStepEntity(
                id = "workflow-step-${UUID.randomUUID()}",
                workflowRunId = run.id,
                sequence = 1,
                type = AGENT_RUN_STEP_TYPE,
                status = WorkflowStepStatus.PENDING.name,
                title = "执行 Agent 目标",
                detail = workflow.goal,
                agentRunId = null,
                result = null,
                errorMessage = null,
                createdAt = now,
                startedAt = null,
                completedAt = null,
            )
            dao.upsertRun(run)
            dao.upsertStep(step)
            WorkflowRunDetail(run.toRecord(), listOf(step.toRecord()))
        }
    }

    suspend fun listScheduledTasks(): List<ScheduledTaskRecord> {
        return database.workflowDao().listScheduledTasks().map { it.toRecord() }
    }

    suspend fun listWorkflowSchedules(): List<WorkflowScheduleRecord> {
        return database.workflowDao().listWorkflowSchedules().map { it.toRecord() }
    }

    suspend fun getWorkflow(workflowId: String): WorkflowRecord? {
        return database.workflowDao().getWorkflow(workflowId)?.toRecord()
    }

    suspend fun getScheduledTask(taskId: String): ScheduledTaskRecord? {
        return database.workflowDao().getScheduledTask(taskId)?.toRecord()
    }

    suspend fun createOneTimeScheduledTask(workflowId: String, delayMinutes: Int): ScheduledTaskRecord {
        val now = System.currentTimeMillis()
        val plannedAt = ScheduledTaskPolicy.plannedAt(now, delayMinutes)
        return database.withTransaction {
            val dao = database.workflowDao()
            val workflow = dao.getWorkflow(workflowId) ?: error("工作流不存在：$workflowId")
            require(workflow.enabled) { "工作流已停用，不能创建计划" }
            val task = ScheduledTaskEntity(
                id = "scheduled-task-${UUID.randomUUID()}",
                workflowId = workflowId,
                type = ScheduledTaskType.ONE_TIME.name,
                scheduleId = null,
                status = ScheduledTaskStatus.SCHEDULED.name,
                plannedAt = plannedAt,
                workRequestId = null,
                workflowRunId = null,
                actualStartedAt = null,
                completedAt = null,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsertScheduledTask(task)
            task.toRecord()
        }
    }

    suspend fun createOrReplaceWorkflowSchedule(
        workflowId: String,
        type: WorkflowScheduleType,
        hour: Int,
        minute: Int,
        dayOfWeek: Int?,
        zoneId: String = ZoneId.systemDefault().id,
    ): WorkflowSchedulePlan {
        require(hour in 0..23) { "周期小时必须在 0 到 23 之间" }
        require(minute in 0..59) { "周期分钟必须在 0 到 59 之间" }
        val timeOfDayMinutes = Math.addExact(Math.multiplyExact(hour, 60), minute)
        WorkflowSchedulePolicy.validate(type, timeOfDayMinutes, dayOfWeek, zoneId)
        return database.withTransaction {
            val dao = database.workflowDao()
            val workflow = dao.getWorkflow(workflowId) ?: error("工作流不存在：$workflowId")
            require(workflow.enabled) { "工作流已停用，不能创建周期计划" }
            val now = System.currentTimeMillis()
            val existing = dao.getWorkflowScheduleByWorkflowId(workflowId)
            val replacedTask = existing?.nextTaskId
                ?.let { taskId -> dao.getScheduledTask(taskId) }
                ?.takeIf { it.status == ScheduledTaskStatus.SCHEDULED.name }
            replacedTask?.let { task ->
                dao.upsertScheduledTask(
                    task.copy(
                        status = ScheduledTaskStatus.CANCELLED.name,
                        completedAt = now,
                        errorMessage = "周期规则已更新",
                        updatedAt = now,
                    ),
                )
            }
            val plannedAt = WorkflowSchedulePolicy.nextPlannedAt(now, type, timeOfDayMinutes, dayOfWeek, zoneId)
            val task = recurringTask(workflowId, existing?.id ?: "workflow-schedule-${UUID.randomUUID()}", plannedAt, now)
            val schedule = WorkflowScheduleEntity(
                id = task.scheduleId!!,
                workflowId = workflowId,
                type = type.name,
                timeOfDayMinutes = timeOfDayMinutes,
                dayOfWeek = dayOfWeek,
                zoneId = zoneId,
                enabled = true,
                nextTaskId = task.id,
                nextPlannedAt = task.plannedAt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            dao.upsertScheduledTask(task)
            dao.upsertWorkflowSchedule(schedule)
            WorkflowSchedulePlan(schedule.toRecord(), task.toRecord(), replacedTask?.id)
        }
    }

    suspend fun cancelWorkflowSchedule(scheduleId: String): WorkflowScheduleCancellation? {
        return database.withTransaction {
            val dao = database.workflowDao()
            val schedule = dao.getWorkflowSchedule(scheduleId) ?: return@withTransaction null
            val now = System.currentTimeMillis()
            val pendingTask = schedule.nextTaskId
                ?.let { taskId -> dao.getScheduledTask(taskId) }
                ?.takeIf { it.status == ScheduledTaskStatus.SCHEDULED.name }
            pendingTask?.let { task ->
                dao.upsertScheduledTask(
                    task.copy(
                        status = ScheduledTaskStatus.CANCELLED.name,
                        completedAt = now,
                        errorMessage = "周期计划已停用",
                        updatedAt = now,
                    ),
                )
            }
            val updated = schedule.copy(
                enabled = false,
                nextTaskId = null,
                nextPlannedAt = null,
                updatedAt = now,
            )
            dao.upsertWorkflowSchedule(updated)
            WorkflowScheduleCancellation(updated.toRecord(), pendingTask?.id)
        }
    }

    suspend fun materializeNextOccurrence(completedTaskId: String): ScheduledTaskRecord? {
        return database.withTransaction {
            val dao = database.workflowDao()
            val completedTask = dao.getScheduledTask(completedTaskId) ?: return@withTransaction null
            val scheduleId = completedTask.scheduleId ?: return@withTransaction null
            val schedule = dao.getWorkflowSchedule(scheduleId) ?: return@withTransaction null
            if (!schedule.enabled || schedule.nextTaskId != completedTask.id) return@withTransaction null
            if (completedTask.status !in TERMINAL_SCHEDULED_TASK_STATUSES.map { it.name }) return@withTransaction null
            val workflow = dao.getWorkflow(schedule.workflowId)
            if (workflow == null || !workflow.enabled) {
                dao.upsertWorkflowSchedule(
                    schedule.copy(enabled = false, nextTaskId = null, nextPlannedAt = null, updatedAt = System.currentTimeMillis()),
                )
                return@withTransaction null
            }
            val now = System.currentTimeMillis()
            val referenceTime = maxOf(now, completedTask.plannedAt)
            val nextPlannedAt = WorkflowSchedulePolicy.nextPlannedAt(
                now = referenceTime,
                type = schedule.toRecord().type,
                timeOfDayMinutes = schedule.timeOfDayMinutes,
                dayOfWeek = schedule.dayOfWeek,
                zoneId = schedule.zoneId,
            )
            val nextTask = recurringTask(schedule.workflowId, schedule.id, nextPlannedAt, now)
            dao.upsertScheduledTask(nextTask)
            dao.upsertWorkflowSchedule(
                schedule.copy(nextTaskId = nextTask.id, nextPlannedAt = nextTask.plannedAt, updatedAt = now),
            )
            nextTask.toRecord()
        }
    }

    suspend fun reconcileWorkflowSchedules(): List<ScheduledTaskRecord> {
        val schedules = database.workflowDao().listWorkflowSchedules().filter { it.enabled }
        val tasksToEnqueue = mutableListOf<ScheduledTaskRecord>()
        schedules.forEach { schedule ->
            val currentTask = schedule.nextTaskId?.let { database.workflowDao().getScheduledTask(it) }
            when {
                currentTask == null -> materializeMissingOccurrence(schedule.id)?.let(tasksToEnqueue::add)
                currentTask.status == ScheduledTaskStatus.SCHEDULED.name && currentTask.workRequestId == null -> {
                    tasksToEnqueue += currentTask.toRecord()
                }
                currentTask.status in TERMINAL_SCHEDULED_TASK_STATUSES.map { it.name } -> {
                    materializeNextOccurrence(currentTask.id)?.let(tasksToEnqueue::add)
                }
            }
        }
        return tasksToEnqueue
    }

    suspend fun reconcileInterruptedScheduledTasks(): Int {
        val dao = database.workflowDao()
        var reconciled = 0
        dao.getRunningScheduledTasks().forEach { task ->
            val workflowRun = task.workflowRunId?.let { runId -> dao.getRun(runId) }
            val terminalStatus = when (workflowRun?.status) {
                WorkflowRunStatus.COMPLETED.name -> ScheduledTaskStatus.COMPLETED
                WorkflowRunStatus.BLOCKED.name -> ScheduledTaskStatus.BLOCKED
                WorkflowRunStatus.CANCELLED.name -> ScheduledTaskStatus.CANCELLED
                WorkflowRunStatus.FAILED.name -> ScheduledTaskStatus.FAILED
                else -> ScheduledTaskStatus.FAILED
            }
            val error = workflowRun?.errorMessage
                ?: "应用重启后无法恢复后台执行栈"
            // long: 启动恢复只依据已收敛的 Workflow Run 关闭旧实例，不重放模型或工具；周期规则随后从未来时间继续，避免重复副作用。
            finishScheduledTask(
                task.id,
                terminalStatus,
                error.takeIf { terminalStatus != ScheduledTaskStatus.COMPLETED },
            )
            reconciled += 1
        }
        return reconciled
    }

    private suspend fun materializeMissingOccurrence(scheduleId: String): ScheduledTaskRecord? {
        return database.withTransaction {
            val dao = database.workflowDao()
            val schedule = dao.getWorkflowSchedule(scheduleId) ?: return@withTransaction null
            if (!schedule.enabled || schedule.nextTaskId != null) return@withTransaction null
            val workflow = dao.getWorkflow(schedule.workflowId)
            if (workflow == null || !workflow.enabled) {
                dao.upsertWorkflowSchedule(schedule.copy(enabled = false, updatedAt = System.currentTimeMillis()))
                return@withTransaction null
            }
            val now = System.currentTimeMillis()
            val plannedAt = WorkflowSchedulePolicy.nextPlannedAt(
                now = now,
                type = schedule.toRecord().type,
                timeOfDayMinutes = schedule.timeOfDayMinutes,
                dayOfWeek = schedule.dayOfWeek,
                zoneId = schedule.zoneId,
            )
            val task = recurringTask(schedule.workflowId, schedule.id, plannedAt, now)
            dao.upsertScheduledTask(task)
            dao.upsertWorkflowSchedule(
                schedule.copy(nextTaskId = task.id, nextPlannedAt = task.plannedAt, updatedAt = now),
            )
            task.toRecord()
        }
    }

    private fun recurringTask(
        workflowId: String,
        scheduleId: String,
        plannedAt: Long,
        now: Long,
    ) = ScheduledTaskEntity(
        id = "scheduled-task-${UUID.randomUUID()}",
        workflowId = workflowId,
        type = ScheduledTaskType.RECURRING.name,
        scheduleId = scheduleId,
        status = ScheduledTaskStatus.SCHEDULED.name,
        plannedAt = plannedAt,
        workRequestId = null,
        workflowRunId = null,
        actualStartedAt = null,
        completedAt = null,
        errorMessage = null,
        createdAt = now,
        updatedAt = now,
    )

    suspend fun attachWorkRequest(taskId: String, workRequestId: String): ScheduledTaskRecord {
        return database.withTransaction {
            val dao = database.workflowDao()
            val task = dao.getScheduledTask(taskId) ?: error("定时任务不存在：$taskId")
            require(task.status == ScheduledTaskStatus.SCHEDULED.name) { "定时任务已经开始或结束" }
            require(task.workRequestId == null || task.workRequestId == workRequestId) { "定时任务已关联其他 WorkRequest" }
            val updated = task.copy(workRequestId = workRequestId, updatedAt = System.currentTimeMillis())
            dao.upsertScheduledTask(updated)
            updated.toRecord()
        }
    }

    suspend fun failScheduling(taskId: String, reason: String): ScheduledTaskRecord? {
        return finishScheduledTask(taskId, ScheduledTaskStatus.FAILED, reason)
    }

    suspend fun cancelScheduledTask(taskId: String): ScheduledTaskRecord? {
        return database.withTransaction {
            val dao = database.workflowDao()
            val task = dao.getScheduledTask(taskId) ?: return@withTransaction null
            if (task.status != ScheduledTaskStatus.SCHEDULED.name) return@withTransaction task.toRecord()
            val now = System.currentTimeMillis()
            val updated = task.copy(
                status = ScheduledTaskStatus.CANCELLED.name,
                completedAt = now,
                updatedAt = now,
            )
            dao.upsertScheduledTask(updated)
            updated.toRecord()
        }
    }

    suspend fun claimScheduledRun(taskId: String): ScheduledWorkflowClaim? {
        // long: ScheduledTask、Workflow Run、首步和会话锚点一次提交；Worker 即使随后被系统终止，也不会留下无法追溯到计划时间的孤立 Agent Run。
        return database.withTransaction {
            val dao = database.workflowDao()
            val task = dao.getScheduledTask(taskId) ?: return@withTransaction null
            if (task.status != ScheduledTaskStatus.SCHEDULED.name) return@withTransaction null
            val now = System.currentTimeMillis()
            val workflow = dao.getWorkflow(task.workflowId)
            val unavailableReason = when {
                workflow == null -> "工作流不存在"
                !workflow.enabled -> "工作流已停用"
                dao.getActiveRun(task.workflowId) != null -> "工作流已有未完成的 Run"
                else -> null
            }
            if (unavailableReason != null || workflow == null) {
                dao.upsertScheduledTask(
                    task.copy(
                        status = ScheduledTaskStatus.FAILED.name,
                        actualStartedAt = now,
                        completedAt = now,
                        errorMessage = unavailableReason,
                        updatedAt = now,
                    ),
                )
                return@withTransaction null
            }

            val conversationId = "conversation-scheduled-${UUID.randomUUID()}"
            val userMessageId = "message-scheduled-${UUID.randomUUID()}"
            val run = WorkflowRunEntity(
                id = "workflow-run-${UUID.randomUUID()}",
                workflowId = workflow.id,
                trigger = WorkflowTrigger.SCHEDULED.name,
                scheduledTaskId = task.id,
                plannedAt = task.plannedAt,
                conversationId = conversationId,
                agentRunId = null,
                status = WorkflowRunStatus.QUEUED.name,
                result = null,
                errorMessage = null,
                createdAt = now,
                startedAt = now,
                completedAt = null,
            )
            val step = WorkflowStepEntity(
                id = "workflow-step-${UUID.randomUUID()}",
                workflowRunId = run.id,
                sequence = 1,
                type = AGENT_RUN_STEP_TYPE,
                status = WorkflowStepStatus.PENDING.name,
                title = "后台执行 Agent 目标",
                detail = workflow.goal,
                agentRunId = null,
                result = null,
                errorMessage = null,
                createdAt = now,
                startedAt = null,
                completedAt = null,
            )
            val updatedTask = task.copy(
                status = ScheduledTaskStatus.RUNNING.name,
                workflowRunId = run.id,
                actualStartedAt = now,
                updatedAt = now,
            )
            dao.upsertRun(run)
            dao.upsertStep(step)
            dao.upsertScheduledTask(updatedTask)
            database.conversationDao().insertConversations(
                listOf(
                    ConversationEntity(
                        id = conversationId,
                        title = "定时 · ${workflow.name}",
                        summary = "",
                        summaryUntilMessageId = null,
                        summaryUpdatedAt = null,
                        summaryModel = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
            database.conversationDao().insertMessages(
                listOf(backgroundMessage(userMessageId, conversationId, "user", "/agent ${workflow.goal}", now, MessageOrigin.USER)),
            )
            ScheduledWorkflowClaim(
                task = updatedTask.toRecord(),
                workflow = workflow.toRecord(),
                run = WorkflowRunDetail(run.toRecord(), listOf(step.toRecord())),
                userMessageId = userMessageId,
            )
        }
    }

    suspend fun finishScheduledTask(
        taskId: String,
        status: ScheduledTaskStatus,
        errorMessage: String? = null,
    ): ScheduledTaskRecord? {
        require(status in TERMINAL_SCHEDULED_TASK_STATUSES) { "定时任务只能收敛到终态" }
        return database.withTransaction {
            val dao = database.workflowDao()
            val current = dao.getScheduledTask(taskId) ?: return@withTransaction null
            if (current.status in TERMINAL_SCHEDULED_TASK_STATUSES.map { it.name }) return@withTransaction current.toRecord()
            val now = System.currentTimeMillis()
            val updated = current.copy(
                status = status.name,
                completedAt = now,
                errorMessage = errorMessage,
                updatedAt = now,
            )
            dao.upsertScheduledTask(updated)
            updated.toRecord()
        }
    }

    suspend fun appendScheduledConversationResult(
        conversationId: String,
        text: String,
        origin: MessageOrigin,
        verifiedAgentContext: String? = null,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val conversation = database.conversationDao().getConversation(conversationId) ?: return@withTransaction
            database.conversationDao().insertMessages(
                listOf(
                    backgroundMessage(
                        id = "message-scheduled-${UUID.randomUUID()}",
                        conversationId = conversationId,
                        role = if (origin == MessageOrigin.AGENT_RESULT) "assistant" else "error",
                        text = text,
                        createdAt = now,
                        origin = origin,
                        verifiedAgentContext = verifiedAgentContext,
                    ),
                ),
            )
            database.conversationDao().insertConversations(listOf(conversation.copy(updatedAt = now)))
        }
    }

    suspend fun markAgentRunStarted(workflowRunId: String, agentRunId: String): WorkflowRunRecord {
        return database.withTransaction {
            val dao = database.workflowDao()
            val current = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            if (current.status in TERMINAL_RUN_STATUSES) return@withTransaction current.toRecord()
            require(current.agentRunId == null || current.agentRunId == agentRunId) {
                "工作流 Run 已关联其他 Agent Run，不能重复执行"
            }
            val now = System.currentTimeMillis()
            val updated = current.copy(
                agentRunId = agentRunId,
                status = WorkflowRunStatus.RUNNING.name,
                startedAt = current.startedAt ?: now,
            )
            val step = dao.getSteps(workflowRunId).single()
            // long: 当前前台与后台工作流都只有一个 Agent 步骤；重复快照只能刷新同一关联，不能创建第二个执行或覆盖已有结果。
            dao.upsertRun(updated)
            dao.upsertStep(
                step.copy(
                    status = WorkflowStepStatus.RUNNING.name,
                    agentRunId = agentRunId,
                    startedAt = step.startedAt ?: now,
                ),
            )
            updated.toRecord()
        }
    }

    suspend fun completeRun(
        workflowRunId: String,
        status: WorkflowRunStatus,
        result: String? = null,
        errorMessage: String? = null,
    ): WorkflowRunRecord {
        require(status.name in TERMINAL_RUN_STATUSES) { "工作流 Run 只能收敛到终态" }
        // long: Run 与步骤共享一次终态提交，避免任务中心看到 Run 已完成但步骤仍在运行；重复回调只返回首次写入的终态结果。
        return database.withTransaction {
            val dao = database.workflowDao()
            val current = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            if (current.status in TERMINAL_RUN_STATUSES) return@withTransaction current.toRecord()
            val now = System.currentTimeMillis()
            val updated = current.copy(
                status = status.name,
                result = result,
                errorMessage = errorMessage,
                completedAt = now,
            )
            val stepStatus = when (status) {
                WorkflowRunStatus.COMPLETED -> WorkflowStepStatus.COMPLETED
                WorkflowRunStatus.BLOCKED -> WorkflowStepStatus.BLOCKED
                WorkflowRunStatus.CANCELLED -> WorkflowStepStatus.CANCELLED
                WorkflowRunStatus.FAILED -> WorkflowStepStatus.FAILED
                else -> error("非终态不能完成工作流步骤")
            }
            val step = dao.getSteps(workflowRunId).single()
            dao.upsertRun(updated)
            dao.upsertStep(
                step.copy(
                    status = stepStatus.name,
                    result = result,
                    errorMessage = errorMessage,
                    completedAt = now,
                ),
            )
            updated.toRecord()
        }
    }

    suspend fun completeByAgentRunId(
        agentRunId: String,
        status: WorkflowRunStatus,
        result: String? = null,
        errorMessage: String? = null,
    ): WorkflowRunRecord? {
        val workflowRun = database.workflowDao().getRunByAgentRunId(agentRunId) ?: return null
        return completeRun(workflowRun.id, status, result, errorMessage)
    }

    suspend fun recentRunDetails(limit: Int = 50): List<WorkflowRunDetail> {
        val dao = database.workflowDao()
        return loadRunDetails(dao.recentRuns(limit))
    }

    suspend fun allRunDetails(): List<WorkflowRunDetail> {
        return loadRunDetails(database.workflowDao().listRuns())
    }

    private suspend fun loadRunDetails(runs: List<WorkflowRunEntity>): List<WorkflowRunDetail> {
        val dao = database.workflowDao()
        if (runs.isEmpty()) return emptyList()
        val stepsByRunId = dao.getStepsForRuns(runs.map { it.id }).groupBy { it.workflowRunId }
        return runs.map { run ->
            WorkflowRunDetail(
                run = run.toRecord(),
                steps = stepsByRunId[run.id].orEmpty().map { it.toRecord() },
            )
        }
    }

    suspend fun reconcileInterruptedRuns(): Int {
        val dao = database.workflowDao()
        val active = dao.runsByStatuses(listOf(WorkflowRunStatus.QUEUED.name, WorkflowRunStatus.RUNNING.name))
        var reconciled = 0
        active.forEach { workflowRun ->
            val agentRun = workflowRun.agentRunId?.let { database.agentRunDao().getRun(it) }
            when {
                agentRun == null -> {
                    completeRun(
                        workflowRun.id,
                        WorkflowRunStatus.FAILED,
                        errorMessage = "应用重启前未能恢复关联的 Agent Run",
                    )
                    reconciled += 1
                }
                agentRun.status == AgentRunStatus.WAITING_APPROVAL.name -> Unit
                else -> {
                    val agentStatus = runCatching { AgentRunStatus.valueOf(agentRun.status) }.getOrNull()
                    val workflowStatus = agentStatus?.let(WorkflowAgentRunStatusPolicy::terminalStatus)
                        ?: WorkflowRunStatus.FAILED
                    // long: 进程重建后旧协程不存在；除明确可恢复的审批等待外，活动状态按失败收敛，绝不重新执行可能有副作用的步骤。
                    completeRun(
                        workflowRun.id,
                        workflowStatus,
                        result = agentRun.result.takeIf { workflowStatus == WorkflowRunStatus.COMPLETED },
                        errorMessage = agentRun.errorMessage
                            ?: "应用重启后无法恢复工作流执行栈".takeIf { workflowStatus != WorkflowRunStatus.COMPLETED },
                    )
                    reconciled += 1
                }
            }
        }
        return reconciled
    }

    private fun WorkflowEntity.toRecord() = WorkflowRecord(id, name, goal, enabled, createdAt, updatedAt)

    private fun WorkflowRunEntity.toRecord() = WorkflowRunRecord(
        id = id,
        workflowId = workflowId,
        trigger = runCatching { WorkflowTrigger.valueOf(trigger) }.getOrDefault(WorkflowTrigger.MANUAL),
        scheduledTaskId = scheduledTaskId,
        plannedAt = plannedAt,
        conversationId = conversationId,
        agentRunId = agentRunId,
        status = runCatching { WorkflowRunStatus.valueOf(status) }.getOrDefault(WorkflowRunStatus.FAILED),
        result = result,
        errorMessage = errorMessage,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
    )

    private fun WorkflowStepEntity.toRecord() = WorkflowStepRecord(
        id = id,
        workflowRunId = workflowRunId,
        sequence = sequence,
        type = type,
        status = runCatching { WorkflowStepStatus.valueOf(status) }.getOrDefault(WorkflowStepStatus.FAILED),
        title = title,
        detail = detail,
        agentRunId = agentRunId,
        result = result,
        errorMessage = errorMessage,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
    )

    private fun ScheduledTaskEntity.toRecord() = ScheduledTaskRecord(
        id = id,
        workflowId = workflowId,
        type = runCatching { ScheduledTaskType.valueOf(type) }.getOrDefault(ScheduledTaskType.ONE_TIME),
        scheduleId = scheduleId,
        status = runCatching { ScheduledTaskStatus.valueOf(status) }.getOrDefault(ScheduledTaskStatus.FAILED),
        plannedAt = plannedAt,
        workRequestId = workRequestId,
        workflowRunId = workflowRunId,
        actualStartedAt = actualStartedAt,
        completedAt = completedAt,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun WorkflowScheduleEntity.toRecord() = WorkflowScheduleRecord(
        id = id,
        workflowId = workflowId,
        type = runCatching { WorkflowScheduleType.valueOf(type) }.getOrDefault(WorkflowScheduleType.DAILY),
        timeOfDayMinutes = timeOfDayMinutes,
        dayOfWeek = dayOfWeek,
        zoneId = zoneId,
        enabled = enabled,
        nextTaskId = nextTaskId,
        nextPlannedAt = nextPlannedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        const val AGENT_RUN_STEP_TYPE = "AGENT_RUN"

        private val TERMINAL_RUN_STATUSES = setOf(
            WorkflowRunStatus.COMPLETED.name,
            WorkflowRunStatus.BLOCKED.name,
            WorkflowRunStatus.FAILED.name,
            WorkflowRunStatus.CANCELLED.name,
        )

        private val TERMINAL_SCHEDULED_TASK_STATUSES = setOf(
            ScheduledTaskStatus.BLOCKED,
            ScheduledTaskStatus.COMPLETED,
            ScheduledTaskStatus.FAILED,
            ScheduledTaskStatus.CANCELLED,
        )

        private fun backgroundMessage(
            id: String,
            conversationId: String,
            role: String,
            text: String,
            createdAt: Long,
            origin: MessageOrigin,
            verifiedAgentContext: String? = null,
        ) = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            text = text,
            createdAt = createdAt,
            origin = origin.name,
            verifiedAgentContext = verifiedAgentContext,
            providerId = null,
            providerName = null,
            model = null,
            apiMode = null,
            streaming = null,
            requestUrl = null,
            firstTokenLatencyMs = null,
            latencyMs = null,
            promptTokens = null,
            completionTokens = null,
            totalTokens = null,
            finishReason = null,
            errorKind = null,
            errorMessage = null,
        )
    }
}

data class ScheduledWorkflowClaim(
    val task: ScheduledTaskRecord,
    val workflow: WorkflowRecord,
    val run: WorkflowRunDetail,
    val userMessageId: String,
)
