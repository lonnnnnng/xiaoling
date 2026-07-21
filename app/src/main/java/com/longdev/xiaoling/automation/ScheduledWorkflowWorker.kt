package com.longdev.xiaoling.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.longdev.xiaoling.agent.AgentBackgroundApprovalRequiredException
import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentExecutionOrigin
import com.longdev.xiaoling.agent.AgentInvocationSource
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentProfileRuntimeConfigPolicy
import com.longdev.xiaoling.agent.AgentProfileSnapshot
import com.longdev.xiaoling.agent.AgentRunSummary
import com.longdev.xiaoling.agent.AgentRunUseCase
import com.longdev.xiaoling.agent.ApprovalDecision
import com.longdev.xiaoling.agent.ApprovalGate
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.VerifiedAgentContextCodec
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.storage.ScheduledWorkflowClaim
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ScheduledWorkflowWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(INPUT_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        ScheduledWorkflowProcessExecutionRegistry.process.withScheduledTask(taskId) {
            // long: 当前进程所有权必须先于 Repository 构造和任务 claim 建立；应用同时启动时，恢复快照会识别这条链而不会把刚开始的 Worker 当成旧进程遗留。
            ScheduledWorkflowExecutor(applicationContext).execute(taskId)
        }
        // long: 业务成功、失败和待处理都已经写入 Room 终态；WorkManager 只负责触发，不用系统重试复制一个可能已执行过的 Agent Run。
        return Result.success()
    }

    companion object {
        const val INPUT_TASK_ID = "scheduled_task_id"
    }
}

