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
import com.longdev.xiaoling.automation.WorkflowStartupRecoveryCandidates
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepDefinitionRecord
import com.longdev.xiaoling.automation.WorkflowStepExecutionPolicy
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import com.longdev.xiaoling.data.WorkflowEntity
import com.longdev.xiaoling.data.WorkflowRunEntity
import com.longdev.xiaoling.data.WorkflowStepDefinitionEntity
import com.longdev.xiaoling.data.WorkflowStepEntity
import com.longdev.xiaoling.data.WorkflowScheduleEntity
import com.longdev.xiaoling.data.ScheduledTaskEntity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import com.longdev.xiaoling.model.MessageOrigin
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException

class RoomWorkflowRepository(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) {
    private val messageRepository = MessageRepository(database)
    private val knowledgeDocumentStore = RoomKnowledgeDocumentStore(context.applicationContext, database)

    suspend fun listWorkflows(): List<WorkflowRecord> {
        val dao = database.workflowDao()
        val stepsByWorkflow = dao.listWorkflowStepDefinitions().groupBy { it.workflowId }
        return dao.listWorkflows().map { workflow ->
            workflow.toRecord(stepsByWorkflow[workflow.id].orEmpty())
        }
    }

    suspend fun createWorkflow(name: String, goal: String): WorkflowRecord {
        return createWorkflow(name, listOf(WorkflowStepDefinitionInput(goal)))
    }