class ScheduledWorkflowExecutor(
    context: Context,
    private val workflowRepository: RoomWorkflowRepository = RoomWorkflowRepository(context.applicationContext),
    private val providerRepository: ProviderRepository = ProviderRepository(context.applicationContext),
    private val notifier: ScheduledTaskNotifier = ScheduledTaskNotifier(context.applicationContext),
    private val client: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    private val uiPreferenceStore: UiPreferenceStore = UiPreferenceStore(context.applicationContext),
    private val scheduledTaskScheduler: ScheduledTaskScheduler = WorkManagerScheduledTaskScheduler(context.applicationContext),
) {
    private val agentRunUseCase = AgentRunUseCase(context.applicationContext, client)
    private val agentProfileStore = RoomAgentProfileStore(context.applicationContext)
    private var backgroundRuntimeSelection: BackgroundAgentRuntimeSelection? = null
    private val reentryCoordinator = ScheduledWorkflowReentryCoordinator(
        loadTask = workflowRepository::getScheduledTask,
        loadWorkflowRun = workflowRepository::runDetail,
        closeAgentRun = agentRunUseCase::closeInterruptedRunForWorkerReentry,
        reconcileWorkflowRun = { workflowRunId ->
            workflowRepository.reconcileInterruptedRuns(workflowRunIds = setOf(workflowRunId)) > 0
        },
        reconcileScheduledTask = { taskId ->
            workflowRepository.reconcileInterruptedScheduledTasks(taskIds = setOf(taskId)) > 0
        },
    )
    private val orchestrator = ScheduledWorkflowOrchestrator(
        claimTask = workflowRepository::claimScheduledRun,
        runAgent = ::runAgent,
        markAgentRunStarted = { claim, step, agentRunId ->
            workflowRepository.markAgentRunStarted(claim.run.run.id, step.id, agentRunId)
        },
        completeStep = ::completeStep,
        faultInjector = NoOpScheduledWorkflowFaultInjector,
        settle = ::settle,
        notify = { claim, task, outcome ->
            val detail = if (task.status != outcome.taskStatus) {
                task.errorMessage ?: when (task.status) {
                    ScheduledTaskStatus.BLOCKED -> "后台工作流需要前台处理"
                    ScheduledTaskStatus.COMPLETED -> "后台工作流已根据持久化结果完成"
                    ScheduledTaskStatus.FAILED -> "后台工作流已根据持久化结果收敛失败"
                    ScheduledTaskStatus.CANCELLED -> "后台工作流已根据持久化结果安全停止"
                    ScheduledTaskStatus.SCHEDULED,
                    ScheduledTaskStatus.RUNNING,
                    ScheduledTaskStatus.STOP_REQUESTED -> outcome.notificationDetail
                }
            } else {
                outcome.notificationDetail
            }
            notifier.notify(claim.workflow.name, task, detail)
        },
        onClaimRejected = ::notifyClaimFailure,
    )

    suspend fun execute(taskId: String) {
        try {
            if (reentryCoordinator.reconcile(taskId)) {
                notifyReentryOutcome(taskId)
            } else {
                orchestrator.execute(taskId)
            }
        } finally {
            withContext(NonCancellable) {
                scheduleNextOccurrence(taskId)
            }
        }
    }

    private suspend fun scheduleNextOccurrence(completedTaskId: String) {
        val nextTask = workflowRepository.materializeNextOccurrence(completedTaskId) ?: return
        try {
            val workRequestId = scheduledTaskScheduler.enqueue(nextTask)
            workflowRepository.attachWorkRequest(nextTask.id, workRequestId)
        } catch (error: Throwable) {
            // long: 当前执行结果已经落库，下一次入队失败只关闭新实例；规则仍保留，应用下次启动会从终态实例继续物化未来周期。
            workflowRepository.failScheduling(nextTask.id, error.message ?: "周期任务入队失败")
        }
    }

    private suspend fun runAgent(
        claim: ScheduledWorkflowClaim,
        step: WorkflowStepRecord,
        onAgentRunId: suspend (String) -> Unit,
    ): AgentRunSummary {
        val runtimeSelection = backgroundRuntimeSelection
            ?: selectedBackgroundRuntime().also { backgroundRuntimeSelection = it }
        val preparedStep = workflowRepository.prepareWorkflowStep(claim.run.run.id, step.id)
        val input = WorkflowStepSnapshotCodec.decodeInput(preparedStep.inputSnapshot)
        val executionGoal = WorkflowStepPromptPolicy.build(input.goal, input.previousOutputs)
        return agentRunUseCase.run(
            conversationId = claim.run.run.conversationId,
            userMessageId = if (step.sequence == 1) {
                claim.userMessageId
            } else {
                workflowRepository.appendScheduledStepPrompt(claim.run.run.conversationId, preparedStep.detail)
            },
            goal = executionGoal,
            config = runtimeSelection.config,
            summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(uiPreferenceStore.loadPromptSettings()),
            agentProfile = runtimeSelection.profile,
            memoryRecallEnabled = runtimeSelection.profile.memoryEnabled,
            executionOrigin = AgentExecutionOrigin.BACKGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            approvalGate = RejectingBackgroundApprovalGate,
            onSnapshot = { snapshot -> onAgentRunId(snapshot.run.id) },
        )
    }

    private suspend fun completeStep(
        claim: ScheduledWorkflowClaim,
        step: WorkflowStepRecord,
        summary: AgentRunSummary,
    ): WorkflowStepRecord {
        return workflowRepository.completeScheduledWorkflowStep(
            taskId = claim.task.id,
            workflowRunId = claim.run.run.id,
            workflowStepId = step.id,
            result = summary.responseText,
            knowledgeReferences = summary.verifiedContext.knowledgeReferences,
            requiresCurrentKnowledgeReferences = summary.verifiedContext.toolName == "knowledge.search" ||
                summary.verifiedContext.toolExecutions.any { it.toolName == "knowledge.search" },
            verifiedAgentContext = VerifiedAgentContextCodec.encode(summary.verifiedContext),
        )
    }

    private suspend fun settle(
        claim: ScheduledWorkflowClaim,
        outcome: ScheduledExecutionOutcome,
    ): ScheduledTaskRecord {
        val task = workflowRepository.settleScheduledWorkflowRun(
            taskId = claim.task.id,
            workflowRunId = claim.run.run.id,
            workflowStatus = outcome.workflowStatus,
            taskStatus = outcome.taskStatus,
            result = outcome.workflowResult,
            errorMessage = outcome.errorMessage,
        ) ?: claim.task.copy(status = outcome.taskStatus, errorMessage = outcome.errorMessage)
        // long: 持久化 Workflow 终态可能覆盖迟到执行结果；只有 Task 与本轮 outcome 一致时才追加消息，避免失败/取消链留下相反结论。
        if (task.status == outcome.taskStatus) {
            outcome.conversationResult?.let { result ->
                workflowRepository.appendScheduledConversationResult(
                    conversationId = claim.run.run.conversationId,
                    text = result.text,
                    origin = result.origin,
                    verifiedAgentContext = result.verifiedAgentContext,
                )
            }
        }
        return task
    }

    private suspend fun selectedBackgroundRuntime(): BackgroundAgentRuntimeSelection {
        val stored = providerRepository.load()
        val defaultProvider = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
            ?: error("没有可用的模型提供方")
        val now = System.currentTimeMillis()
        val skills = agentRunUseCase.listSkills()
        val storedAgents = agentProfileStore.loadOrCreateDefault(
            AgentProfileRecord(
                id = DEFAULT_AGENT_PROFILE_ID,
                name = "默认 Agent",
                avatar = "灵",
                providerId = defaultProvider.id,
                model = defaultProvider.model.takeIf { it in defaultProvider.enabledModels }.orEmpty(),
                apiMode = ApiMode.CHAT_COMPLETIONS,
                systemPrompt = "",
                contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                allowedToolNames = agentRunUseCase.registeredTools().map { it.name },
                allowedSkillIds = skills.filter { it.enabled }.map { it.definition.id },
                memoryEnabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val agentProfile = storedAgents.profiles.firstOrNull { it.id == storedAgents.selectedProfileId }
            ?: error("没有可用的 Agent Profile")
        val snapshot = agentProfile.snapshot()
        return BackgroundAgentRuntimeSelection(
            config = AgentProfileRuntimeConfigPolicy.resolve(snapshot, stored.profiles, uiPreferenceStore.loadUserAgent()),
            profile = snapshot,
        )
    }

    private suspend fun notifyClaimFailure(taskId: String) {
        val task = workflowRepository.getScheduledTask(taskId) ?: return
        if (task.status != ScheduledTaskStatus.FAILED) return
        val workflowName = workflowRepository.getWorkflow(task.workflowId)?.name ?: "已删除工作流"
        notifier.notify(workflowName, task, task.errorMessage ?: "定时任务未能开始")
    }

    private suspend fun notifyReentryOutcome(taskId: String) {
        val task = workflowRepository.getScheduledTask(taskId) ?: return
        if (ScheduledTaskPolicy.isUnsettled(task.status)) return
        val workflowName = workflowRepository.getWorkflow(task.workflowId)?.name ?: "已删除工作流"
        val detail = task.errorMessage ?: when (task.status) {
            ScheduledTaskStatus.COMPLETED -> "后台工作流已根据持久化结果完成"
            ScheduledTaskStatus.BLOCKED -> "后台工作流需要前台处理"
            ScheduledTaskStatus.CANCELLED -> "后台工作流已在系统重启后安全停止"
            ScheduledTaskStatus.FAILED -> "后台工作流已在系统重启后收敛失败"
            ScheduledTaskStatus.SCHEDULED,
            ScheduledTaskStatus.RUNNING,
            ScheduledTaskStatus.STOP_REQUESTED -> return
        }
        notifier.notify(workflowName, task, detail)
    }

    private companion object {
        const val DEFAULT_AGENT_PROFILE_ID = "agent-profile-default"
    }
}

private data class BackgroundAgentRuntimeSelection(
    val config: ProviderRequestConfig,
    val profile: AgentProfileSnapshot,
)

internal sealed interface ScheduledExecutionOutcome {
    val workflowStatus: WorkflowRunStatus
    val taskStatus: ScheduledTaskStatus
    val workflowResult: String?
    val errorMessage: String?
    val notificationDetail: String
    val conversationResult: ScheduledConversationResult?

    data class Completed(val stepResults: List<String>) : ScheduledExecutionOutcome {
        override val workflowStatus = WorkflowRunStatus.COMPLETED
        override val taskStatus = ScheduledTaskStatus.COMPLETED
        override val workflowResult = stepResults.joinToString(separator = "\n\n")
        override val errorMessage: String? = null
        override val notificationDetail = workflowResult.ifBlank { "工作流步骤已完成" }
        override val conversationResult: ScheduledConversationResult? = null
    }

    data class Blocked(val reason: String) : ScheduledExecutionOutcome {
        override val workflowStatus = WorkflowRunStatus.BLOCKED
        override val taskStatus = ScheduledTaskStatus.BLOCKED
        override val workflowResult: String? = null
        override val errorMessage = reason
        override val notificationDetail = "$reason。请打开应用以前台重试。"
        override val conversationResult = ScheduledConversationResult(
            text = "后台工作流已停止：$reason。请打开 Agent 任务中心并以前台重试继续。",
            origin = MessageOrigin.ERROR,
        )
    }

    data class Failed(val reason: String) : ScheduledExecutionOutcome {
        override val workflowStatus = WorkflowRunStatus.FAILED
        override val taskStatus = ScheduledTaskStatus.FAILED
        override val workflowResult: String? = null
        override val errorMessage = reason
        override val notificationDetail = reason
        override val conversationResult = ScheduledConversationResult(
            text = "后台工作流失败：$reason",
            origin = MessageOrigin.ERROR,
        )
    }

    data class Cancelled(val reason: String) : ScheduledExecutionOutcome {
        override val workflowStatus = WorkflowRunStatus.CANCELLED
        override val taskStatus = ScheduledTaskStatus.CANCELLED
        override val workflowResult: String? = null
        override val errorMessage = reason
        override val notificationDetail = reason
        override val conversationResult: ScheduledConversationResult? = null
    }
}

internal data class ScheduledConversationResult(
    val text: String,
    val origin: MessageOrigin,
    val verifiedAgentContext: String? = null,
)

internal class ScheduledWorkflowOrchestrator(
    private val claimTask: suspend (String) -> ScheduledWorkflowClaim?,
    private val runAgent: suspend (
        ScheduledWorkflowClaim,
        WorkflowStepRecord,
        suspend (String) -> Unit,
    ) -> AgentRunSummary,
    private val markAgentRunStarted: suspend (ScheduledWorkflowClaim, WorkflowStepRecord, String) -> Unit,
    private val completeStep: suspend (ScheduledWorkflowClaim, WorkflowStepRecord, AgentRunSummary) -> WorkflowStepRecord,
    private val faultInjector: ScheduledWorkflowFaultInjector = NoOpScheduledWorkflowFaultInjector,
    private val settle: suspend (ScheduledWorkflowClaim, ScheduledExecutionOutcome) -> ScheduledTaskRecord,
    private val notify: (ScheduledWorkflowClaim, ScheduledTaskRecord, ScheduledExecutionOutcome) -> Unit,
    private val onClaimRejected: suspend (String) -> Unit,
) {
    suspend fun execute(taskId: String) {
        val claim = claimTask(taskId)
        if (claim == null) {
            onClaimRejected(taskId)
            return
        }
        val outcome = try {
            val results = mutableListOf<String>()
            var steps = claim.run.steps
            while (true) {
                val step = WorkflowStepExecutionPolicy.nextExecutableStep(steps) ?: break
                val summary = runAgent(claim, step) { agentRunId ->
                    markAgentRunStarted(claim, step, agentRunId)
                }
                val completed = completeStep(claim, step, summary)
                faultInjector.afterStepPersisted(claim, step, completed)
                steps = steps.map { current -> if (current.id == completed.id) completed else current }
                results += summary.responseText
            }
            val unfinished = steps.any { it.status !in setOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.SKIPPED) }
            if (unfinished) {
                ScheduledExecutionOutcome.Failed("工作流步骤未按顺序完成")
            } else {
                ScheduledExecutionOutcome.Completed(results)
            }
        } catch (error: AgentBackgroundApprovalRequiredException) {
            ScheduledExecutionOutcome.Blocked(error.message ?: "后台任务需要用户确认")
        } catch (error: ScheduledWorkflowProcessTerminationException) {
            // long: 进程终止不会留下业务结算机会，必须把已持久化的步骤交给下次启动对账，避免伪造失败通知或复制副作用。
            throw error
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                finishAndNotify(claim, ScheduledExecutionOutcome.Cancelled("系统停止了本次后台工作流"))
            }
            throw error
        } catch (error: Throwable) {
            ScheduledExecutionOutcome.Failed(error.message ?: "后台工作流执行失败")
        }
        // long: Agent 执行异常先归一化为业务终态，Ledger 或通知自身失败不再被误判成第二次 Agent 失败，避免重复覆盖已经完成的结果。
        finishAndNotify(claim, outcome)
    }

    private suspend fun finishAndNotify(
        claim: ScheduledWorkflowClaim,
        outcome: ScheduledExecutionOutcome,
    ) {
        val task = settle(claim, outcome)
        notify(claim, task, outcome)
    }
}