    suspend fun createWorkflow(name: String, steps: List<WorkflowStepDefinitionInput>): WorkflowRecord {
        val normalizedName = name.trim()
        val normalizedSteps = steps.map { WorkflowStepDefinitionInput(it.goal.trim()) }
        WorkflowDefinitionPolicy.validate(normalizedName, normalizedSteps)
        return database.withTransaction {
            val dao = database.workflowDao()
            val now = System.currentTimeMillis()
            val workflowId = "workflow-${UUID.randomUUID()}"
            val workflow = WorkflowEntity(
                id = workflowId,
                name = normalizedName,
                // long: legacy goal 继续保存首步目标，兼容旧备份与仍读取该列的版本；多步骤真实定义以 workflow_step_definitions 为准。
                goal = normalizedSteps.first().goal,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            )
            val definitions = normalizedSteps.mapIndexed { index, step ->
                val definitionId = "workflow-definition-step-${UUID.randomUUID()}"
                WorkflowStepDefinitionEntity(
                    id = definitionId,
                    workflowId = workflowId,
                    sequence = index + 1,
                    goal = step.goal,
                    idempotencyKey = definitionId,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            dao.upsertWorkflow(workflow)
            dao.upsertWorkflowStepDefinitions(definitions)
            workflow.toRecord(definitions)
        }
    }

    suspend fun updateWorkflow(
        workflowId: String,
        name: String,
        steps: List<WorkflowStepDefinitionInput>,
    ): WorkflowRecord {
        val normalizedName = name.trim()
        val normalizedSteps = steps.map { WorkflowStepDefinitionInput(it.goal.trim()) }
        WorkflowDefinitionPolicy.validate(normalizedName, normalizedSteps)
        return database.withTransaction {
            val dao = database.workflowDao()
            val current = dao.getWorkflow(workflowId) ?: error("工作流不存在：$workflowId")
            require(dao.getActiveRun(workflowId) == null) { "工作流有未完成的 Run，结束后才能编辑" }
            val now = System.currentTimeMillis()
            val definitions = normalizedSteps.mapIndexed { index, step ->
                val definitionId = "workflow-definition-step-${UUID.randomUUID()}"
                WorkflowStepDefinitionEntity(
                    id = definitionId,
                    workflowId = workflowId,
                    sequence = index + 1,
                    goal = step.goal,
                    idempotencyKey = definitionId,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            val updated = current.copy(
                name = normalizedName,
                // long: 编辑只替换后续 Run 使用的定义；历史 Run 已持有自己的步骤快照，因此不会被定义变化改写。
                goal = normalizedSteps.first().goal,
                updatedAt = now,
            )
            dao.upsertWorkflow(updated)
            dao.deleteWorkflowStepDefinitions(workflowId)
            dao.upsertWorkflowStepDefinitions(definitions)
            updated.toRecord(definitions)
        }
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
            dao.getWorkflow(workflowId)?.toRecord(dao.getWorkflowStepDefinitions(workflowId))
        }
    }

    suspend fun createManualRun(workflowId: String, conversationId: String): WorkflowRunDetail {
        // long: 定义校验、Run 和首个步骤必须在同一事务内建立；否则进程中断可能留下没有步骤的 Run，后续既无法展示也无法安全对账。
        return database.withTransaction {
            val dao = database.workflowDao()
            val workflow = dao.getWorkflow(workflowId) ?: error("工作流不存在：$workflowId")
            require(workflow.enabled) { "工作流已停用，不能执行" }
            require(dao.getActiveRun(workflowId) == null) { "这个工作流已有未完成的 Run" }
            val definitions = dao.getWorkflowStepDefinitions(workflowId)
            require(definitions.isNotEmpty()) { "工作流没有可执行步骤" }
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
                retryOfWorkflowRunId = null,
            )
            val runSteps = definitions.map { definition -> definition.toRunStep(run.id, now, background = false) }
            dao.upsertRun(run)
            runSteps.forEach { dao.upsertStep(it) }
            WorkflowRunDetail(run.toRecord(), runSteps.map { it.toRecord() })
        }
    }

    suspend fun listScheduledTasks(): List<ScheduledTaskRecord> {
        return database.workflowDao().listScheduledTasks().map { it.toRecord() }
    }

    suspend fun listWorkflowSchedules(): List<WorkflowScheduleRecord> {
        return database.workflowDao().listWorkflowSchedules().map { it.toRecord() }
    }

    suspend fun getWorkflow(workflowId: String): WorkflowRecord? {
        val dao = database.workflowDao()
        return dao.getWorkflow(workflowId)?.toRecord(dao.getWorkflowStepDefinitions(workflowId))
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

    suspend fun reconcileInterruptedScheduledTasks(taskIds: Set<String>? = null): Int {
        val dao = database.workflowDao()
        var reconciled = 0
        dao.getRecoverableScheduledTasks()
            .filter { taskIds == null || it.id in taskIds }
            .forEach { task ->
            val workflowRun = task.workflowRunId?.let { runId -> dao.getRun(runId) }
            val persistedWorkflowStatus = workflowRun?.let { run ->
                WorkflowRunStatus.valueOf(run.status).takeIf { it.name in TERMINAL_RUN_STATUSES }
            }
            val stopRequested = task.status == ScheduledTaskStatus.STOP_REQUESTED.name
            val terminalStatus = when {
                persistedWorkflowStatus != null -> persistedWorkflowStatus.toScheduledTaskStatus()
                stopRequested -> ScheduledTaskStatus.CANCELLED
                else -> ScheduledTaskStatus.FAILED
            }
            val error = when {
                persistedWorkflowStatus != null -> workflowRun.errorMessage
                stopRequested -> task.errorMessage ?: "用户已请求停止后台工作流"
                else -> workflowRun?.errorMessage ?: "应用重启后无法恢复后台执行栈"
            }
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

    suspend fun requestScheduledTaskStop(taskId: String, reason: String): ScheduledTaskRecord? {
        require(reason.isNotBlank()) { "停止原因不能为空" }
        return database.withTransaction {
            val dao = database.workflowDao()
            val task = dao.getScheduledTask(taskId) ?: return@withTransaction null
            if (task.status == ScheduledTaskStatus.STOP_REQUESTED.name) return@withTransaction task.toRecord()
            if (task.status != ScheduledTaskStatus.RUNNING.name) return@withTransaction task.toRecord()
            val workflowRun = task.workflowRunId?.let { workflowRunId -> dao.getRun(workflowRunId) }
            val persistedWorkflowStatus = workflowRun?.let { run ->
                WorkflowRunStatus.valueOf(run.status).takeIf { it.name in TERMINAL_RUN_STATUSES }
            }
            if (persistedWorkflowStatus != null) {
                val now = System.currentTimeMillis()
                // long: Workflow 已经持久化终态说明停止请求来晚了；直接修复半结算 Task，不能写入一个无法覆盖既有终态的伪停止栅栏。
                val settled = task.copy(
                    status = persistedWorkflowStatus.toScheduledTaskStatus().name,
                    completedAt = workflowRun.completedAt ?: now,
                    errorMessage = workflowRun.errorMessage,
                    updatedAt = now,
                )
                dao.upsertScheduledTask(settled)
                return@withTransaction settled.toRecord()
            }
            // long: 用户停止意图必须先于系统取消持久化；即使 WorkManager 或即时兜底随后失败，启动恢复仍能识别并继续收敛同一执行链。
            val updated = task.copy(
                status = ScheduledTaskStatus.STOP_REQUESTED.name,
                errorMessage = reason,
                updatedAt = System.currentTimeMillis(),
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
            val definitions = dao.getWorkflowStepDefinitions(workflow.id)
            if (definitions.isEmpty()) {
                dao.upsertScheduledTask(
                    task.copy(
                        status = ScheduledTaskStatus.FAILED.name,
                        actualStartedAt = now,
                        completedAt = now,
                        errorMessage = "工作流没有可执行步骤",
                        updatedAt = now,
                    ),
                )
                return@withTransaction null
            }
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
                retryOfWorkflowRunId = null,
            )
            val runSteps = definitions.map { definition -> definition.toRunStep(run.id, now, background = true) }
            val updatedTask = task.copy(
                status = ScheduledTaskStatus.RUNNING.name,
                workflowRunId = run.id,
                actualStartedAt = now,
                updatedAt = now,
            )
            dao.upsertRun(run)
            runSteps.forEach { dao.upsertStep(it) }
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
            messageRepository.insert(
                listOf(conversationId to backgroundMessage(userMessageId, "user", "/agent ${definitions.first().goal}", now, MessageOrigin.USER)),
            )
            ScheduledWorkflowClaim(
                task = updatedTask.toRecord(),
                workflow = workflow.toRecord(definitions),
                run = WorkflowRunDetail(run.toRecord(), runSteps.map { it.toRecord() }),
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
            val stopRequested = current.status == ScheduledTaskStatus.STOP_REQUESTED.name
            val settledStatus = if (stopRequested) ScheduledTaskStatus.CANCELLED else status
            val updated = current.copy(
                // long: STOP_REQUESTED 是持久化取消栅栏；迟到 Worker 即使返回成功，也只能把 Task 收敛为 CANCELLED，不能覆盖用户停止意图。
                status = settledStatus.name,
                completedAt = now,
                errorMessage = if (stopRequested) current.errorMessage else errorMessage,
                updatedAt = now,
            )
            dao.upsertScheduledTask(updated)
            updated.toRecord()
        }
    }

    suspend fun settleScheduledWorkflowRun(
        taskId: String,
        workflowRunId: String,
        workflowStatus: WorkflowRunStatus,
        taskStatus: ScheduledTaskStatus,
        result: String? = null,
        errorMessage: String? = null,
    ): ScheduledTaskRecord? {
        require(workflowStatus.name in TERMINAL_RUN_STATUSES) { "工作流 Run 只能收敛到终态" }
        require(taskStatus in TERMINAL_SCHEDULED_TASK_STATUSES) { "定时任务只能收敛到终态" }
        return database.withTransaction {
            val dao = database.workflowDao()
            val task = dao.getScheduledTask(taskId)
                ?: error("定时任务不存在：$taskId")
            require(task.workflowRunId == workflowRunId) { "定时任务与工作流 Run 关联不一致" }
            if (task.status in TERMINAL_SCHEDULED_TASK_STATUSES.map { it.name }) {
                return@withTransaction task.toRecord()
            }
            val workflowRun = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            val persistedWorkflowStatus = WorkflowRunStatus.valueOf(workflowRun.status)
                .takeIf { it.name in TERMINAL_RUN_STATUSES }
            val stopRequested = task.status == ScheduledTaskStatus.STOP_REQUESTED.name
            val settledWorkflowStatus = when {
                persistedWorkflowStatus != null -> persistedWorkflowStatus
                stopRequested -> WorkflowRunStatus.CANCELLED
                else -> workflowStatus
            }
            val settledTaskStatus = when {
                persistedWorkflowStatus != null -> persistedWorkflowStatus.toScheduledTaskStatus()
                stopRequested -> ScheduledTaskStatus.CANCELLED
                else -> taskStatus
            }
            val settledResult = when {
                persistedWorkflowStatus != null -> workflowRun.result
                stopRequested -> null
                else -> result
            }
            val settledError = when {
                persistedWorkflowStatus != null -> workflowRun.errorMessage
                stopRequested -> task.errorMessage ?: "用户已请求停止后台工作流"
                else -> errorMessage
            }
            if (persistedWorkflowStatus != null) {
                val now = System.currentTimeMillis()
                // long: Workflow 已先持久化终态时，它是半结算链的事实源；直接在当前事务修复活动 Task，不能再经过通用停止栅栏把两者改成不同终态。
                val settledTask = task.copy(
                    status = settledTaskStatus.name,
                    completedAt = workflowRun.completedAt ?: now,
                    errorMessage = settledError,
                    updatedAt = now,
                )
                dao.upsertScheduledTask(settledTask)
                return@withTransaction settledTask.toRecord()
            }
            // long: Workflow 与 Task 必须在同一事务内重读停止栅栏和既有终态；旧版半结算或迟到 Worker 都不能制造互相矛盾的最终状态。
            completeRun(
                workflowRunId = workflowRunId,
                status = settledWorkflowStatus,
                result = settledResult,
                errorMessage = settledError,
            )
            finishScheduledTask(taskId, settledTaskStatus, settledError)
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
            messageRepository.insert(
                listOf(
                    conversationId to backgroundMessage(
                        id = "message-scheduled-${UUID.randomUUID()}",
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

    suspend fun completeScheduledWorkflowStep(
        taskId: String,
        workflowRunId: String,
        workflowStepId: String,
        result: String,
        knowledgeReferences: List<KnowledgeReference> = emptyList(),
        requiresCurrentKnowledgeReferences: Boolean = false,
        verifiedAgentContext: String? = null,
    ): WorkflowStepRecord {
        return database.withTransaction {
            val dao = database.workflowDao()
            val task = dao.getScheduledTask(taskId) ?: error("定时任务不存在：$taskId")
            require(task.workflowRunId == workflowRunId) { "定时任务与工作流 Run 关联不一致" }
            if (task.status == ScheduledTaskStatus.STOP_REQUESTED.name) {
                throw CancellationException(task.errorMessage ?: "用户已请求停止后台工作流")
            }
            require(task.status == ScheduledTaskStatus.RUNNING.name) { "定时任务未处于运行状态" }
            val run = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            // long: 步骤成功与会话结果必须共享同一个停止栅栏；用户停止不能插入两次写入之间，让已取消 Workflow 留下一条迟到成功消息。
            val completed = completeWorkflowStep(
                workflowRunId = workflowRunId,
                workflowStepId = workflowStepId,
                status = WorkflowStepStatus.COMPLETED,
                result = result,
                knowledgeReferences = knowledgeReferences,
                requiresCurrentKnowledgeReferences = requiresCurrentKnowledgeReferences,
            )
            appendScheduledConversationResult(
                conversationId = run.conversationId,
                text = result,
                origin = MessageOrigin.AGENT_RESULT,
                verifiedAgentContext = verifiedAgentContext,
            )
            completed
        }
    }

    suspend fun appendScheduledStepPrompt(conversationId: String, goal: String): String {
        val messageId = "message-scheduled-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        database.withTransaction {
            val conversation = database.conversationDao().getConversation(conversationId)
                ?: error("后台工作流会话不存在：$conversationId")
            messageRepository.insert(
                listOf(conversationId to backgroundMessage(messageId, "user", "/agent $goal", now, MessageOrigin.USER)),
            )
            database.conversationDao().insertConversations(listOf(conversation.copy(updatedAt = now)))
        }
        return messageId
    }

    suspend fun markAgentRunStarted(workflowRunId: String, agentRunId: String): WorkflowRunRecord {
        val steps = database.workflowDao().getSteps(workflowRunId).map { it.toRecord() }
        val step = steps.firstOrNull { it.agentRunId == agentRunId }
            ?: WorkflowStepExecutionPolicy.nextExecutableStep(steps)
            ?: error("工作流没有可执行步骤")
        // long: 旧单步骤调用方可能随 Agent 快照重复回调同一 Run ID；优先找已关联步骤，确保幂等刷新不会被 RUNNING 状态误判为越序执行。
        return markAgentRunStarted(workflowRunId, step.id, agentRunId)
    }

    suspend fun prepareWorkflowStep(workflowRunId: String, workflowStepId: String): WorkflowStepRecord {
        return database.withTransaction {
            val dao = database.workflowDao()
            val run = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            require(run.status !in TERMINAL_RUN_STATUSES) { "工作流 Run 已结束" }
            val steps = dao.getSteps(workflowRunId)
            val step = steps.firstOrNull { it.id == workflowStepId } ?: error("工作流步骤不存在：$workflowStepId")
            val expected = WorkflowStepExecutionPolicy.nextExecutableStep(steps.map { it.toRecord() })
            require(expected?.id == step.id) { "工作流步骤必须按顺序准备" }
            val previousOutputs = currentPreviousOutputs(steps, step.sequence)
            val updated = step.copy(
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(step.detail, previousOutputs),
            )
            dao.upsertStep(updated)
            updated.toRecord()
        }
    }

    suspend fun markAgentRunStarted(
        workflowRunId: String,
        workflowStepId: String,
        agentRunId: String,
    ): WorkflowRunRecord {
        return database.withTransaction {
            val dao = database.workflowDao()
            val current = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            if (current.status in TERMINAL_RUN_STATUSES) return@withTransaction current.toRecord()
            val steps = dao.getSteps(workflowRunId)
            val step = steps.firstOrNull { it.id == workflowStepId } ?: error("工作流步骤不存在：$workflowStepId")
            val expected = WorkflowStepExecutionPolicy.nextExecutableStep(steps.map { it.toRecord() })
            require(expected?.id == step.id || step.agentRunId == agentRunId) { "工作流步骤必须按顺序执行" }
            require(step.agentRunId == null || step.agentRunId == agentRunId) { "工作流步骤已关联其他 Agent Run" }
            val now = System.currentTimeMillis()
            val previousOutputs = currentPreviousOutputs(steps, step.sequence)
            val updated = current.copy(
                agentRunId = agentRunId,
                status = WorkflowRunStatus.RUNNING.name,
                startedAt = current.startedAt ?: now,
            )
            // long: 每个步骤只能关联一个 Agent Run；输入快照在真正启动时冻结前序输出，后续定义编辑或页面刷新都不能改变本次执行上下文。
            dao.upsertRun(updated)
            dao.upsertStep(
                step.copy(
                    status = WorkflowStepStatus.RUNNING.name,
                    agentRunId = agentRunId,
                    startedAt = step.startedAt ?: now,
                    inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(step.detail, previousOutputs),
                ),
            )
            updated.toRecord()
        }
    }

    suspend fun completeWorkflowStep(
        workflowRunId: String,
        workflowStepId: String,
        status: WorkflowStepStatus,
        result: String? = null,
        errorMessage: String? = null,
        knowledgeReferences: List<KnowledgeReference> = emptyList(),
        requiresCurrentKnowledgeReferences: Boolean = false,
    ): WorkflowStepRecord {
        require(status in TERMINAL_STEP_STATUSES) { "工作流步骤只能收敛到终态" }
        return database.withTransaction {
            val dao = database.workflowDao()
            val run = dao.getRun(workflowRunId) ?: error("工作流 Run 不存在：$workflowRunId")
            require(run.status !in TERMINAL_RUN_STATUSES) { "工作流 Run 已结束" }
            val step = dao.getStep(workflowStepId) ?: error("工作流步骤不存在：$workflowStepId")
            require(step.workflowRunId == workflowRunId) { "工作流步骤不属于当前 Run" }
            if (step.status in TERMINAL_STEP_STATUSES.map { it.name }) return@withTransaction step.toRecord()
            val now = System.currentTimeMillis()
            val updated = step.copy(
                status = status.name,
                result = result,
                errorMessage = errorMessage,
                outputSnapshot = result?.let { output ->
                    WorkflowStepSnapshotCodec.encodeOutput(
                        text = output,
                        knowledgeReferences = knowledgeReferences,
                        requiresCurrentKnowledgeReferences = requiresCurrentKnowledgeReferences,
                    )
                },
                completedAt = now,
            )
            dao.upsertStep(updated)
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
        // long: Run 终态与尚未完成步骤在同一事务内收敛；已完成或从来源 Run 复用的步骤保持原结果，旧执行证据不能被最终错误覆盖。
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
            val terminalStepStatus = when (status) {
                WorkflowRunStatus.COMPLETED -> null
                WorkflowRunStatus.BLOCKED -> WorkflowStepStatus.BLOCKED
                WorkflowRunStatus.CANCELLED -> WorkflowStepStatus.CANCELLED
                WorkflowRunStatus.FAILED -> WorkflowStepStatus.FAILED
                else -> error("非终态不能完成工作流步骤")
            }
            val steps = dao.getSteps(workflowRunId)
            if (status == WorkflowRunStatus.COMPLETED) {
                val unfinished = steps.filter { it.status !in SUCCESSFUL_STEP_STATUSES }
                if (steps.size == 1 && unfinished.size == 1) {
                    // long: 兼容 v15 单步骤调用方直接收敛 Run；多步骤执行必须逐步完成，不能用最终回调一次性伪造所有步骤成功。
                    val onlyStep = unfinished.single()
                    dao.upsertStep(
                        onlyStep.copy(
                            status = WorkflowStepStatus.COMPLETED.name,
                            result = result,
                            outputSnapshot = result,
                            completedAt = now,
                        ),
                    )
                } else {
                    require(unfinished.isEmpty()) { "工作流仍有未完成步骤" }
                }
            }
            dao.upsertRun(updated)
            if (terminalStepStatus != null) {
                var terminalAssigned = false
                steps.filter { it.status !in TERMINAL_STEP_STATUSES.map { statusValue -> statusValue.name } }.forEach { step ->
                    val stepStatus = if (!terminalAssigned && step.status == WorkflowStepStatus.RUNNING.name) {
                        terminalAssigned = true
                        terminalStepStatus
                    } else {
                        WorkflowStepStatus.CANCELLED
                    }
                    dao.upsertStep(
                        step.copy(
                            status = stepStatus.name,
                            errorMessage = errorMessage,
                            completedAt = now,
                        ),
                    )
                }
            }
            updated.toRecord()
        }
    }

    suspend fun completeByAgentRunId(
        agentRunId: String,
        status: WorkflowRunStatus,
        result: String? = null,
        errorMessage: String? = null,
    ): WorkflowRunRecord? {
        val dao = database.workflowDao()
        val workflowRun = dao.getRunByAgentRunId(agentRunId) ?: return null
        val step = dao.getStepByAgentRunId(agentRunId) ?: return completeRun(workflowRun.id, status, result, errorMessage)
        val stepStatus = when (status) {
            WorkflowRunStatus.COMPLETED -> WorkflowStepStatus.COMPLETED
            WorkflowRunStatus.BLOCKED -> WorkflowStepStatus.BLOCKED
            WorkflowRunStatus.CANCELLED -> WorkflowStepStatus.CANCELLED
            WorkflowRunStatus.FAILED -> WorkflowStepStatus.FAILED
            else -> return workflowRun.toRecord()
        }
        val toolResults = database.agentRunDao().getToolResults(agentRunId)
        completeWorkflowStep(
            workflowRunId = workflowRun.id,
            workflowStepId = step.id,
            status = stepStatus,
            result = result,
            errorMessage = errorMessage,
            knowledgeReferences = toolResults
                .flatMap { KnowledgeReferenceCodec.decode(it.knowledgeReferencesJson) }
                .distinct(),
            requiresCurrentKnowledgeReferences = toolResults.any { it.toolName == KNOWLEDGE_SEARCH_TOOL },
        )
        val refreshedSteps = dao.getSteps(workflowRun.id).map { it.toRecord() }
        val nextStep = WorkflowStepExecutionPolicy.nextExecutableStep(refreshedSteps)
        return if (status == WorkflowRunStatus.COMPLETED && nextStep != null) {
            dao.getRun(workflowRun.id)?.toRecord()
        } else {
            val workflowResult = if (status == WorkflowRunStatus.COMPLETED) {
                // long: 审批恢复可能完成最后一步；最终结果必须重新聚合全部步骤快照，不能只保留刚恢复的单步输出。
                refreshedSteps.mapNotNull { step ->
                    WorkflowStepSnapshotCodec.outputText(step.outputSnapshot ?: step.result)
                }.joinToString(separator = "\n\n")
            } else {
                result
            }
            completeRun(workflowRun.id, status, workflowResult, errorMessage)
        }
    }

    suspend fun recentRunDetails(limit: Int = 50): List<WorkflowRunDetail> {
        val dao = database.workflowDao()
        return loadRunDetails(dao.recentRuns(limit))
    }

    suspend fun allRunDetails(): List<WorkflowRunDetail> {
        return loadRunDetails(database.workflowDao().listRuns())
    }

    suspend fun runDetail(workflowRunId: String): WorkflowRunDetail? {
        val dao = database.workflowDao()
        val run = dao.getRun(workflowRunId) ?: return null
        return WorkflowRunDetail(
            run = run.toRecord(),
            steps = dao.getSteps(workflowRunId).map { it.toRecord() },
        )
    }

    suspend fun retryRun(sourceWorkflowRunId: String, conversationId: String): WorkflowRunDetail {
        return database.withTransaction {
            val dao = database.workflowDao()
            val source = dao.getRun(sourceWorkflowRunId) ?: error("来源工作流 Run 不存在：$sourceWorkflowRunId")
            require(source.status in TERMINAL_RUN_STATUSES) { "只有已结束的工作流 Run 可以重试" }
            val workflow = dao.getWorkflow(source.workflowId) ?: error("工作流不存在：${source.workflowId}")
            require(workflow.enabled) { "工作流已停用，不能重试" }
            require(dao.getActiveRun(source.workflowId) == null) { "这个工作流已有未完成的 Run" }
            val sourceSteps = dao.getSteps(sourceWorkflowRunId)
            require(sourceSteps.isNotEmpty()) { "来源工作流没有步骤快照" }
            require(sourceSteps.any { it.status !in SUCCESSFUL_STEP_STATUSES }) { "来源工作流没有可重试步骤" }
            val now = System.currentTimeMillis()
            val run = WorkflowRunEntity(
                id = "workflow-run-${UUID.randomUUID()}",
                workflowId = source.workflowId,
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
                retryOfWorkflowRunId = sourceWorkflowRunId,
            )
            var reachedIncompleteStep = false
            val retrySteps = sourceSteps.sortedBy { it.sequence }.map { sourceStep ->
                val reusable = !reachedIncompleteStep && sourceStep.status in SUCCESSFUL_STEP_STATUSES
                if (!reusable) reachedIncompleteStep = true
                WorkflowStepEntity(
                    id = "workflow-step-${UUID.randomUUID()}",
                    workflowRunId = run.id,
                    sequence = sourceStep.sequence,
                    type = sourceStep.type,
                    status = if (reusable) WorkflowStepStatus.SKIPPED.name else WorkflowStepStatus.PENDING.name,
                    title = sourceStep.title,
                    detail = sourceStep.detail,
                    agentRunId = null,
                    result = sourceStep.result.takeIf { reusable },
                    errorMessage = null,
                    createdAt = now,
                    startedAt = null,
                    completedAt = now.takeIf { reusable },
                    definitionStepId = sourceStep.definitionStepId,
                    idempotencyKey = sourceStep.idempotencyKey,
                    inputSnapshot = sourceStep.inputSnapshot.takeIf { reusable }
                        ?: WorkflowStepSnapshotCodec.encodeInput(sourceStep.detail, emptyList()),
                    outputSnapshot = sourceStep.outputSnapshot.takeIf { reusable },
                    reusedFromStepId = sourceStep.id.takeIf { reusable },
                )
            }
            // long: 新 Run 只复用来源 Run 已落库的成功输出；失败步骤即使可能产生过副作用也不会被标成完成，后续 UI 需按风险提示用户确认重试。
            dao.upsertRun(run)
            retrySteps.forEach { dao.upsertStep(it) }
            WorkflowRunDetail(run.toRecord(), retrySteps.map { it.toRecord() })
        }
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

    internal suspend fun startupRecoveryCandidates(
        currentProcessTaskIds: Set<String>,
    ): WorkflowStartupRecoveryCandidates {
        return database.withTransaction {
            val dao = database.workflowDao()
            val activeWorkflowRuns = dao.runsByStatuses(
                listOf(WorkflowRunStatus.QUEUED.name, WorkflowRunStatus.RUNNING.name),
            )
            val recoverableScheduledTasks = dao.getRecoverableScheduledTasks()
            val currentProcessScheduledTaskIds = linkedSetOf<String>()
            val currentProcessWorkflowRunIds = linkedSetOf<String>()
            currentProcessTaskIds.forEach { taskId ->
                val task = dao.getScheduledTask(taskId)
                // long: 当前进程所有权只保护仍正常 RUNNING 的 Worker；用户已写入 STOP_REQUESTED 时，启动恢复必须越过旧所有权继续取消。
                if (task?.status == ScheduledTaskStatus.RUNNING.name) {
                    currentProcessScheduledTaskIds += taskId
                    task.workflowRunId?.let(currentProcessWorkflowRunIds::add)
                }
            }
            val currentProcessAgentRunIds = linkedSetOf<String>()
            currentProcessWorkflowRunIds.forEach { workflowRunId ->
                dao.getRun(workflowRunId)?.agentRunId?.let(currentProcessAgentRunIds::add)
                // long: 多步骤 Workflow 的 Run 只保存当前 Agent；同时读取步骤关联可覆盖状态切换窗口，并保留未来多 Agent 步骤的排除能力。
                dao.getSteps(workflowRunId).mapNotNullTo(currentProcessAgentRunIds) { it.agentRunId }
            }
            WorkflowStartupRecoveryCandidates(
                activeWorkflowRunIds = activeWorkflowRuns.mapTo(linkedSetOf()) { it.id },
                recoverableScheduledTaskIds = recoverableScheduledTasks.mapTo(linkedSetOf()) { it.id },
                currentProcessScheduledTaskIds = currentProcessScheduledTaskIds,
                currentProcessWorkflowRunIds = currentProcessWorkflowRunIds,
                currentProcessAgentRunIds = currentProcessAgentRunIds,
            )
        }
    }

    suspend fun reconcileInterruptedRuns(
        resumableAgentRunIds: Set<String> = emptySet(),
        workflowRunIds: Set<String>? = null,
    ): Int {
        val dao = database.workflowDao()
        val active = dao.runsByStatuses(listOf(WorkflowRunStatus.QUEUED.name, WorkflowRunStatus.RUNNING.name))
            .filter { workflowRunIds == null || it.id in workflowRunIds }
        var reconciled = 0
        active.forEach { workflowRun ->
            val scheduledTask = dao.getScheduledTaskByWorkflowRunId(workflowRun.id)
            if (scheduledTask?.status == ScheduledTaskStatus.STOP_REQUESTED.name) {
                // long: 用户可能在 Worker 认领任务后、Agent Run 创建前发起停止；持久化停止栅栏优先于“Agent 关联缺失”恢复规则，避免把主动取消误记为执行失败。
                completeRun(
                    workflowRun.id,
                    WorkflowRunStatus.CANCELLED,
                    errorMessage = scheduledTask.errorMessage ?: "用户已请求停止后台工作流",
                )
                reconciled += 1
                return@forEach
            }
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
                agentRun.id in resumableAgentRunIds -> {
                    // long: Agent 已通过幂等证据筛选时，Workflow 必须保持当前步骤运行中，等待只读验证写回输出后再决定后续步骤，不能在启动对账中提前判失败。
                    Unit
                }
                agentRun.status == AgentRunStatus.WAITING_APPROVAL.name -> Unit
                else -> {
                    val agentStatus = runCatching { AgentRunStatus.valueOf(agentRun.status) }.getOrNull()
                    val workflowStatus = agentStatus?.let(WorkflowAgentRunStatusPolicy::terminalStatus)
                        ?: WorkflowRunStatus.FAILED
                    val settled = completeByAgentRunId(
                        agentRunId = agentRun.id,
                        status = workflowStatus,
                        result = agentRun.result.takeIf { workflowStatus == WorkflowRunStatus.COMPLETED },
                        errorMessage = agentRun.errorMessage
                            ?: "应用重启后无法恢复工作流执行栈".takeIf { workflowStatus != WorkflowRunStatus.COMPLETED },
                    )
                    if (settled?.status == WorkflowRunStatus.RUNNING) {
                        val preservedResult = dao.getSteps(workflowRun.id)
                            .filter { it.status in SUCCESSFUL_STEP_STATUSES }
                            .sortedBy { it.sequence }
                            .mapNotNull { step -> WorkflowStepSnapshotCodec.outputText(step.outputSnapshot ?: step.result) }
                            .joinToString(separator = "\n\n")
                        // long: 当前 Agent 已成功时先保留步骤输出，再关闭丢失执行栈的旧 Run；用户重试会复用完成前缀，但不会自动重放后续副作用。
                        completeRun(
                            workflowRun.id,
                            WorkflowRunStatus.FAILED,
                            result = preservedResult,
                            errorMessage = "应用重启时当前步骤已完成，但后续步骤尚未执行；请重试此 Run",
                        )
                    }
                    reconciled += 1
                }
            }
        }
        return reconciled
    }

    private suspend fun currentPreviousOutputs(
        steps: List<WorkflowStepEntity>,
        beforeSequence: Int,
    ): List<String> {
        return steps
            .filter { it.sequence < beforeSequence && it.status in SUCCESSFUL_STEP_STATUSES }
            .sortedBy { it.sequence }
            .mapNotNull { step ->
                val output = WorkflowStepSnapshotCodec.decodeOutput(step.outputSnapshot ?: step.result)
                    ?: return@mapNotNull null
                val references = output.knowledgeReferences.distinct()
                val knowledgeEvidenceExpected = output.requiresCurrentKnowledgeReferences ||
                    output.expectedKnowledgeReferenceCount > 0
                if (!knowledgeEvidenceExpected) return@mapNotNull output.text
                if (references.isEmpty() || references.size != output.expectedKnowledgeReferenceCount) {
                    return@mapNotNull null
                }
                val currentReferences = knowledgeDocumentStore.retainCurrentReferences(references)
                // long: Workflow 只把完整且仍有效的知识证据传给下一步骤；旧输出快照继续保留在来源 Run 中供审计，不在这里回写或删改。
                output.text.takeIf {
                    currentReferences.size == references.size && currentReferences.toSet() == references.toSet()
                }
            }
    }

    private fun WorkflowEntity.toRecord(
        definitions: List<WorkflowStepDefinitionEntity> = emptyList(),
    ) = WorkflowRecord(
        id = id,
        name = name,
        goal = definitions.firstOrNull()?.goal ?: goal,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        steps = definitions.sortedBy { it.sequence }.map { it.toRecord() },
    )

    private fun WorkflowStepDefinitionEntity.toRecord() = WorkflowStepDefinitionRecord(
        id = id,
        workflowId = workflowId,
        sequence = sequence,
        goal = goal,
        idempotencyKey = idempotencyKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun WorkflowStepDefinitionEntity.toRunStep(
        workflowRunId: String,
        now: Long,
        background: Boolean,
    ) = WorkflowStepEntity(
        id = "workflow-step-${UUID.randomUUID()}",
        workflowRunId = workflowRunId,
        sequence = sequence,
        type = AGENT_RUN_STEP_TYPE,
        status = WorkflowStepStatus.PENDING.name,
        title = if (background) "后台步骤 $sequence" else "步骤 $sequence",
        detail = goal,
        agentRunId = null,
        result = null,
        errorMessage = null,
        createdAt = now,
        startedAt = null,
        completedAt = null,
        definitionStepId = id,
        idempotencyKey = idempotencyKey,
        inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(goal, emptyList()),
        outputSnapshot = null,
        reusedFromStepId = null,
    )

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
        retryOfWorkflowRunId = retryOfWorkflowRunId,
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
        definitionStepId = definitionStepId,
        idempotencyKey = idempotencyKey,
        inputSnapshot = inputSnapshot,
        outputSnapshot = outputSnapshot,
        reusedFromStepId = reusedFromStepId,
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

    private fun WorkflowRunStatus.toScheduledTaskStatus(): ScheduledTaskStatus = when (this) {
        WorkflowRunStatus.BLOCKED -> ScheduledTaskStatus.BLOCKED
        WorkflowRunStatus.COMPLETED -> ScheduledTaskStatus.COMPLETED
        WorkflowRunStatus.FAILED -> ScheduledTaskStatus.FAILED
        WorkflowRunStatus.CANCELLED -> ScheduledTaskStatus.CANCELLED
        WorkflowRunStatus.QUEUED,
        WorkflowRunStatus.RUNNING -> error("活动工作流状态不能映射为定时任务终态")
    }

    companion object {
        const val AGENT_RUN_STEP_TYPE = "AGENT_RUN"
        private const val KNOWLEDGE_SEARCH_TOOL = "knowledge.search"
        private val SUCCESSFUL_STEP_STATUSES = setOf(
            WorkflowStepStatus.COMPLETED.name,
            WorkflowStepStatus.SKIPPED.name,
        )
        private val TERMINAL_STEP_STATUSES = setOf(
            WorkflowStepStatus.BLOCKED,
            WorkflowStepStatus.COMPLETED,
            WorkflowStepStatus.SKIPPED,
            WorkflowStepStatus.FAILED,
            WorkflowStepStatus.CANCELLED,
        )

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
            role: String,
            text: String,
            createdAt: Long,
            origin: MessageOrigin,
            verifiedAgentContext: String? = null,
        ) = StoredConversationMessage(
            id = id,
            role = role,
            text = text,
            createdAt = createdAt,
            origin = origin.name,
            verifiedAgentContext = verifiedAgentContext,
            meta = null,
        )
    }
}

data class ScheduledWorkflowClaim(
    val task: ScheduledTaskRecord,
    val workflow: WorkflowRecord,
    val run: WorkflowRunDetail,
    val userMessageId: String,
)