internal fun interface ScheduledWorkflowFaultInjector {
    suspend fun afterStepPersisted(
        claim: ScheduledWorkflowClaim,
        step: WorkflowStepRecord,
        completedStep: WorkflowStepRecord,
    )
}

internal object NoOpScheduledWorkflowFaultInjector : ScheduledWorkflowFaultInjector {
    override suspend fun afterStepPersisted(
        claim: ScheduledWorkflowClaim,
        step: WorkflowStepRecord,
        completedStep: WorkflowStepRecord,
    ) = Unit
}

/**
 * 只用于测试操作系统在步骤已经落库后的直接进程终止，不应被业务流程捕获为普通失败。
 * long: 真实进程回收不会执行 finally 或结算逻辑，因此测试必须保留中间 Ledger 状态。
 */
internal class ScheduledWorkflowProcessTerminationException : Error("模拟 Workflow 进程终止")

private object RejectingBackgroundApprovalGate : ApprovalGate {
    override suspend fun requestApproval(
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): ApprovalDecision {
        // long: Runtime 应在调用 Gate 前把后台审批收敛为 BLOCKED；此拒绝实现是第二道 fail-closed 保护，防止未来重构误接自动审批。
        return ApprovalDecision(approved = false, reason = "后台任务不能代替用户批准工具")
    }
}
