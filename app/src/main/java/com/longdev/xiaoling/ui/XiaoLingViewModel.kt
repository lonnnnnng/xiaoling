package com.longdev.xiaoling.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.xiaoling.agent.AgentCommand
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentMemoryDecayPolicy
import com.longdev.xiaoling.agent.AgentMemoryExpiryOption
import com.longdev.xiaoling.agent.AgentMemoryUpdate
import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentMessagePartPolicy
import com.longdev.xiaoling.agent.AgentProfilePolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentProfileRuntimeConfigPolicy
import com.longdev.xiaoling.agent.AgentProfileSnapshot
import com.longdev.xiaoling.agent.AgentRunUseCase
import com.longdev.xiaoling.agent.AgentInvocationSource
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentSkillDocumentCodec
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.agentProfileSnapshotOrNull
import com.longdev.xiaoling.agent.ApprovalDecision
import com.longdev.xiaoling.agent.ApprovalGate
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedAgentContextCodec
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowAgentRunStatusPolicy
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunRetryEligibility
import com.longdev.xiaoling.automation.WorkflowRunRetryPolicy
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepExecutionPolicy
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepPromptPolicy
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledWorkflowProcessExecutionRegistry
import com.longdev.xiaoling.automation.ScheduledWorkflowStopFallbackCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopOutcome
import com.longdev.xiaoling.automation.StartupRecoveryCoordinator
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.automation.ScheduledTaskScheduler
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageAttachmentSelection
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.ModelReasoningSummary
import com.longdev.xiaoling.model.ProviderMessagePartPolicy
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.ProviderApiUrlBuilder
import com.longdev.xiaoling.network.FailureKind
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.network.StreamDeltaUpdate
import com.longdev.xiaoling.prompt.PromptDefaults
import com.longdev.xiaoling.prompt.PromptContextMessage
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.prompt.PromptSettings
import com.longdev.xiaoling.storage.ConversationRepository
import com.longdev.xiaoling.storage.ImageAttachmentReader
import com.longdev.xiaoling.storage.DocumentAttachmentReader
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.SecureConfigStore
import com.longdev.xiaoling.storage.StoredConversation
import com.longdev.xiaoling.storage.StoredConversationMessage
import com.longdev.xiaoling.storage.StoredMessageMeta
import com.longdev.xiaoling.storage.StoredConversations
import com.longdev.xiaoling.storage.StoredProfiles
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.storage.XiaoLingBackupManager
import com.longdev.xiaoling.storage.UiPreferenceStore
import com.longdev.xiaoling.system.ProcessExitObservation
import com.longdev.xiaoling.system.RoomProcessExitObservationStore
import com.longdev.xiaoling.system.collectProcessExitObservationsBestEffort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun newChatMessageId(): String = "message-${System.currentTimeMillis()}-${UUID.randomUUID()}"

private fun ToolRisk.toUiLabel(): String {
    return when (this) {
        ToolRisk.SAFE -> "低风险"
        ToolRisk.REQUIRES_APPROVAL -> "需确认"
        ToolRisk.DANGEROUS -> "高风险"
    }
}

internal fun List<ChatMessage>.withRecoveredAgentUserMessage(run: AgentRunRecord): List<ChatMessage> {
    if (any { it.id == run.userMessageId }) return this
    return (this + ChatMessage(
        id = run.userMessageId,
        role = "user",
        text = "/agent ${run.goal}",
        createdAt = run.createdAt,
    )).sortedBy(ChatMessage::createdAt)
}

data class XiaoLingUiState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val promptSettings: PromptSettings = PromptSettings(),
    val profiles: List<ProviderProfile> = emptyList(),
    val selectedProfileId: String = "",
    val profileName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val userAgent: String = ProviderRequestConfig.DEFAULT_USER_AGENT,
    val model: String = "",
    val prompt: String = SecureConfigStore.DEFAULT_PROMPT,
    val availableModels: List<String> = emptyList(),
    val enabledModels: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val sendingMessage: Boolean = false,
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val reasoningSummaryEnabled: Boolean = false,
    val pendingImage: ImageAttachment? = null,
    val pendingDocument: DocumentAttachment? = null,
    val attachingImage: Boolean = false,
    val attachingDocument: Boolean = false,
    val loadingConversationMessages: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val knowledgeReferenceStatuses: Map<KnowledgeReference, KnowledgeReferenceStatus> = emptyMap(),
    val failedKnowledgeReferenceStatuses: Set<KnowledgeReference> = emptySet(),
    val conversations: List<ConversationSession> = emptyList(),
    val selectedConversationId: String = "",
    val conversationTitle: String = "",
    val conversationSummary: String = "",
    val manageDraft: ProviderEditDraft? = null,
    val syncingProfileIds: Set<String> = emptySet(),
    val syncingAllProfiles: Boolean = false,
    val batchSyncResults: Map<String, String> = emptyMap(),
    val activeAgentRun: AgentRunSnapshot? = null,
    val agentMemoryRecallEnabled: Boolean = true,
    val pendingAgentApproval: AgentApprovalUiState? = null,
    val loadingAgentRunHistory: Boolean = false,
    val agentRunHistory: List<AgentRunDetailRecord> = emptyList(),
    val selectedAgentRunId: String? = null,
    val agentRunHistoryError: String? = null,
    val loadingProcessExitObservations: Boolean = false,
    val processExitObservations: List<ProcessExitObservation> = emptyList(),
    val processExitObservationError: String? = null,
    val retryingAgentRunId: String? = null,
    val pendingAgentRetryConfirmation: AgentRetryConfirmationUiState? = null,
    val agentRetryNavigationConversationId: String? = null,
    val loadingMemories: Boolean = false,
    val memories: List<AgentMemoryRecord> = emptyList(),
    val memoryCandidatesEnabled: Boolean = false,
    val loadingMemoryCandidates: Boolean = false,
    val memoryCandidates: List<AgentMemoryCandidateRecord> = emptyList(),
    val mutatingMemoryCandidateIds: Set<String> = emptySet(),
    val memorySearchQuery: String = "",
    val memoryFilter: AgentMemoryFilter = AgentMemoryFilter.ALL,
    val selectedMemoryId: String? = null,
    val memoryError: String? = null,
    val mutatingMemoryIds: Set<String> = emptySet(),
    val editingMemory: AgentMemoryEditUiState? = null,
    val pendingMemoryDelete: AgentMemoryRecord? = null,
    val deletedMemoryForUndo: AgentMemoryRecord? = null,
    val memorySourceConversationNavigationId: String? = null,
    val memorySourceRunNavigationId: String? = null,
    val loadingSkills: Boolean = false,
    val importingSkill: Boolean = false,
    val skills: List<AgentSkillRecord> = emptyList(),
    val mutatingSkillIds: Set<String> = emptySet(),
    val skillError: String? = null,
    val pendingLocalSkillDelete: AgentSkillRecord? = null,
    val agentProfiles: List<AgentProfileRecord> = emptyList(),
    val selectedAgentProfileId: String = "",
    val registeredAgentTools: List<ToolDefinition> = emptyList(),
    val mutatingAgentProfileIds: Set<String> = emptySet(),
    val agentProfileError: String? = null,
    val loadingWorkflows: Boolean = false,
    val workflows: List<WorkflowRecord> = emptyList(),
    val workflowRuns: List<WorkflowRunDetail> = emptyList(),
    val scheduledTasks: List<ScheduledTaskRecord> = emptyList(),
    val workflowSchedules: List<WorkflowScheduleRecord> = emptyList(),
    val mutatingWorkflowIds: Set<String> = emptySet(),
    val mutatingScheduledTaskIds: Set<String> = emptySet(),
    val mutatingWorkflowScheduleIds: Set<String> = emptySet(),
    val schedulingWorkflowId: String? = null,
    val runningWorkflowId: String? = null,
    val pendingWorkflowRetryConfirmation: WorkflowRetryConfirmationUiState? = null,
    val workflowError: String? = null,
    val workflowNavigationConversationId: String? = null,
    val backupBusy: Boolean = false,
    val backupRestartRequired: Boolean = false,
    val result: OperationResult? = null,
)

data class ProviderEditDraft(
    val id: String?,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val upstreamModels: List<String>,
    val enabledModels: Set<String>,
    val loadingModels: Boolean = false,
)

private data class WorkflowUiData(
    val workflows: List<WorkflowRecord>,
    val runs: List<WorkflowRunDetail>,
    val tasks: List<ScheduledTaskRecord>,
    val schedules: List<WorkflowScheduleRecord>,
)

data class ChatMessage(
    val role: String,
    val text: String,
    val id: String = newChatMessageId(),
    val createdAt: Long = System.currentTimeMillis(),
    val origin: MessageOrigin = MessageOrigin.fromStored(value = null, role = role),
    val verifiedAgentContext: VerifiedAgentContext? = null,
    val meta: MessageMeta? = null,
    val parts: List<MessagePart> = emptyList(),
) {
    fun effectiveParts(): List<MessagePart> = AgentMessagePartPolicy.resolve(
        messageId = id,
        text = text,
        origin = origin,
        verifiedContext = verifiedAgentContext,
        storedParts = parts,
    )
}

internal fun ChatMessage.imagesForRequest(apiMode: ApiMode): List<ImageAttachment> {
    if (apiMode != ApiMode.RESPONSES || origin != MessageOrigin.USER) return emptyList()
    return effectiveParts().filterIsInstance<MessagePart.Image>().map { it.attachment }
}

internal fun ChatMessage.documentsForRequest(apiMode: ApiMode): List<DocumentAttachment> {
    if (apiMode != ApiMode.RESPONSES || origin != MessageOrigin.USER) return emptyList()
    return effectiveParts().filterIsInstance<MessagePart.Document>().map { it.attachment }
}

data class MessageMeta(
    val providerId: String? = null,
    val providerName: String? = null,
    val model: String? = null,
    val apiMode: ApiMode? = null,
    val streaming: Boolean? = null,
    val requestUrl: String? = null,
    val firstTokenLatencyMs: Long? = null,
    val latencyMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val finishReason: String? = null,
    val errorKind: String? = null,
    val errorMessage: String? = null,
)

internal fun ChatMessage.isEligibleForConversationContext(): Boolean {
    if (role == "user") return true
    if (role != "assistant") return false
    // long: 失败或取消的流式正文只是用户已经看到的局部输出，不能作为完整模型回复再次发送或进入摘要，否则残缺结论会在后续轮次被放大。
    return meta?.finishReason !in setOf("failed", "cancelled")
}

internal fun List<ChatMessage>.withFailedStreamingGeneration(
    baseMeta: MessageMeta,
    errorKind: String,
    errorMessage: String,
): List<ChatMessage> {
    val last = lastOrNull()
    if (last?.role != "assistant") return this
    // long: delta 已经展示后发生断流时保留用户看到的部分正文，但必须把同一气泡收敛为失败终态，不能让它永久显示“接收中”或伪装成完整回复。
    return dropLast(1) + last.copy(
        meta = (last.meta ?: baseMeta).copy(
            finishReason = "failed",
            errorKind = errorKind,
            errorMessage = errorMessage,
        ),
    )
}

data class ConversationSession(
    val id: String,
    val title: String,
    val summary: String,
    val summaryUntilMessageId: String?,
    val summaryUpdatedAt: Long?,
    val summaryModel: String?,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class OperationResult(
    val success: Boolean,
    val title: String,
    val message: String,
    val requestUrl: String? = null,
    val latencyMs: Long? = null,
    val firstTokenLatencyMs: Long? = null,
)

data class AgentApprovalUiState(
    val requestId: String,
    val runId: String,
    val conversationId: String,
    val toolCallId: String,
    val toolName: String,
    val toolDescription: String,
    val riskLabel: String,
    val arguments: Map<String, String>,
    val expiresAt: Long,
    val deciding: Boolean = false,
    val restoredFromProcess: Boolean = false,
) {
    companion object {
        fun from(request: ApprovalRequestRecord): AgentApprovalUiState {
            return AgentApprovalUiState(
                requestId = request.id,
                runId = request.runId,
                conversationId = request.conversationId,
                toolCallId = request.toolCallId,
                toolName = request.toolName,
                toolDescription = request.toolDescription,
                riskLabel = request.risk.toUiLabel(),
                arguments = request.arguments,
                expiresAt = request.expiresAt,
            )
        }
    }
}

data class AgentRetryConfirmationUiState(
    val runId: String,
    val goal: String,
    val evidenceCode: AgentTaskRetryEvidenceCode,
    val evidenceFingerprint: String,
)

data class WorkflowRetryConfirmationUiState(
    val runId: String,
    val workflowName: String,
    val retryFromSequence: Int,
    val reusedStepCount: Int,
)

data class AgentMemoryEditUiState(
    val id: String,
    val content: String,
    val tags: String,
    val type: String,
    val confidence: Double,
)

private data class AgentRuntimeSelection(
    val config: ProviderRequestConfig,
    val profile: AgentProfileSnapshot,
)

class XiaoLingViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ProviderRepository(application)
    private val conversationStore = ConversationRepository(application)
    private val imageAttachmentReader = ImageAttachmentReader(application.contentResolver)
    private val documentAttachmentReader = DocumentAttachmentReader(application)
    private val knowledgeDocumentStore = RoomKnowledgeDocumentStore(application)
    private val uiPreferenceStore = UiPreferenceStore(application)
    private val client = OpenAiCompatibleClient()
    private val conversationRequestContextPreparer = ConversationRequestContextPreparer(
        retainCurrentKnowledgeReferences = { references ->
            withContext(Dispatchers.IO) {
                knowledgeDocumentStore.retainCurrentReferences(references).toSet()
            }
        },
        generateSummary = { config, existingSummary, messagesToCompress, promptSettings ->
            if (messagesToCompress.isEmpty()) {
                existingSummary
            } else {
                val transcript = PromptPolicy.summaryTranscript(
                    messagesToCompress.map { message ->
                        PromptContextMessage(
                            origin = message.origin,
                            content = message.text,
                            verifiedAgentContext = message.verifiedAgentContext,
                        )
                    },
                )
                val prompt = buildString {
                    appendLine("已有摘要：")
                    appendLine(existingSummary.ifBlank { "无" })
                    appendLine()
                    appendLine("新增对话：")
                    appendLine(transcript)
                }
                val summaryConfig = config.copy(streamingEnabled = false, reasoningSummaryEnabled = false)
                val result = client.sendMessage(
                    config = summaryConfig,
                    messages = listOf(
                        RequestMessage(
                            role = "system",
                            content = PromptPolicy.summarySystemPrompt(promptSettings),
                        ),
                        RequestMessage(role = "user", content = prompt),
                    ),
                )
                result.responseText.trim().take(SUMMARY_MAX_CHARS)
            }
        },
    )
    private val conversationPersistenceCoordinator = ConversationPersistenceCoordinator(
        scope = viewModelScope,
        persistSnapshot = { snapshot ->
            conversationStore.save(
                snapshot.conversations,
                snapshot.selectedConversationId,
                snapshot.deletedConversationIds,
            )
        },
    )
    private val conversationLoadCoordinator = ConversationLoadCoordinator(
        scope = viewModelScope,
        loadMessages = { conversationId ->
            conversationStore.loadConversationMessages(conversationId).map { it.toChatMessage() }
        },
    )
    // long: 普通聊天的持久化、上下文准备和网络发送顺序由应用服务统一；ViewModel 只消费事件并更新 Compose 状态。
    private val conversationSendCoordinator = ConversationSendCoordinator(
        persistSnapshot = conversationPersistenceCoordinator::persist,
        prepareRequestContext = conversationRequestContextPreparer::prepare,
        sendRequest = { config, messages, onStreamDelta ->
            client.sendMessage(config, messages, onStreamDelta)
        },
    )
    private val agentRunUseCase = AgentRunUseCase(application, client)
    private val agentProfileStore = RoomAgentProfileStore(application)
    private val agentRunRepository = RoomAgentRunRepository(application)
    private val agentMemoryStore = RoomAgentMemoryStore(application)
    private val workflowRepository = RoomWorkflowRepository(application)
    private val processExitObservationStore = RoomProcessExitObservationStore(application)
    private val scheduledTaskScheduler: ScheduledTaskScheduler = WorkManagerScheduledTaskScheduler(application)
    private val startupRecoveryCoordinator = StartupRecoveryCoordinator(
        processExecutionRegistry = ScheduledWorkflowProcessExecutionRegistry.process,
        loadAgentRunIds = agentRunRepository::activeRunIds,
        loadWorkflowCandidates = workflowRepository::startupRecoveryCandidates,
    )
    private val scheduledWorkflowStopFallback = ScheduledWorkflowStopFallbackCoordinator(
        loadTask = workflowRepository::getScheduledTask,
        loadWorkflowRun = workflowRepository::runDetail,
        cancelAgentRun = agentRunUseCase::cancelActiveRunForScheduledTaskStop,
        settleWorkflowAndTask = { taskId, workflowRunId, reason ->
            workflowRepository.settleScheduledWorkflowRun(
                taskId = taskId,
                workflowRunId = workflowRunId,
                workflowStatus = WorkflowRunStatus.CANCELLED,
                taskStatus = ScheduledTaskStatus.CANCELLED,
                errorMessage = reason,
            )
        },
        settleTaskWithoutWorkflow = { taskId, reason ->
            workflowRepository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
        },
    )
    private val scheduledWorkflowStopCoordinator = ScheduledWorkflowStopCoordinator(
        loadTask = workflowRepository::getScheduledTask,
        cancelPendingTask = workflowRepository::cancelScheduledTask,
        requestScheduledTaskStop = { taskId ->
            workflowRepository.requestScheduledTaskStop(taskId, "用户请求停止后台工作流")
        },
        cancelSystemWork = scheduledTaskScheduler::cancel,
        waitForWorkerSettlement = { delay(100L) },
        reconcileUnsettledTask = scheduledWorkflowStopFallback::reconcile,
    )
    private val backupManager = XiaoLingBackupManager(application)
    private var streamingThrottleJob: Job? = null
    private var pendingStreamingUpdate: StreamDeltaUpdate? = null
    private var pendingApprovalDecision: CompletableDeferred<ApprovalDecision>? = null
    private val activeAgentRunsByConversation = mutableMapOf<String, AgentRunSnapshot>()
    private val pendingAgentApprovalsByConversation = mutableMapOf<String, AgentApprovalUiState>()
    private var sendMessageJob: Job? = null
    private var memoryLoadJob: Job? = null
    private var memorySearchJob: Job? = null
    private var memoryCandidateLoadJob: Job? = null
    private var skillLoadJob: Job? = null
    private var workflowLoadJob: Job? = null
    private var saveProfilesJob: Job? = null
    private var knowledgeReferenceStatusJob: Job? = null
    private var processExitObservationLoadJob: Job? = null

    var uiState by mutableStateOf(
        initialUiState(
            themeMode = uiPreferenceStore.loadThemeMode(),
            promptSettings = uiPreferenceStore.loadPromptSettings(),
            memoryCandidatesEnabled = uiPreferenceStore.loadMemoryCandidatesEnabled(),
            userAgent = uiPreferenceStore.loadUserAgent(),
            reasoningSummaryEnabled = uiPreferenceStore.loadReasoningSummaryEnabled(),
        ),
    )
        private set

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // long: 前台启动只补采系统退出观察；采集失败不能阻塞聊天和恢复，也不能把时间邻近的退出强行归因到某个旧 Run。
                collectProcessExitObservationsBestEffort {
                    processExitObservationStore.collect()
                }
            }
            val recoveryCandidates = withContext(Dispatchers.IO) {
                // long: 启动恢复先冻结旧进程候选，并与当前进程已注册 Worker 隔离；后续每个恢复步骤只消费这份快照，不能重新全库扫描误伤新执行。
                startupRecoveryCoordinator.capture()
            }
            val (resumableApprovalRuns, resumableCommittedToolRuns, resumableVerifiedToolRuns) = withContext(Dispatchers.IO) {
                val approvals = agentRunRepository.recoverPendingApprovalRuns(recoveryCandidates.agentRunIds)
                val committedTools = agentRunUseCase.recoverCommittedToolRuns(recoveryCandidates.agentRunIds)
                val verifiedTools = agentRunUseCase.recoverVerifiedToolRuns(recoveryCandidates.agentRunIds)
                // long: 生产 Registry 已参与未验证结果的证据判定；只保留待审批、完整幂等证据或已落库 PASSED 验证的候选，其余中间态继续 fail-closed 收敛。
                agentRunUseCase.closeInterruptedRuns(recoveryCandidates.agentRunIds)
                Triple(approvals, committedTools, verifiedTools)
            }
            val workflowState = withContext(Dispatchers.IO) {
                // long: Agent Run 先完成恢复收敛，Workflow Ledger 再依据真实 Agent 终态对账，避免把已经取消的执行继续显示为运行中。
                workflowRepository.reconcileInterruptedRuns(
                    resumableAgentRunIds = (resumableCommittedToolRuns + resumableVerifiedToolRuns)
                        .map { it.snapshot.run.id }
                        .toSet(),
                    workflowRunIds = recoveryCandidates.workflowRunIds,
                )
                workflowRepository.reconcileInterruptedScheduledTasks(recoveryCandidates.scheduledTaskIds)
                workflowRepository.reconcileWorkflowSchedules().forEach { task ->
                    try {
                        val workRequestId = scheduledTaskScheduler.enqueue(task)
                        workflowRepository.attachWorkRequest(task.id, workRequestId)
                    } catch (error: Throwable) {
                        // long: 周期实例已在 Room 中占位，启动补队失败时收敛该实例，保留规则供下次启动继续计算未来触发，不让初始化整体失败。
                        workflowRepository.failScheduling(task.id, error.message ?: "恢复周期任务入队失败")
                    }
                }
                loadWorkflowUiData()
            }
            val latestDeletedMemory = withContext(Dispatchers.IO) {
                // long: 删除撤销快照独立于页面内存；启动时与 Room 正式记录核对后恢复入口，保证进程重建不会丢失最近一次撤销机会。
                agentMemoryStore.latestDeleted()
            }
            val storedProfiles = configStore.load()
            val storedConversations = conversationStore.load()
            val availableAgentTools = agentRunUseCase.registeredTools()
            val availableSkills = withContext(Dispatchers.IO) { agentRunUseCase.listSkills() }
            val defaultProvider = storedProfiles.profiles
                .firstOrNull { it.id == storedProfiles.selectedProfileId }
                ?: storedProfiles.profiles.first()
            val now = System.currentTimeMillis()
            val storedAgentProfiles = withContext(Dispatchers.IO) {
                agentProfileStore.loadOrCreateDefault(
                    AgentProfileRecord(
                        id = DEFAULT_AGENT_PROFILE_ID,
                        name = "默认 Agent",
                        avatar = "灵",
                        providerId = defaultProvider.id,
                        model = defaultProvider.model.takeIf { it in defaultProvider.enabledModels }.orEmpty(),
                        apiMode = ApiMode.CHAT_COMPLETIONS,
                        systemPrompt = "",
                        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                        allowedToolNames = availableAgentTools.map { it.name },
                        allowedSkillIds = availableSkills.filter { it.enabled }.map { it.definition.id },
                        memoryEnabled = true,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            // long: Room/Keystore 读取放在协程里执行，首屏先用安全的空白状态，避免应用启动阶段因为解密或数据库迁移阻塞主线程。
            uiState = storedProfiles
                .toUiState()
                .withConversations(storedConversations)
                .copy(
                    themeMode = uiState.themeMode,
                    promptSettings = uiState.promptSettings,
                    memoryCandidatesEnabled = uiState.memoryCandidatesEnabled,
                    userAgent = uiState.userAgent,
                    reasoningSummaryEnabled = uiState.reasoningSummaryEnabled,
                    agentProfiles = storedAgentProfiles.profiles,
                    selectedAgentProfileId = storedAgentProfiles.selectedProfileId,
                    registeredAgentTools = availableAgentTools,
                    skills = availableSkills,
                    agentMemoryRecallEnabled = storedAgentProfiles.profiles
                        .first { it.id == storedAgentProfiles.selectedProfileId }
                        .memoryEnabled,
                    deletedMemoryForUndo = latestDeletedMemory,
                    workflows = workflowState.workflows,
                    workflowRuns = workflowState.runs,
                    scheduledTasks = workflowState.tasks,
                    workflowSchedules = workflowState.schedules,
                    result = uiState.result,
                )
            restoreRecoveredAgentRuns(resumableApprovalRuns)
            resumeRecoveredCommittedToolRuns(resumableCommittedToolRuns)
            resumeRecoveredVerifiedToolRuns(resumableVerifiedToolRuns)
        }
    }

    fun selectProfile(profileId: String) {
        val profile = uiState.profiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.fromProfile(profile, profileId)
        saveProfilesSnapshot(uiState.profiles, profileId)
    }

    fun updateModel(value: String) {
        if (value.isBlank()) return
        val allowed = uiState.enabledModels
        if (allowed.isNotEmpty() && value !in allowed) {
            showValidation("这个模型没有在设置页的模型提供方管理中勾选")
            return
        }
        updateAndSaveSelectedProfile { copy(model = value, result = null) }
    }

    fun updatePrompt(value: String) {
        uiState = uiState.copy(prompt = value, result = null)
    }

    fun attachImage(uri: Uri) {
        if (uiState.sendingMessage || uiState.attachingImage || uiState.attachingDocument) return
        uiState = uiState.copy(attachingImage = true, result = null)
        viewModelScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) { imageAttachmentReader.read(uri) }
                uiState = uiState.copy(
                    pendingImage = attachment,
                    pendingDocument = null,
                    attachingImage = false,
                    result = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    attachingImage = false,
                    result = OperationResult(
                        success = false,
                        title = "图片不可用",
                        message = error.message ?: "无法读取所选图片",
                    ),
                )
            }
        }
    }

    fun removePendingImage() {
        if (uiState.sendingMessage || uiState.attachingImage || uiState.attachingDocument) return
        uiState = uiState.copy(pendingImage = null, result = null)
    }

    fun attachDocument(uri: Uri) {
        if (uiState.sendingMessage || uiState.attachingImage || uiState.attachingDocument) return
        uiState = uiState.copy(attachingDocument = true, result = null)
        viewModelScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) { documentAttachmentReader.read(uri) }
                uiState = uiState.copy(
                    pendingImage = null,
                    pendingDocument = attachment,
                    attachingDocument = false,
                    result = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    attachingDocument = false,
                    result = OperationResult(
                        success = false,
                        title = "文档不可用",
                        message = error.message ?: "无法读取所选文档",
                    ),
                )
            }
        }
    }

    fun removePendingDocument() {
        if (uiState.sendingMessage || uiState.attachingImage || uiState.attachingDocument) return
        uiState = uiState.copy(pendingDocument = null, result = null)
    }

    fun updateAgentMemoryRecallEnabled(value: Boolean) {
        val selectedAgent = uiState.agentProfiles.firstOrNull { it.id == uiState.selectedAgentProfileId }
        if (value && selectedAgent?.memoryEnabled == false) {
            showValidation("当前 Agent Profile 已关闭长期记忆")
            return
        }
        uiState = uiState.copy(agentMemoryRecallEnabled = value, result = null)
    }

    fun selectAgentProfile(profileId: String) {
        val profile = uiState.agentProfiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.copy(
            selectedAgentProfileId = profile.id,
            agentMemoryRecallEnabled = profile.memoryEnabled,
            agentProfileError = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            if (!agentProfileStore.select(profile.id)) {
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(agentProfileError = "Agent Profile 已不存在，请刷新")
                }
            }
        }
    }

    fun saveAgentProfile(
        profileId: String?,
        name: String,
        avatar: String,
        providerId: String,
        model: String,
        apiMode: ApiMode,
        systemPrompt: String,
        memoryEnabled: Boolean,
        allowedToolNames: Set<String>,
        allowedSkillIds: Set<String>,
    ) {
        val provider = uiState.profiles.firstOrNull { it.id == providerId }
        if (provider == null) {
            showValidation("Agent 选择的模型提供方不存在")
            return
        }
        if (model !in provider.enabledModels) {
            showValidation("Agent 选择的模型没有在提供方中启用")
            return
        }
        val registeredToolNames = uiState.registeredAgentTools.mapTo(linkedSetOf()) { it.name }
        val unknownTools = allowedToolNames - registeredToolNames
        if (unknownTools.isNotEmpty()) {
            showValidation("Agent 包含未注册工具：${unknownTools.sorted().joinToString()}")
            return
        }
        val knownSkills = uiState.skills.associateBy { it.definition.id }
        val unknownSkills = allowedSkillIds - knownSkills.keys
        if (unknownSkills.isNotEmpty()) {
            showValidation("Agent 包含不存在的 Skill：${unknownSkills.sorted().joinToString()}")
            return
        }
        val incompatibleSkill = allowedSkillIds
            .mapNotNull(knownSkills::get)
            .firstOrNull { skill -> skill.definition.toolNames.any { it !in allowedToolNames } }
        if (incompatibleSkill != null) {
            showValidation("Skill ${incompatibleSkill.definition.name} 使用了未授权工具，请先勾选对应工具")
            return
        }
        val old = profileId?.let { id -> uiState.agentProfiles.firstOrNull { it.id == id } }
        val now = System.currentTimeMillis()
        val profile = AgentProfileRecord(
            id = old?.id ?: "agent-profile-${UUID.randomUUID()}",
            name = name.trim(),
            avatar = avatar.trim(),
            providerId = provider.id,
            model = model,
            apiMode = apiMode,
            systemPrompt = systemPrompt.trim(),
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = allowedToolNames.sorted(),
            allowedSkillIds = allowedSkillIds.sorted(),
            memoryEnabled = memoryEnabled,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        runCatching { AgentProfilePolicy.validateRunnable(profile) }
            .onFailure {
                showValidation(it.message ?: "Agent Profile 配置无效")
                return
            }
        uiState = uiState.copy(mutatingAgentProfileIds = uiState.mutatingAgentProfileIds + profile.id)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { agentProfileStore.upsert(profile) } }
                .onSuccess {
                    val profiles = (uiState.agentProfiles.filterNot { it.id == profile.id } + profile)
                        .sortedWith(compareByDescending<AgentProfileRecord> { it.updatedAt }.thenBy { it.name })
                    uiState = uiState.copy(
                        agentProfiles = profiles,
                        selectedAgentProfileId = profile.id,
                        agentMemoryRecallEnabled = profile.memoryEnabled,
                        mutatingAgentProfileIds = uiState.mutatingAgentProfileIds - profile.id,
                        agentProfileError = null,
                        result = OperationResult(true, "已保存", "Agent Profile：${profile.name}"),
                    )
                    withContext(Dispatchers.IO) { agentProfileStore.select(profile.id) }
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        mutatingAgentProfileIds = uiState.mutatingAgentProfileIds - profile.id,
                        agentProfileError = error.message ?: "保存 Agent Profile 失败",
                    )
                }
        }
    }

    fun deleteAgentProfile(profileId: String) {
        if (uiState.agentProfiles.size <= 1) {
            showValidation("至少保留一个 Agent Profile")
            return
        }
        val profile = uiState.agentProfiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.copy(mutatingAgentProfileIds = uiState.mutatingAgentProfileIds + profileId)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { agentProfileStore.delete(profileId) } }
                .onSuccess { deleted ->
                    if (!deleted) error("Agent Profile 已不存在")
                    val remaining = uiState.agentProfiles.filterNot { it.id == profileId }
                    val selectedId = if (uiState.selectedAgentProfileId == profileId) remaining.first().id else uiState.selectedAgentProfileId
                    val selected = remaining.first { it.id == selectedId }
                    withContext(Dispatchers.IO) { agentProfileStore.select(selectedId) }
                    uiState = uiState.copy(
                        agentProfiles = remaining,
                        selectedAgentProfileId = selectedId,
                        agentMemoryRecallEnabled = selected.memoryEnabled,
                        mutatingAgentProfileIds = uiState.mutatingAgentProfileIds - profileId,
                        agentProfileError = null,
                        result = OperationResult(true, "已删除", "Agent Profile：${profile.name}"),
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        mutatingAgentProfileIds = uiState.mutatingAgentProfileIds - profileId,
                        agentProfileError = error.message ?: "删除 Agent Profile 失败",
                    )
                }
        }
    }

    fun openNewProvider() {
        uiState = uiState.copy(
            manageDraft = ProviderEditDraft(
                id = null,
                name = "",
                baseUrl = "",
                apiKey = "",
                upstreamModels = emptyList(),
                enabledModels = emptySet(),
            ),
            result = null,
        )
    }

    fun openEditProvider(profileId: String) {
        val profile = uiState.profiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.copy(
            manageDraft = ProviderEditDraft(
                id = profile.id,
                name = profile.name,
                baseUrl = profile.baseUrl,
                apiKey = profile.apiKey,
                upstreamModels = profile.availableModels,
                enabledModels = profile.enabledModels.toSet(),
            ),
            result = null,
        )
    }

    fun closeProviderEditor() {
        uiState = uiState.copy(manageDraft = null, result = null)
    }

    fun clearResult() {
        uiState = uiState.copy(result = null)
    }

    fun defaultBackupFileName(): String {
        return "xiaoling-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"
    }

    fun exportBackup(uri: android.net.Uri) {
        if (uiState.backupBusy) return
        uiState = uiState.copy(backupBusy = true, result = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { backupManager.export(uri) }
            }.onSuccess { manifest ->
                uiState = uiState.copy(
                    backupBusy = false,
                    result = OperationResult(
                        success = true,
                        title = "备份已导出",
                        message = "Room v${manifest.schemaVersion} 数据已写入所选文件；Provider 密文仍依赖当前设备 Keystore",
                    ),
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    backupBusy = false,
                    result = OperationResult(false, "备份导出失败", error.message ?: "无法导出备份"),
                )
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        if (uiState.backupBusy) return
        uiState = uiState.copy(backupBusy = true, result = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { backupManager.restore(uri) }
            }.onSuccess { restored ->
                uiState = uiState.copy(
                    backupBusy = false,
                    backupRestartRequired = restored.restartRequired,
                    result = OperationResult(
                        success = true,
                        title = "备份已恢复",
                        message = "Room v${restored.manifest.schemaVersion} 已替换当前数据库，请退出并重新打开应用；Keystore 密文只能在原设备密钥仍存在时解密",
                    ),
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    backupBusy = false,
                    result = OperationResult(false, "备份恢复失败", error.message ?: "无法恢复备份"),
                )
            }
        }
    }

    fun updateDraftName(value: String) = updateDraft { copy(name = value) }
    fun updateDraftBaseUrl(value: String) = updateDraft { copy(baseUrl = value) }
    fun updateDraftApiKey(value: String) = updateDraft { copy(apiKey = value) }
    fun updateUserAgent(value: String) {
        val normalized = value.filterNot { it == '\r' || it == '\n' }.take(512)
        uiState = uiState.copy(userAgent = normalized, result = null)
        uiPreferenceStore.saveUserAgent(normalized)
    }

    fun resetUserAgent() {
        updateUserAgent(ProviderRequestConfig.DEFAULT_USER_AGENT)
    }
    fun updateApiMode(value: ApiMode) {
        uiState = uiState.copy(apiMode = value, result = null)
    }
    fun updateStreamingEnabled(value: Boolean) {
        uiState = uiState.copy(streamingEnabled = value, result = null)
    }

    fun updateReasoningSummaryEnabled(value: Boolean) {
        uiState = uiState.copy(reasoningSummaryEnabled = value, result = null)
        uiPreferenceStore.saveReasoningSummaryEnabled(value)
    }

    fun updateResponsesEnabled(value: Boolean) {
        updateApiMode(if (value) ApiMode.RESPONSES else ApiMode.CHAT_COMPLETIONS)
    }

    fun updateThemeMode(value: AppThemeMode) {
        // long: 主题切换是即时视觉偏好，选择后立即保存，避免用户夜间重开应用又回到刺眼亮色。
        uiState = uiState.copy(themeMode = value, result = null)
        uiPreferenceStore.saveThemeMode(value)
    }

    fun updateChatPromptEnabled(value: Boolean) = updatePromptSettings {
        copy(chatPromptEnabled = value)
    }

    fun updateChatPrompt(value: String) = updatePromptSettings {
        copy(chatPrompt = value)
    }

    fun restoreChatPrompt() = updatePromptSettings {
        copy(chatPrompt = PromptDefaults.CHAT)
    }

    fun updateSummaryPromptEnabled(value: Boolean) = updatePromptSettings {
        copy(summaryPromptEnabled = value)
    }

    fun updateSummaryPrompt(value: String) = updatePromptSettings {
        copy(summaryPrompt = value)
    }

    fun restoreSummaryPrompt() = updatePromptSettings {
        copy(summaryPrompt = PromptDefaults.SUMMARY)
    }

    fun updateAgentSummaryPromptEnabled(value: Boolean) = updatePromptSettings {
        copy(agentSummaryPromptEnabled = value)
    }

    fun updateAgentSummaryPrompt(value: String) = updatePromptSettings {
        copy(agentSummaryPrompt = value)
    }

    fun restoreAgentSummaryPrompt() = updatePromptSettings {
        copy(agentSummaryPrompt = PromptDefaults.AGENT_SUMMARY)
    }

    fun stopGenerating() {
        // long: 停止生成是用户接管当前 Run 的入口，必须取消真实网络请求，而不是只隐藏 loading。
        pendingApprovalDecision?.cancel()
        sendMessageJob?.cancel()
    }

    fun refreshAgentRunHistory() {
        if (uiState.loadingAgentRunHistory) return
        uiState = uiState.copy(loadingAgentRunHistory = true, agentRunHistoryError = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    agentRunRepository.recentRunDetails(AGENT_RUN_HISTORY_LIMIT)
                }
            }.onSuccess { history ->
                val selectedId = uiState.selectedAgentRunId
                    ?.takeIf { id -> history.any { it.snapshot.run.id == id } }
                    ?: history.firstOrNull()?.snapshot?.run?.id
                uiState = uiState.copy(
                    loadingAgentRunHistory = false,
                    agentRunHistory = history,
                    selectedAgentRunId = selectedId,
                    agentRunHistoryError = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    loadingAgentRunHistory = false,
                    agentRunHistoryError = error.message ?: "无法读取 Agent 任务",
                )
            }
        }
    }

    fun selectAgentRun(runId: String) {
        if (uiState.agentRunHistory.none { it.snapshot.run.id == runId }) return
        uiState = uiState.copy(selectedAgentRunId = runId)
    }

    fun refreshProcessExitObservations() {
        processExitObservationLoadJob?.cancel()
        uiState = uiState.copy(
            loadingProcessExitObservations = true,
            processExitObservationError = null,
        )
        processExitObservationLoadJob = viewModelScope.launch {
            try {
                val observations = withContext(Dispatchers.IO) {
                    // long: 诊断页刷新只读取已经落库的系统证据，不再次触发平台采集，避免用户查看页面改变观察样本。
                    processExitObservationStore.latest()
                }
                uiState = uiState.copy(
                    loadingProcessExitObservations = false,
                    processExitObservations = observations,
                    processExitObservationError = null,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                uiState = uiState.copy(
                    loadingProcessExitObservations = false,
                    processExitObservationError = error.message ?: "无法读取进程退出记录",
                )
            }
        }
    }

    fun refreshMemories() {
        loadMemories()
        loadMemoryCandidates()
    }

    fun refreshSkills() {
        skillLoadJob?.cancel()
        uiState = uiState.copy(loadingSkills = true, skillError = null)
        skillLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentRunUseCase.listSkills() }
            }.onSuccess { skills ->
                uiState = uiState.copy(loadingSkills = false, skills = skills, skillError = null)
            }.onFailure { error ->
                uiState = uiState.copy(
                    loadingSkills = false,
                    skillError = error.message ?: "无法读取 Skill",
                )
            }
        }
    }

    private suspend fun loadWorkflowUiData() = WorkflowUiData(
        workflows = workflowRepository.listWorkflows(),
        runs = workflowRepository.allRunDetails(),
        tasks = workflowRepository.listScheduledTasks(),
        schedules = workflowRepository.listWorkflowSchedules(),
    )

    fun refreshWorkflows() {
        workflowLoadJob?.cancel()
        uiState = uiState.copy(loadingWorkflows = true, workflowError = null)
        workflowLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { loadWorkflowUiData() }
            }.onSuccess { data ->
                uiState = uiState.copy(
                    loadingWorkflows = false,
                    workflows = data.workflows,
                    workflowRuns = data.runs,
                    scheduledTasks = data.tasks,
                    workflowSchedules = data.schedules,
                    workflowError = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    loadingWorkflows = false,
                    workflowError = error.message ?: "无法读取工作流",
                )
            }
        }
    }

    fun createWorkflow(name: String, stepGoals: List<String>) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    workflowRepository.createWorkflow(name, stepGoals.map(::WorkflowStepDefinitionInput))
                }
            }.onSuccess { workflow ->
                uiState = uiState.copy(
                    result = OperationResult(true, "工作流已保存", workflow.name),
                    workflowError = null,
                )
                refreshWorkflows()
            }.onFailure { error ->
                uiState = uiState.copy(workflowError = error.message ?: "保存工作流失败")
            }
        }
    }

    fun updateWorkflow(workflowId: String, name: String, stepGoals: List<String>) {
        if (workflowId in uiState.mutatingWorkflowIds) return
        uiState = uiState.copy(
            mutatingWorkflowIds = uiState.mutatingWorkflowIds + workflowId,
            workflowError = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    workflowRepository.updateWorkflow(
                        workflowId = workflowId,
                        name = name,
                        steps = stepGoals.map(::WorkflowStepDefinitionInput),
                    )
                }
            }.onSuccess { workflow ->
                uiState = uiState.copy(
                    mutatingWorkflowIds = uiState.mutatingWorkflowIds - workflowId,
                    result = OperationResult(true, "工作流已更新", workflow.name),
                    workflowError = null,
                )
                refreshWorkflows()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingWorkflowIds = uiState.mutatingWorkflowIds - workflowId,
                    workflowError = error.message ?: "更新工作流失败",
                )
            }
        }
    }

    fun requestWorkflowRunRetry(runId: String) {
        if (uiState.sendingMessage || uiState.runningWorkflowId != null) {
            showValidation("当前已有任务正在执行，请等待结束后再重试")
            return
        }
        val detail = uiState.workflowRuns.firstOrNull { it.run.id == runId }
        if (detail == null) {
            showValidation("找不到要重试的 Workflow Run，请刷新后再试")
            return
        }
        val workflow = uiState.workflows.firstOrNull { it.id == detail.run.workflowId }
        if (workflow == null || !workflow.enabled) {
            showValidation("工作流不存在或已停用，不能重试")
            return
        }
        val hasActiveRun = uiState.workflowRuns.any {
            it.run.workflowId == detail.run.workflowId &&
                it.run.id != detail.run.id &&
                it.run.status in setOf(WorkflowRunStatus.QUEUED, WorkflowRunStatus.RUNNING)
        }
        when (val eligibility = WorkflowRunRetryPolicy.evaluate(detail, hasActiveRun)) {
            is WorkflowRunRetryEligibility.NotRetryable -> showValidation(eligibility.reason)
            is WorkflowRunRetryEligibility.Retryable -> {
                val workflowName = workflow.name
                if (eligibility.requiresConfirmation) {
                    uiState = uiState.copy(
                        pendingWorkflowRetryConfirmation = WorkflowRetryConfirmationUiState(
                            runId = runId,
                            workflowName = workflowName,
                            retryFromSequence = eligibility.retryFromSequence,
                            reusedStepCount = eligibility.reusedStepCount,
                        ),
                    )
                } else {
                    startWorkflowRunRetry(detail)
                }
            }
        }
    }

    fun confirmWorkflowRunRetry() {
        val pending = uiState.pendingWorkflowRetryConfirmation ?: return
        uiState = uiState.copy(pendingWorkflowRetryConfirmation = null)
        val detail = uiState.workflowRuns.firstOrNull { it.run.id == pending.runId }
        if (detail == null) {
            showValidation("来源 Workflow Run 已不存在，请刷新后再试")
            return
        }
        startWorkflowRunRetry(detail)
    }

    fun cancelWorkflowRunRetry() {
        uiState = uiState.copy(pendingWorkflowRetryConfirmation = null)
    }

    private fun startWorkflowRunRetry(sourceDetail: WorkflowRunDetail) {
        val sourceRun = sourceDetail.run
        val conversation = uiState.conversations.firstOrNull { it.id == sourceRun.conversationId }
        if (conversation == null) {
            showValidation("来源会话已不存在，无法在原上下文中重试")
            return
        }
        val runtimeSelection = validatedSelectedAgentRuntimeSelection() ?: return
        selectConversation(sourceRun.conversationId)
        uiState = uiState.copy(
            runningWorkflowId = sourceRun.workflowId,
            pendingWorkflowRetryConfirmation = null,
            workflowError = null,
            workflowNavigationConversationId = sourceRun.conversationId,
            sendingMessage = true,
        )
        clearAgentStateForConversation(sourceRun.conversationId)
        sendMessageJob = viewModelScope.launch {
            var retryDetail: WorkflowRunDetail? = null
            try {
                retryDetail = withContext(Dispatchers.IO) {
                    workflowRepository.retryRun(sourceRun.id, sourceRun.conversationId)
                }
                // long: 重试记录先进入 UI，再启动首个未完成步骤；来源 Run 与复用步骤引用始终保留，便于用户核对新旧执行证据。
                uiState = uiState.copy(workflowRuns = listOf(retryDetail) + uiState.workflowRuns)
                executeForegroundWorkflow(retryDetail, runtimeSelection, sourceRun.conversationId)
            } catch (error: CancellationException) {
                retryDetail?.let { current ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            current.run.id,
                            WorkflowRunStatus.CANCELLED,
                            errorMessage = "用户停止工作流重试",
                        )
                    }
                }
                appendWorkflowMessage(
                    sourceRun.conversationId,
                    ChatMessage(role = "error", text = "已停止工作流重试", createdAt = System.currentTimeMillis()),
                )
            } catch (error: Throwable) {
                val failure = error.message ?: "工作流重试失败"
                retryDetail?.let { current ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            current.run.id,
                            WorkflowRunStatus.FAILED,
                            errorMessage = failure,
                        )
                    }
                }
                appendWorkflowMessage(
                    sourceRun.conversationId,
                    ChatMessage(
                        role = "error",
                        text = "工作流重试失败\n$failure",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                uiState = uiState.copy(workflowError = failure)
            } finally {
                pendingApprovalDecision = null
                clearPendingApprovalForConversation(sourceRun.conversationId)
                uiState = uiState.copy(runningWorkflowId = null, sendingMessage = false)
                sendMessageJob = null
                refreshWorkflows()
                saveConversationSelection()
            }
        }
    }

    fun setWorkflowEnabled(workflowId: String, enabled: Boolean) {
        if (workflowId in uiState.mutatingWorkflowIds) return
        uiState = uiState.copy(
            mutatingWorkflowIds = uiState.mutatingWorkflowIds + workflowId,
            workflowError = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val updated = workflowRepository.setEnabled(workflowId, enabled)
                    val pendingTasks = if (!enabled && updated != null) {
                        workflowRepository.listScheduledTasks().filter {
                            it.workflowId == workflowId && it.status == ScheduledTaskStatus.SCHEDULED
                        }
                    } else {
                        emptyList()
                    }
                    var workManagerCancelFailures = 0
                    pendingTasks.forEach { task ->
                        workflowRepository.cancelScheduledTask(task.id)
                        try {
                            scheduledTaskScheduler.cancel(task.id)
                        } catch (_: Throwable) {
                            // long: Room 已取消后 Worker 会在领取阶段跳过；系统取消失败只影响残留 WorkRequest，不得把已成功停用的工作流回报成失败。
                            workManagerCancelFailures += 1
                        }
                    }
                    Triple(updated, pendingTasks.size, workManagerCancelFailures)
                }
            }.onSuccess { (updated, cancelledTaskCount, workManagerCancelFailures) ->
                uiState = uiState.copy(
                    mutatingWorkflowIds = uiState.mutatingWorkflowIds - workflowId,
                    result = OperationResult(
                        success = updated != null,
                        title = if (enabled) "工作流已启用" else "工作流已停用",
                        message = updated?.let {
                            when {
                                workManagerCancelFailures > 0 -> "${it.name} · $cancelledTaskCount 个计划已在本地取消，系统队列稍后会安全跳过"
                                cancelledTaskCount > 0 -> "${it.name} · 已取消 $cancelledTaskCount 个待执行计划"
                                else -> it.name
                            }
                        } ?: "工作流不存在",
                    ),
                )
                refreshWorkflows()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingWorkflowIds = uiState.mutatingWorkflowIds - workflowId,
                    workflowError = error.message ?: "更新工作流失败",
                )
            }
        }
    }

    fun scheduleWorkflowOnce(workflowId: String, delayMinutes: Int) {
        if (uiState.schedulingWorkflowId != null) return
        val workflow = uiState.workflows.firstOrNull { it.id == workflowId }
        if (workflow == null || !workflow.enabled) {
            showValidation("工作流不存在或已停用")
            return
        }
        uiState = uiState.copy(schedulingWorkflowId = workflowId, workflowError = null)
        viewModelScope.launch {
            var task: ScheduledTaskRecord? = null
            try {
                task = withContext(Dispatchers.IO) {
                    workflowRepository.createOneTimeScheduledTask(workflowId, delayMinutes)
                }
                val workRequestId = withContext(Dispatchers.IO) { scheduledTaskScheduler.enqueue(task) }
                withContext(Dispatchers.IO) { workflowRepository.attachWorkRequest(task.id, workRequestId) }
                uiState = uiState.copy(
                    schedulingWorkflowId = null,
                    result = OperationResult(
                        true,
                        "一次性计划已创建",
                        "${workflow.name} · 系统将在计划时间后尽快执行",
                    ),
                )
                refreshWorkflows()
            } catch (error: Throwable) {
                task?.let { created ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            workflowRepository.failScheduling(created.id, error.message ?: "WorkManager 入队失败")
                        }
                    }
                }
                uiState = uiState.copy(
                    schedulingWorkflowId = null,
                    workflowError = error.message ?: "创建一次性计划失败",
                )
                refreshWorkflows()
            }
        }
    }

    fun scheduleWorkflowRecurring(
        workflowId: String,
        type: WorkflowScheduleType,
        hour: Int,
        minute: Int,
        dayOfWeek: Int?,
    ) {
        if (uiState.schedulingWorkflowId != null) return
        val workflow = uiState.workflows.firstOrNull { it.id == workflowId }
        if (workflow == null || !workflow.enabled) {
            showValidation("工作流不存在或已停用")
            return
        }
        uiState = uiState.copy(schedulingWorkflowId = workflowId, workflowError = null)
        viewModelScope.launch {
            var createdTask: ScheduledTaskRecord? = null
            try {
                val plan = withContext(Dispatchers.IO) {
                    workflowRepository.createOrReplaceWorkflowSchedule(workflowId, type, hour, minute, dayOfWeek)
                }
                createdTask = plan.task
                plan.replacedTaskId?.let { replacedTaskId ->
                    runCatching { withContext(Dispatchers.IO) { scheduledTaskScheduler.cancel(replacedTaskId) } }
                }
                val workRequestId = withContext(Dispatchers.IO) { scheduledTaskScheduler.enqueue(plan.task) }
                withContext(Dispatchers.IO) { workflowRepository.attachWorkRequest(plan.task.id, workRequestId) }
                uiState = uiState.copy(
                    schedulingWorkflowId = null,
                    result = OperationResult(
                        true,
                        if (type == WorkflowScheduleType.DAILY) "每日计划已创建" else "每周计划已创建",
                        "${workflow.name} · ${plan.schedule.zoneId} · 系统将在计划时间后尽快执行",
                    ),
                )
                refreshWorkflows()
            } catch (error: Throwable) {
                createdTask?.let { task ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            workflowRepository.failScheduling(task.id, error.message ?: "周期任务入队失败")
                        }
                    }
                }
                uiState = uiState.copy(
                    schedulingWorkflowId = null,
                    workflowError = error.message ?: "创建周期计划失败",
                )
                refreshWorkflows()
            }
        }
    }

    fun cancelWorkflowSchedule(scheduleId: String) {
        if (scheduleId in uiState.mutatingWorkflowScheduleIds) return
        uiState = uiState.copy(
            mutatingWorkflowScheduleIds = uiState.mutatingWorkflowScheduleIds + scheduleId,
            workflowError = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val cancellation = workflowRepository.cancelWorkflowSchedule(scheduleId)
                    var systemCancelFailed = false
                    cancellation?.cancelledTaskId?.let { taskId ->
                        try {
                            scheduledTaskScheduler.cancel(taskId)
                        } catch (_: Throwable) {
                            systemCancelFailed = true
                        }
                    }
                    cancellation to systemCancelFailed
                }
            }.onSuccess { (cancellation, systemCancelFailed) ->
                uiState = uiState.copy(
                    mutatingWorkflowScheduleIds = uiState.mutatingWorkflowScheduleIds - scheduleId,
                    result = OperationResult(
                        cancellation != null,
                        "周期计划已停用",
                        when {
                            cancellation == null -> "周期计划不存在"
                            systemCancelFailed -> "本地规则已停用；残留系统任务到时会安全跳过"
                            else -> "后续不会再生成新的执行实例"
                        },
                    ),
                )
                refreshWorkflows()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingWorkflowScheduleIds = uiState.mutatingWorkflowScheduleIds - scheduleId,
                    workflowError = error.message ?: "停用周期计划失败",
                )
            }
        }
    }

    fun cancelScheduledTask(taskId: String) {
        if (taskId in uiState.mutatingScheduledTaskIds) return
        val task = uiState.scheduledTasks.firstOrNull { it.id == taskId }
        if (task == null || task.status !in setOf(ScheduledTaskStatus.SCHEDULED, ScheduledTaskStatus.RUNNING)) return
        uiState = uiState.copy(
            mutatingScheduledTaskIds = uiState.mutatingScheduledTaskIds + taskId,
            workflowError = null,
        )
        viewModelScope.launch {
            try {
                val operationResult = withContext(Dispatchers.IO) {
                    val stopped = scheduledWorkflowStopCoordinator.stop(taskId)
                    when (stopped.outcome) {
                        ScheduledWorkflowStopOutcome.SCHEDULE_CANCELLED -> OperationResult(
                            true,
                            "计划已取消",
                            if (stopped.systemCancellationFailed) {
                                "本地门禁已取消；残留系统任务到时会安全跳过"
                            } else {
                                "这个一次性计划不会再执行"
                            },
                        )
                        ScheduledWorkflowStopOutcome.STOPPED -> OperationResult(
                            true,
                            "后台任务已停止",
                            if (stopped.systemCancellationFailed) {
                                "系统取消失败，Agent、工作流与调度实例已通过持久化账本兜底收敛"
                            } else {
                                "Agent、工作流与调度实例已按持久化状态收敛"
                            },
                        )
                        ScheduledWorkflowStopOutcome.STOP_REQUESTED -> OperationResult(
                            true,
                            "已请求停止后台任务",
                            if (stopped.systemCancellationFailed) {
                                "停止意图已持久化；系统取消异常，应用下次启动仍会继续收敛"
                            } else {
                                "系统取消已提交，持久化账本仍在收敛；稍后刷新可查看终态"
                            },
                        )
                        ScheduledWorkflowStopOutcome.NOT_RUNNING -> OperationResult(
                            true,
                            "后台任务已经结束",
                            stopped.task?.status?.name.orEmpty(),
                        )
                        ScheduledWorkflowStopOutcome.NOT_FOUND -> OperationResult(
                            false,
                            "停止失败",
                            "调度实例不存在",
                        )
                    }
                }
                uiState = uiState.copy(
                    mutatingScheduledTaskIds = uiState.mutatingScheduledTaskIds - taskId,
                    result = operationResult,
                )
                refreshWorkflows()
            } catch (error: Throwable) {
                uiState = uiState.copy(
                    mutatingScheduledTaskIds = uiState.mutatingScheduledTaskIds - taskId,
                    workflowError = error.message ?: "停止或取消任务失败",
                )
                refreshWorkflows()
            }
        }
    }

    fun runWorkflow(workflowId: String) {
        if (uiState.sendingMessage || uiState.runningWorkflowId != null) {
            showValidation("当前已有任务正在执行，请等待结束后再运行工作流")
            return
        }
        val workflow = uiState.workflows.firstOrNull { it.id == workflowId }
        if (workflow == null) {
            showValidation("工作流不存在，请刷新后重试")
            return
        }
        if (!workflow.enabled) {
            showValidation("工作流已停用")
            return
        }
        if (uiState.workflowRuns.any {
                it.run.workflowId == workflowId && it.run.status in setOf(WorkflowRunStatus.QUEUED, WorkflowRunStatus.RUNNING)
            }
        ) {
            showValidation("这个工作流已有未完成的 Run")
            return
        }
        val conversationId = uiState.selectedConversationId.takeIf { id ->
            id.isNotBlank() && uiState.conversations.any { it.id == id }
        }
        if (conversationId == null) {
            showValidation("请先打开一个会话")
            return
        }
        val runtimeSelection = validatedSelectedAgentRuntimeSelection() ?: return
        uiState = uiState.copy(
            runningWorkflowId = workflowId,
            workflowError = null,
            workflowNavigationConversationId = conversationId,
            sendingMessage = true,
        )
        clearAgentStateForConversation(conversationId)
        sendMessageJob = viewModelScope.launch {
            var detail: WorkflowRunDetail? = null
            try {
                detail = withContext(Dispatchers.IO) { workflowRepository.createManualRun(workflowId, conversationId) }
                uiState = uiState.copy(workflowRuns = listOf(detail) + uiState.workflowRuns)
                executeForegroundWorkflow(detail, runtimeSelection, conversationId)
            } catch (error: CancellationException) {
                detail?.let { current ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            current.run.id,
                            WorkflowRunStatus.CANCELLED,
                            errorMessage = "用户停止工作流执行",
                        )
                    }
                }
                appendWorkflowMessage(
                    conversationId,
                    ChatMessage(role = "error", text = "已停止工作流", createdAt = System.currentTimeMillis()),
                )
            } catch (error: Throwable) {
                detail?.let { current ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            current.run.id,
                            WorkflowRunStatus.FAILED,
                            errorMessage = error.message ?: "工作流执行失败",
                        )
                    }
                }
                appendWorkflowMessage(
                    conversationId,
                    ChatMessage(
                        role = "error",
                        text = "工作流失败\n${error.message ?: "未知错误"}",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                uiState = uiState.copy(workflowError = error.message ?: "工作流执行失败")
            } finally {
                pendingApprovalDecision = null
                clearPendingApprovalForConversation(conversationId)
                uiState = uiState.copy(
                    runningWorkflowId = null,
                    sendingMessage = false,
                )
                sendMessageJob = null
                refreshWorkflows()
                saveConversationSelection()
            }
        }
    }

    private suspend fun executeForegroundWorkflow(
        initialDetail: WorkflowRunDetail,
        runtimeSelection: AgentRuntimeSelection,
        conversationId: String,
    ) {
        var detail = initialDetail
        val approvalGate = interactiveAgentApprovalGate(conversationId)
        while (true) {
            val step = WorkflowStepExecutionPolicy.nextExecutableStep(detail.steps) ?: break
            val preparedStep = withContext(Dispatchers.IO) {
                workflowRepository.prepareWorkflowStep(detail.run.id, step.id)
            }
            val input = WorkflowStepSnapshotCodec.decodeInput(preparedStep.inputSnapshot)
            val executionGoal = WorkflowStepPromptPolicy.build(input.goal, input.previousOutputs)
            val userMessage = ChatMessage(
                role = "user",
                text = "/agent ${preparedStep.detail}",
                createdAt = System.currentTimeMillis(),
            )
            appendWorkflowMessage(conversationId, userMessage)
            saveConversationSelection()
            // long: 每个 Workflow 步骤仍是独立 Agent Run，继续执行原有逐工具审批与验证；前序输出只作为本步骤已冻结的输入上下文。
            val summary = agentRunUseCase.run(
                conversationId = conversationId,
                userMessageId = userMessage.id,
                goal = executionGoal,
                config = runtimeSelection.config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(uiState.promptSettings),
                agentProfile = runtimeSelection.profile,
                memoryRecallEnabled = runtimeSelection.profile.memoryEnabled,
                invocationSource = AgentInvocationSource.WORKFLOW,
                approvalGate = approvalGate,
                onSnapshot = { snapshot ->
                    withContext(Dispatchers.IO) {
                        workflowRepository.markAgentRunStarted(detail.run.id, preparedStep.id, snapshot.run.id)
                    }
                    publishAgentRunSnapshot(snapshot)
                },
            )
            withContext(Dispatchers.IO) {
                workflowRepository.completeWorkflowStep(
                    workflowRunId = detail.run.id,
                    workflowStepId = preparedStep.id,
                    status = WorkflowStepStatus.COMPLETED,
                    result = summary.responseText,
                    knowledgeReferences = summary.verifiedContext.knowledgeReferences,
                    requiresCurrentKnowledgeReferences = summary.verifiedContext.toolName == "knowledge.search" ||
                        summary.verifiedContext.toolExecutions.any { it.toolName == "knowledge.search" },
                )
            }
            appendWorkflowMessage(
                conversationId,
                ChatMessage(
                    role = "assistant",
                    text = summary.responseText,
                    createdAt = System.currentTimeMillis(),
                    origin = MessageOrigin.AGENT_RESULT,
                    verifiedAgentContext = summary.verifiedContext,
                ),
            )
            createMemoryCandidateAfterTurn(
                userText = preparedStep.detail,
                conversationId = conversationId,
                runId = summary.runId,
            )
            saveConversationSelection()
            detail = withContext(Dispatchers.IO) {
                workflowRepository.runDetail(detail.run.id) ?: error("工作流 Run 已丢失")
            }
        }
        require(detail.steps.all { it.status in setOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.SKIPPED) }) {
            "工作流仍有未完成步骤"
        }
        val result = detail.steps.mapNotNull { step ->
            WorkflowStepSnapshotCodec.outputText(step.outputSnapshot ?: step.result)
        }.joinToString(separator = "\n\n")
        withContext(Dispatchers.IO) {
            workflowRepository.completeRun(detail.run.id, WorkflowRunStatus.COMPLETED, result = result)
        }
    }

    private fun appendWorkflowMessage(conversationId: String, message: ChatMessage) {
        val conversation = uiState.conversations.firstOrNull { it.id == conversationId } ?: return
        uiState = uiState.withUpdatedConversation(
            conversationId = conversationId,
            messages = conversation.messages + message,
            summary = conversation.summary,
            summaryUntilMessageId = conversation.summaryUntilMessageId,
            summaryUpdatedAt = conversation.summaryUpdatedAt,
            summaryModel = conversation.summaryModel,
        )
    }

    fun consumeWorkflowNavigation() {
        uiState = uiState.copy(workflowNavigationConversationId = null)
    }

    fun importSkill(uri: android.net.Uri) {
        if (uiState.importingSkill) return
        uiState = uiState.copy(importingSkill = true, skillError = null, result = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = readUtf8SkillDocument(uri)
                    agentRunUseCase.importSkill(raw)
                }
            }.onSuccess { record ->
                uiState = uiState.copy(
                    importingSkill = false,
                    result = OperationResult(
                        true,
                        "Skill 已导入",
                        "${record.definition.name} v${record.definition.version} 已${if (record.enabled) "启用" else "保持停用"}",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                uiState = uiState.copy(
                    importingSkill = false,
                    skillError = error.message ?: "Skill 导入失败",
                    result = OperationResult(false, "Skill 导入失败", error.message ?: "文件校验未通过"),
                )
            }
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        if (skillId in uiState.mutatingSkillIds) return
        uiState = uiState.copy(mutatingSkillIds = uiState.mutatingSkillIds + skillId, skillError = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentRunUseCase.setSkillEnabled(skillId, enabled) }
            }.onSuccess { updated ->
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    result = OperationResult(
                        updated != null,
                        if (enabled) "Skill 已启用" else "Skill 已停用",
                        updated?.definition?.name ?: "Skill 不存在",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    skillError = error.message ?: "无法更新 Skill",
                )
            }
        }
    }

    fun requestLocalSkillDelete(skillId: String) {
        val skill = uiState.skills.firstOrNull { it.definition.id == skillId } ?: return
        if (skill.definition.source != AgentSkillSource.LOCAL) {
            showValidation("内置 Skill 不能删除")
            return
        }
        uiState = uiState.copy(pendingLocalSkillDelete = skill)
    }

    fun cancelLocalSkillDelete() {
        uiState = uiState.copy(pendingLocalSkillDelete = null)
    }

    fun confirmLocalSkillDelete() {
        val skill = uiState.pendingLocalSkillDelete ?: return
        val skillId = skill.definition.id
        if (skillId in uiState.mutatingSkillIds) return
        uiState = uiState.copy(
            pendingLocalSkillDelete = null,
            mutatingSkillIds = uiState.mutatingSkillIds + skillId,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentRunUseCase.deleteLocalSkill(skillId) }
            }.onSuccess { deleted ->
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    result = OperationResult(
                        deleted,
                        if (deleted) "Skill 已删除" else "删除失败",
                        if (deleted) skill.definition.name else "Skill 不存在或不是本地 Skill",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    skillError = error.message ?: "删除 Skill 失败",
                )
            }
        }
    }

    private fun readUtf8SkillDocument(uri: android.net.Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= AgentSkillDocumentCodec.MAX_DOCUMENT_BYTES) { "Skill 文件不能超过 64 KiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法打开 Skill 文件")
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    fun updateMemoryCandidatesEnabled(enabled: Boolean) {
        uiPreferenceStore.saveMemoryCandidatesEnabled(enabled)
        uiState = uiState.copy(
            memoryCandidatesEnabled = enabled,
            memoryCandidates = if (enabled) uiState.memoryCandidates else emptyList(),
            result = OperationResult(
                success = true,
                title = if (enabled) "候选记忆已开启" else "候选记忆已关闭",
                message = if (enabled) "后续明确陈述只会生成候选，仍需你确认保存" else "后续对话不会生成新的候选记忆",
            ),
        )
        if (enabled) loadMemoryCandidates()
    }

    fun acceptMemoryCandidate(candidateId: String) {
        mutateMemoryCandidate(candidateId, accepted = true)
    }

    fun rejectMemoryCandidate(candidateId: String) {
        mutateMemoryCandidate(candidateId, accepted = false)
    }

    fun updateMemorySearchQuery(query: String) {
        uiState = uiState.copy(memorySearchQuery = query, memoryError = null)
        memorySearchJob?.cancel()
        memorySearchJob = viewModelScope.launch {
            delay(MEMORY_SEARCH_DEBOUNCE_MS)
            loadMemories()
        }
    }

    fun updateMemoryFilter(filter: AgentMemoryFilter) {
        if (filter == uiState.memoryFilter) return
        uiState = uiState.copy(memoryFilter = filter, memoryError = null)
        loadMemories()
    }

    fun selectMemory(memoryId: String) {
        if (uiState.memories.none { it.id == memoryId }) return
        uiState = uiState.copy(selectedMemoryId = memoryId)
    }

    fun openMemoryEdit(memoryId: String) {
        val memory = uiState.memories.firstOrNull { it.id == memoryId } ?: return
        uiState = uiState.copy(
            editingMemory = AgentMemoryEditUiState(
                id = memory.id,
                content = memory.content,
                tags = memory.tags,
                type = memory.type,
                confidence = memory.confidence,
            ),
        )
    }

    fun updateMemoryEditContent(value: String) {
        uiState.editingMemory?.let { uiState = uiState.copy(editingMemory = it.copy(content = value)) }
    }

    fun updateMemoryEditTags(value: String) {
        uiState.editingMemory?.let { uiState = uiState.copy(editingMemory = it.copy(tags = value)) }
    }

    fun updateMemoryEditType(value: String) {
        uiState.editingMemory?.let { uiState = uiState.copy(editingMemory = it.copy(type = value)) }
    }

    fun updateMemoryEditConfidence(value: Double) {
        uiState.editingMemory?.let {
            uiState = uiState.copy(editingMemory = it.copy(confidence = value.coerceIn(0.0, 1.0)))
        }
    }

    fun cancelMemoryEdit() {
        uiState = uiState.copy(editingMemory = null)
    }

    fun saveMemoryEdit() {
        val draft = uiState.editingMemory ?: return
        if (draft.content.isBlank()) {
            showValidation("记忆内容不能为空")
            return
        }
        mutateMemory(
            memoryId = draft.id,
            successMessage = "记忆内容和检索索引已更新",
        ) {
            agentMemoryStore.update(
                memoryId = draft.id,
                update = AgentMemoryUpdate(
                    content = draft.content,
                    tags = draft.tags,
                    type = draft.type,
                    confidence = draft.confidence,
                ),
            )
        }
    }

    fun setMemoryEnabled(memoryId: String, enabled: Boolean) {
        mutateMemory(
            memoryId = memoryId,
            successMessage = if (enabled) "记忆已启用" else "记忆已禁用，不再参与 Agent 检索",
        ) {
            agentMemoryStore.setEnabled(memoryId, enabled)
        }
    }

    fun setMemoryPinned(memoryId: String, pinned: Boolean) {
        mutateMemory(
            memoryId = memoryId,
            successMessage = if (pinned) "记忆已置顶" else "已取消置顶",
        ) {
            agentMemoryStore.setPinned(memoryId, pinned)
        }
    }

    fun setMemoryExpiry(memoryId: String, option: AgentMemoryExpiryOption) {
        mutateMemory(
            memoryId = memoryId,
            successMessage = "记忆过期策略已更新",
        ) {
            agentMemoryStore.setExpiresAt(
                memoryId = memoryId,
                expiresAt = AgentMemoryDecayPolicy.expiresAt(option, System.currentTimeMillis()),
            )
        }
    }

    fun requestMemoryDelete(memoryId: String) {
        val memory = uiState.memories.firstOrNull { it.id == memoryId } ?: return
        uiState = uiState.copy(pendingMemoryDelete = memory)
    }

    fun cancelMemoryDelete() {
        uiState = uiState.copy(pendingMemoryDelete = null)
    }

    fun confirmMemoryDelete() {
        val memory = uiState.pendingMemoryDelete ?: return
        if (memory.id in uiState.mutatingMemoryIds) return
        uiState = uiState.copy(
            pendingMemoryDelete = null,
            mutatingMemoryIds = uiState.mutatingMemoryIds + memory.id,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentMemoryStore.delete(memory.id) }
            }.onSuccess { deleted ->
                uiState = uiState.copy(
                    mutatingMemoryIds = uiState.mutatingMemoryIds - memory.id,
                    deletedMemoryForUndo = deleted,
                    result = OperationResult(
                        success = deleted != null,
                        title = if (deleted != null) "已删除" else "删除失败",
                        message = if (deleted != null) "记忆及其检索索引已删除，应用重启后仍可撤销" else "记忆不存在或已被删除",
                    ),
                )
                loadMemories()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingMemoryIds = uiState.mutatingMemoryIds - memory.id,
                    memoryError = error.message ?: "删除记忆失败",
                )
            }
        }
    }

    fun undoMemoryDelete() {
        val memory = uiState.deletedMemoryForUndo ?: return
        if (memory.id in uiState.mutatingMemoryIds) return
        uiState = uiState.copy(mutatingMemoryIds = uiState.mutatingMemoryIds + memory.id)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentMemoryStore.restore(memory) }
            }.onSuccess {
                uiState = uiState.copy(
                    deletedMemoryForUndo = null,
                    mutatingMemoryIds = uiState.mutatingMemoryIds - memory.id,
                    result = OperationResult(true, "已恢复", "记忆和检索索引已恢复"),
                )
                loadMemories()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingMemoryIds = uiState.mutatingMemoryIds - memory.id,
                    memoryError = error.message ?: "恢复记忆失败",
                )
            }
        }
    }

    fun openMemorySourceConversation(memoryId: String) {
        val memory = uiState.memories.firstOrNull { it.id == memoryId } ?: return
        val conversationId = memory.sourceConversationId
        if (conversationId == null || uiState.conversations.none { it.id == conversationId }) {
            showValidation("来源会话已不存在")
            return
        }
        selectConversation(conversationId)
        uiState = uiState.copy(memorySourceConversationNavigationId = conversationId)
    }

    fun openMemorySourceRun(memoryId: String) {
        val memory = uiState.memories.firstOrNull { it.id == memoryId } ?: return
        val runId = memory.sourceRunId
        if (runId.isNullOrBlank()) {
            showValidation("这条记忆没有来源 Run")
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentRunRepository.runDetail(runId) }
            }.onSuccess { detail ->
                if (detail == null) {
                    showValidation("来源 Run 已不存在")
                } else {
                    uiState = uiState.copy(
                        agentRunHistory = listOf(detail) + uiState.agentRunHistory.filterNot {
                            it.snapshot.run.id == runId
                        },
                        selectedAgentRunId = runId,
                        memorySourceRunNavigationId = runId,
                    )
                }
            }.onFailure { error ->
                showValidation(error.message ?: "无法读取来源 Run")
            }
        }
    }

    fun consumeMemorySourceConversationNavigation() {
        uiState = uiState.copy(memorySourceConversationNavigationId = null)
    }

    fun consumeMemorySourceRunNavigation() {
        uiState = uiState.copy(memorySourceRunNavigationId = null)
    }

    private fun loadMemories() {
        memoryLoadJob?.cancel()
        val query = uiState.memorySearchQuery
        val filter = uiState.memoryFilter
        uiState = uiState.copy(loadingMemories = true, memoryError = null)
        memoryLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    agentMemoryStore.list(query = query, filter = filter, limit = MEMORY_MANAGEMENT_LIMIT)
                }
            }.onSuccess { memories ->
                val selectedId = uiState.selectedMemoryId
                    ?.takeIf { id -> memories.any { it.id == id } }
                    ?: memories.firstOrNull()?.id
                uiState = uiState.copy(
                    loadingMemories = false,
                    memories = memories,
                    selectedMemoryId = selectedId,
                    memoryError = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    loadingMemories = false,
                    memoryError = error.message ?: "无法读取长期记忆",
                )
            }
        }
    }

    private fun loadMemoryCandidates() {
        memoryCandidateLoadJob?.cancel()
        if (!uiState.memoryCandidatesEnabled) return
        uiState = uiState.copy(loadingMemoryCandidates = true, memoryError = null)
        memoryCandidateLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { agentMemoryStore.listCandidates(limit = MEMORY_CANDIDATE_LIMIT) }
            }.onSuccess { candidates ->
                uiState = uiState.copy(
                    loadingMemoryCandidates = false,
                    memoryCandidates = candidates,
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    loadingMemoryCandidates = false,
                    memoryError = error.message ?: "无法读取候选记忆",
                )
            }
        }
    }

    private suspend fun createMemoryCandidateAfterTurn(
        userText: String,
        conversationId: String,
        runId: String?,
    ) {
        if (!uiState.memoryCandidatesEnabled) return
        runCatching {
            withContext(Dispatchers.IO) {
                agentMemoryStore.createCandidate(
                    userText = userText,
                    source = com.longdev.xiaoling.agent.AgentMemorySource(
                        conversationId = conversationId,
                        runId = runId,
                        summary = if (runId == null) "普通对话结束后生成的候选" else "Agent Run 结束后生成的候选",
                    ),
                )
            }
        }.onSuccess { candidate ->
            if (candidate?.status == AgentMemoryCandidateStatus.BLOCKED_SENSITIVE) {
                uiState = uiState.copy(
                    result = OperationResult(
                        success = false,
                        title = "敏感内容未加入记忆",
                        message = "检测到${candidate.sensitiveCategory?.displayName ?: "敏感信息"}，未保存原文",
                    ),
                )
            }
            if (candidate != null) loadMemoryCandidates()
        }.onFailure { error ->
            // long: 候选提取是聊天后的附加能力，失败时只记录记忆侧错误，不能把已经成功的普通回复或 Agent Run 改成失败。
            uiState = uiState.copy(memoryError = error.message ?: "生成候选记忆失败")
        }
    }

    private fun mutateMemoryCandidate(candidateId: String, accepted: Boolean) {
        if (candidateId in uiState.mutatingMemoryCandidateIds) return
        uiState = uiState.copy(
            mutatingMemoryCandidateIds = uiState.mutatingMemoryCandidateIds + candidateId,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (accepted) {
                        agentMemoryStore.acceptCandidate(candidateId)
                    } else {
                        agentMemoryStore.rejectCandidate(candidateId)
                    }
                }
            }.onSuccess { candidate ->
                val status = candidate?.status
                uiState = uiState.copy(
                    mutatingMemoryCandidateIds = uiState.mutatingMemoryCandidateIds - candidateId,
                    result = OperationResult(
                        success = candidate != null,
                        title = when (status) {
                            AgentMemoryCandidateStatus.ACCEPTED -> "已保存记忆"
                            AgentMemoryCandidateStatus.DUPLICATE -> "已有相同记忆"
                            AgentMemoryCandidateStatus.REJECTED -> "已忽略候选"
                            else -> "候选未更新"
                        },
                        message = when (status) {
                            AgentMemoryCandidateStatus.ACCEPTED -> "候选已转为正式记忆并加入检索"
                            AgentMemoryCandidateStatus.DUPLICATE -> "未重复写入，继续使用原有记忆"
                            AgentMemoryCandidateStatus.REJECTED -> "该候选不会进入正式记忆"
                            else -> "候选状态已变化，请刷新后重试"
                        },
                    ),
                )
                loadMemoryCandidates()
                if (accepted) loadMemories()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingMemoryCandidateIds = uiState.mutatingMemoryCandidateIds - candidateId,
                    memoryError = error.message ?: "更新候选记忆失败",
                )
            }
        }
    }

    private fun mutateMemory(
        memoryId: String,
        successMessage: String,
        operation: suspend () -> AgentMemoryRecord?,
    ) {
        if (memoryId in uiState.mutatingMemoryIds) return
        uiState = uiState.copy(mutatingMemoryIds = uiState.mutatingMemoryIds + memoryId)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { operation() }
            }.onSuccess { updated ->
                uiState = uiState.copy(
                    mutatingMemoryIds = uiState.mutatingMemoryIds - memoryId,
                    editingMemory = null,
                    result = OperationResult(
                        success = updated != null,
                        title = if (updated != null) "已更新" else "更新失败",
                        message = if (updated != null) successMessage else "记忆不存在",
                    ),
                )
                loadMemories()
            }.onFailure { error ->
                uiState = uiState.copy(
                    mutatingMemoryIds = uiState.mutatingMemoryIds - memoryId,
                    memoryError = error.message ?: "更新记忆失败",
                )
            }
        }
    }

    fun requestAgentRunRetry(runId: String) {
        if (uiState.sendingMessage || uiState.retryingAgentRunId != null) {
            showValidation("当前已有任务正在执行，请等待结束后再重试")
            return
        }
        val detail = uiState.agentRunHistory.firstOrNull { it.snapshot.run.id == runId }
        if (detail == null) {
            showValidation("找不到要重试的 Agent Run，请刷新任务中心")
            return
        }
        when (val eligibility = AgentTaskRetryPolicy.evaluate(detail)) {
            AgentTaskRetryEligibility.NotRetryable -> {
                showValidation("当前状态不支持重试")
            }
            is AgentTaskRetryEligibility.Retryable -> {
                if (eligibility.requiresConfirmation) {
                    val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
                    uiState = uiState.copy(
                        pendingAgentRetryConfirmation = AgentRetryConfirmationUiState(
                            runId = runId,
                            goal = detail.snapshot.run.goal,
                            evidenceCode = evidence.code,
                            evidenceFingerprint = evidence.fingerprint,
                        ),
                    )
                } else {
                    startAgentRunRetry(detail)
                }
            }
        }
    }

    fun confirmAgentRunRetry() {
        val pending = uiState.pendingAgentRetryConfirmation ?: return
        uiState = uiState.copy(pendingAgentRetryConfirmation = null)
        val detail = uiState.agentRunHistory.firstOrNull { it.snapshot.run.id == pending.runId }
        if (detail == null) {
            showValidation("来源 Agent Run 已不存在，请刷新任务中心")
            return
        }
        if (AgentTaskRetryPolicy.evaluate(detail) is AgentTaskRetryEligibility.NotRetryable) {
            uiState = uiState.copy(pendingAgentRetryConfirmation = null)
            showValidation("当前状态已变化，请刷新任务中心")
            return
        }
        if (!AgentTaskRetryPolicy.canConfirmRetry(pending.evidenceCode, detail, pending.evidenceFingerprint)) {
            val currentEvidence = AgentTaskRetryPolicy.assessEvidence(detail)
            uiState = uiState.copy(
                pendingAgentRetryConfirmation = pending.copy(
                    evidenceCode = currentEvidence.code,
                    evidenceFingerprint = currentEvidence.fingerprint,
                ),
            )
            showValidation("重试证据已变化，请重新确认")
            return
        }
        startAgentRunRetry(detail)
    }

    fun cancelAgentRunRetry() {
        uiState = uiState.copy(pendingAgentRetryConfirmation = null)
    }

    fun consumeAgentRetryNavigation() {
        uiState = uiState.copy(agentRetryNavigationConversationId = null)
    }

    private fun startAgentRunRetry(detail: AgentRunDetailRecord) {
        val sourceRun = detail.snapshot.run
        val sourceConversation = uiState.conversations.firstOrNull { it.id == sourceRun.conversationId }
        if (sourceConversation == null) {
            showValidation("原会话已不存在，无法在正确上下文中重试")
            return
        }
        val runtimeSelection = validatedSelectedAgentRuntimeSelection() ?: return
        selectConversation(sourceRun.conversationId)
        uiState = uiState.copy(
            retryingAgentRunId = sourceRun.id,
            pendingAgentRetryConfirmation = null,
            selectedAgentRunId = sourceRun.id,
            agentRetryNavigationConversationId = sourceRun.conversationId,
        )
        // long: 重试不是修改或续跑旧 Run，而是在原会话追加同一目标的新用户消息；同时回到来源会话，确保重新触发的写工具审批不会隐藏在任务中心后台。
        sendAgentRun(
            userMessage = "/agent " + sourceRun.goal,
            runtimeSelection = runtimeSelection,
            conversationId = sourceRun.conversationId,
            retryOfRunId = sourceRun.id,
        )
    }

    fun approvePendingAgentTool() {
        val pending = uiState.pendingAgentApproval ?: return
        if (pending.restoredFromProcess) {
            resumeRecoveredAgentRun(pending)
            return
        }
        completePendingAgentApproval(
            pending = pending,
            status = ApprovalRequestStatus.APPROVED,
            approved = true,
            reason = "用户已批准：${pending.toolName}",
        )
    }

    fun rejectPendingAgentTool() {
        val pending = uiState.pendingAgentApproval ?: return
        if (pending.restoredFromProcess) {
            rejectRecoveredAgentApproval(pending)
            return
        }
        completePendingAgentApproval(
            pending = pending,
            status = ApprovalRequestStatus.DENIED,
            approved = false,
            reason = "用户已拒绝：${pending.toolName}",
        )
    }

    private fun completePendingAgentApproval(
        pending: AgentApprovalUiState,
        status: ApprovalRequestStatus,
        approved: Boolean,
        reason: String,
    ) {
        val deferred = pendingApprovalDecision ?: return
        val deciding = pending.copy(deciding = true)
        rememberPendingApproval(deciding)
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                agentRunRepository.decideApprovalRequest(
                    requestId = pending.requestId,
                    status = status,
                    reason = reason,
                )
            }
            if (pendingApprovalDecision === deferred) {
                pendingApprovalDecision = null
                clearPendingApprovalForConversation(pending.conversationId)
                deferred.complete(
                    ApprovalDecision(
                        approved = approved,
                        reason = reason,
                    ),
                )
            }
        }
    }

    private fun resumeRecoveredAgentRun(pending: AgentApprovalUiState) {
        val detail = uiState.agentRunHistory.firstOrNull { it.snapshot.run.id == pending.runId }
        if (detail == null) {
            showValidation("找不到待恢复的 Agent Run，请刷新任务中心")
            return
        }
        val approval = detail.approvals.firstOrNull { it.id == pending.requestId }
        if (approval == null || approval.status != ApprovalRequestStatus.PENDING) {
            showValidation("审批请求已处理，请刷新任务中心")
            return
        }
        val source = detail.snapshot.run
        val conversation = uiState.conversations.firstOrNull { it.id == source.conversationId }
        if (conversation == null) {
            showValidation("原会话已不存在，无法恢复 Agent Run")
            return
        }
        val sourceProfile = detail.agentProfileSnapshotOrNull()
        val runtimeSelection = if (sourceProfile == null) {
            validatedSelectedAgentRuntimeSelection()
        } else {
            validatedAgentRuntimeSelection(sourceProfile)
        } ?: return
        val preparedContext = PreparedRequestContext.fromConversation(conversation)
        selectConversation(source.conversationId)
        clearPendingApprovalForConversation(source.conversationId)
        uiState = uiState.copy(
            sendingMessage = true,
            result = null,
            retryingAgentRunId = null,
            selectedAgentRunId = source.id,
        )
        sendMessageJob = viewModelScope.launch {
            var workflowRunIdToSettle: String? = null
            try {
                val summary = agentRunUseCase.resumeApprovedRun(
                    detail = detail,
                    approval = approval,
                    config = runtimeSelection.config,
                    summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(uiState.promptSettings),
                    approvalReason = "用户批准恢复后的工具执行：${pending.toolName}",
                    approvalGate = interactiveAgentApprovalGate(source.conversationId),
                    onSnapshot = ::publishAgentRunSnapshot,
                )
                val workflowContinuation = withContext(NonCancellable + Dispatchers.IO) {
                    val workflowRun = workflowRepository.completeByAgentRunId(
                        agentRunId = summary.runId,
                        status = WorkflowRunStatus.COMPLETED,
                        result = summary.responseText,
                    )
                    workflowRun
                        ?.takeIf { it.status == WorkflowRunStatus.RUNNING }
                        ?.let { workflowRepository.runDetail(it.id) }
                }
                workflowRunIdToSettle = workflowContinuation?.run?.id
                val finalMessages = conversation.messages + ChatMessage(
                    role = "assistant",
                    text = summary.responseText,
                    createdAt = System.currentTimeMillis(),
                    origin = MessageOrigin.AGENT_RESULT,
                    verifiedAgentContext = summary.verifiedContext,
                )
                uiState = uiState
                    .withUpdatedConversation(
                        conversationId = source.conversationId,
                        messages = finalMessages,
                        summary = preparedContext.summary,
                        summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                        summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                        summaryModel = preparedContext.summaryModel,
                    )
                    .copy(sendingMessage = false, result = null)
                createMemoryCandidateAfterTurn(
                    userText = source.goal,
                    conversationId = source.conversationId,
                    runId = summary.runId,
                )
                saveConversationSelection()
                if (workflowContinuation != null) {
                    // long: 进程恢复只续接已经由用户批准的当前步骤；步骤结果落库后继续同一 Run 的后续快照，避免留下永久 RUNNING 的 Workflow。
                    uiState = uiState.copy(runningWorkflowId = workflowContinuation.run.workflowId)
                    executeForegroundWorkflow(workflowContinuation, runtimeSelection, source.conversationId)
                }
            } catch (error: CancellationException) {
                workflowRunIdToSettle?.let { workflowRunId ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            workflowRunId,
                            WorkflowRunStatus.CANCELLED,
                            errorMessage = "用户停止工作流执行",
                        )
                    }
                } ?: reconcileWorkflowAfterResumeFailure(source.id, "用户停止工作流执行")
                uiState = uiState.copy(sendingMessage = false, runningWorkflowId = null, result = null)
            } catch (error: Throwable) {
                val failure = error.message ?: "未知错误"
                workflowRunIdToSettle?.let { workflowRunId ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            workflowRunId,
                            WorkflowRunStatus.FAILED,
                            errorMessage = failure,
                        )
                    }
                } ?: reconcileWorkflowAfterResumeFailure(source.id, failure)
                uiState = uiState.copy(sendingMessage = false, runningWorkflowId = null, result = null)
            } finally {
                uiState = uiState.copy(sendingMessage = false, runningWorkflowId = null)
                sendMessageJob = null
                refreshAgentRunHistory()
                refreshWorkflows()
            }
        }
    }

    private fun rejectRecoveredAgentApproval(pending: AgentApprovalUiState) {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                agentRunRepository.decideApprovalRequest(
                    requestId = pending.requestId,
                    status = ApprovalRequestStatus.DENIED,
                    reason = "用户拒绝恢复后的工具执行",
                )
                agentRunRepository.updateRunStatus(
                    runId = pending.runId,
                    status = AgentRunStatus.FAILED,
                    errorMessage = "用户拒绝恢复后的工具执行",
                )
            }
            settleWorkflowLedger(
                agentRunId = pending.runId,
                agentStatus = AgentRunStatus.FAILED,
                errorMessage = "用户拒绝恢复后的工具执行",
            )
            clearPendingApprovalForConversation(pending.conversationId)
            refreshAgentRunHistory()
            refreshWorkflows()
        }
    }

    fun openNewConversation() {
        conversationLoadCoordinator.cancelPendingLoad()
        val lightweightConversations = uiState.conversations.map { it.withoutBinaryPayloads() }
        val current = lightweightConversations.firstOrNull { it.id == uiState.selectedConversationId }
        if (current != null && current.messages.isEmpty()) {
            uiState = uiState.copy(
                conversations = lightweightConversations,
                selectedConversationId = current.id,
                conversationTitle = current.title,
                conversationSummary = current.summary,
                chatMessages = emptyList(),
                activeAgentRun = activeAgentRunsByConversation[current.id],
                pendingAgentApproval = pendingAgentApprovalsByConversation[current.id],
                loadingConversationMessages = false,
                result = null,
            )
            saveConversationSelection()
            return
        }
        val reusableEmptyConversation = lightweightConversations
            .filter { it.messages.isEmpty() }
            .maxByOrNull { it.updatedAt }
        if (reusableEmptyConversation != null) {
            uiState = uiState.copy(
                conversations = lightweightConversations
                    .collapseDuplicateEmptyConversations(reusableEmptyConversation.id),
                selectedConversationId = reusableEmptyConversation.id,
                conversationTitle = reusableEmptyConversation.title,
                conversationSummary = reusableEmptyConversation.summary,
                chatMessages = emptyList(),
                activeAgentRun = activeAgentRunsByConversation[reusableEmptyConversation.id],
                pendingAgentApproval = pendingAgentApprovalsByConversation[reusableEmptyConversation.id],
                loadingConversationMessages = false,
                result = null,
            )
            saveConversationSelection()
            return
        }

        val now = System.currentTimeMillis()
        val conversation = ConversationSession(
            id = "conversation-$now",
            title = "新会话",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        uiState = uiState.copy(
            conversations = lightweightConversations + conversation,
            selectedConversationId = conversation.id,
            conversationTitle = conversation.title,
            conversationSummary = "",
            chatMessages = emptyList(),
            activeAgentRun = null,
            pendingAgentApproval = null,
            loadingConversationMessages = false,
            result = null,
        )
        saveConversationSelection()
    }

    fun selectConversation(conversationId: String) {
        val conversation = uiState.conversations.firstOrNull { it.id == conversationId } ?: return
        loadAndSelectConversation(conversation, uiState.conversations, result = null)
    }

    fun refreshKnowledgeReferenceStatuses(references: List<KnowledgeReference>) {
        val distinctReferences = references.distinct()
        val conversationId = uiState.selectedConversationId
        knowledgeReferenceStatusJob?.cancel()
        if (distinctReferences.isEmpty()) {
            if (uiState.knowledgeReferenceStatuses.isNotEmpty() || uiState.failedKnowledgeReferenceStatuses.isNotEmpty()) {
                uiState = uiState.copy(
                    knowledgeReferenceStatuses = emptyMap(),
                    failedKnowledgeReferenceStatuses = emptySet(),
                )
            }
            return
        }
        // long: 每次进入对话页先清空旧状态，避免知识文档刚被替换或停用时短暂沿用离开页面前的“当前有效”标签。
        uiState = uiState.copy(
            knowledgeReferenceStatuses = emptyMap(),
            failedKnowledgeReferenceStatuses = emptySet(),
        )
        knowledgeReferenceStatusJob = viewModelScope.launch {
            val statusResult = runCatching {
                withContext(Dispatchers.IO) {
                    knowledgeDocumentStore.inspectReferences(distinctReferences)
                }
            }.onFailure { error ->
                // long: 切换会话或触发新一轮核验时，旧 Job 的取消必须停止回写；取消不是数据库失败，不能覆盖新状态为“暂无法核验”。
                if (error is CancellationException) throw error
            }
            val currentReferences = uiState.chatMessages
                .flatMap(ChatMessage::knowledgeReferencesForDisplay)
                .distinct()
            if (uiState.selectedConversationId != conversationId || currentReferences != distinctReferences) {
                return@launch
            }
            uiState = statusResult.fold(
                onSuccess = { statuses ->
                    uiState.copy(
                        knowledgeReferenceStatuses = statuses.associateBy(KnowledgeReferenceStatus::reference),
                        failedKnowledgeReferenceStatuses = emptySet(),
                    )
                },
                onFailure = {
                    // long: 数据库核验失败不等于引用已经失效；界面明确显示“暂无法核验”，并在下次进入对话页时重新尝试。
                    uiState.copy(
                        knowledgeReferenceStatuses = emptyMap(),
                        failedKnowledgeReferenceStatuses = distinctReferences.toSet(),
                    )
                },
            )
        }
    }

    fun deleteCurrentConversation() {
        conversationLoadCoordinator.cancelPendingLoad()
        val currentId = uiState.selectedConversationId
        val deletionIntent = conversationPersistenceCoordinator.markConversationDeleted(currentId)
        val remaining = uiState.conversations.filterNot { it.id == currentId }
        activeAgentRunsByConversation.remove(currentId)
        pendingAgentApprovalsByConversation.remove(currentId)
        if (remaining.isEmpty()) {
            val now = System.currentTimeMillis()
            val conversation = ConversationSession(
                id = "conversation-$now",
                title = "新会话",
                summary = "",
                summaryUntilMessageId = null,
                summaryUpdatedAt = null,
                summaryModel = null,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
            uiState = uiState.copy(
                conversations = listOf(conversation),
                selectedConversationId = conversation.id,
                conversationTitle = conversation.title,
                conversationSummary = "",
                chatMessages = emptyList(),
                activeAgentRun = null,
                pendingAgentApproval = null,
                loadingConversationMessages = false,
                result = null,
            )
            saveConversationSelection()
            return
        }

        val next = remaining.maxBy { it.updatedAt }
        loadAndSelectConversation(
            conversation = next,
            conversations = remaining,
            result = OperationResult(true, "已删除", "当前会话已删除"),
            rollbackDeletionIntentOnFailure = deletionIntent,
        )
    }

    private fun loadAndSelectConversation(
        conversation: ConversationSession,
        conversations: List<ConversationSession>,
        result: OperationResult?,
        rollbackDeletionIntentOnFailure: ConversationDeletionIntent? = null,
    ) {
        conversationLoadCoordinator.load(
            request = ConversationLoadRequest(
                conversation = conversation,
                conversations = conversations,
                result = result,
                rollbackDeletionIntentOnFailure = rollbackDeletionIntentOnFailure,
            ),
            onEvent = { event ->
                when (event) {
                    ConversationLoadEvent.Loading -> {
                        uiState = uiState.withConversationLoadEvent(event)
                    }

                    is ConversationLoadEvent.Loaded -> {
                        val request = event.request
                        uiState = uiState.withConversationLoadEvent(
                            event = event,
                            activeAgentRun = activeAgentRunsByConversation[request.conversation.id],
                            pendingAgentApproval = pendingAgentApprovalsByConversation[request.conversation.id],
                        )
                        saveConversationSelection()
                    }

                    is ConversationLoadEvent.Failed -> {
                        event.request.rollbackDeletionIntentOnFailure?.let(
                            conversationPersistenceCoordinator::rollbackConversationDeletion,
                        )
                        uiState = uiState.withConversationLoadEvent(event)
                    }
                }
            },
        )
    }

    fun importDraftFromQr(raw: String) {
        importDraftCredentials(raw, successTitle = "扫码导入成功", invalidMessage = "二维码内容必须是 baseUrl,apiKey")
    }

    fun importDraftFromClipboard(raw: String) {
        importDraftCredentials(raw, successTitle = "剪切板导入成功", invalidMessage = "剪切板内容必须是 baseUrl,apiKey")
    }

    private fun importDraftCredentials(raw: String, successTitle: String, invalidMessage: String) {
        val separator = raw.indexOf(',')
        if (separator <= 0 || separator == raw.lastIndex) {
            showValidation(invalidMessage)
            return
        }
        val baseUrl = raw.substring(0, separator).trim()
        val apiKey = raw.substring(separator + 1).trim()
        ProviderApiUrlBuilder.validate(baseUrl)?.let {
            showValidation(it)
            return
        }
        updateDraft {
            copy(
                baseUrl = baseUrl,
                apiKey = apiKey,
                upstreamModels = emptyList(),
                enabledModels = emptySet(),
            )
        }
        showSuccess(successTitle, baseUrl)
    }

    fun toggleDraftModel(model: String, enabled: Boolean) = updateDraft {
        copy(
            enabledModels = if (enabled) {
                enabledModels + model
            } else {
                enabledModels - model
            },
        )
    }

    fun fetchDraftModels() {
        val draft = uiState.manageDraft ?: return
        ProviderApiUrlBuilder.validate(draft.baseUrl)?.let {
            showValidation(it)
            return
        }

        val config = ProviderRequestConfig(
            baseUrl = draft.baseUrl.trim(),
            apiKey = draft.apiKey.trim(),
            model = "",
            userAgent = uiState.userAgent,
        )
        updateDraft { copy(loadingModels = true) }
        viewModelScope.launch {
            runCatching { client.fetchModels(config) }
                .onSuccess { models ->
                    val previous = uiState.manageDraft?.enabledModels.orEmpty()
                    updateDraft {
                        copy(
                            loadingModels = false,
                            upstreamModels = models,
                            enabledModels = previous.intersect(models.toSet()).ifEmpty { models.toSet() },
                        )
                    }
                    showSuccess("获取上游模型成功", "获取到 ${models.size} 个模型")
                }
                .onFailure { showFailure(it, loadingModels = false) }
        }
    }

    fun syncProviderModels(profileId: String) {
        val profile = uiState.profiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in uiState.syncingProfileIds) return
        viewModelScope.launch {
            syncStoredProfile(profile, showPopup = true, keepBatchResult = false)
        }
    }

    fun syncAllProviders() {
        if (uiState.syncingAllProfiles) return
        viewModelScope.launch {
            uiState = uiState.copy(
                syncingAllProfiles = true,
                batchSyncResults = emptyMap(),
                result = null,
            )
            uiState.profiles.toList().forEach { profile ->
                syncStoredProfile(profile, showPopup = false, keepBatchResult = true)
            }
            uiState = uiState.copy(syncingAllProfiles = false)
        }
    }

    fun saveDraftProvider() {
        val draft = uiState.manageDraft ?: return
        ProviderApiUrlBuilder.validate(draft.baseUrl)?.let {
            showValidation(it)
            return
        }

        val id = draft.id ?: "profile-${System.currentTimeMillis()}"
        val enabledModels = draft.upstreamModels.filter { it in draft.enabledModels }
        val displayName = draft.name.trim().ifBlank { draft.baseUrl.trim() }
        val oldProfile = uiState.profiles.firstOrNull { it.id == id }
        val selectedModel = oldProfile?.model
            ?.takeIf { it in enabledModels }
            ?: enabledModels.firstOrNull()
            ?: ""
        val savedProfile = ProviderProfile.blank(id).copy(
            name = displayName,
            baseUrl = draft.baseUrl.trim(),
            apiKey = draft.apiKey,
            model = selectedModel,
            availableModels = draft.upstreamModels.distinct(),
            enabledModels = enabledModels.distinct(),
            lastSyncedAt = oldProfile?.lastSyncedAt.orEmpty(),
        )
        val brokenAgents = uiState.agentProfiles.filter { agent ->
            agent.providerId == id && agent.model.isNotBlank() && agent.model !in savedProfile.enabledModels
        }
        if (brokenAgents.isNotEmpty()) {
            showValidation("以下 Agent 仍在使用被取消的模型：${brokenAgents.joinToString { it.name }}")
            return
        }

        val profiles = if (draft.id == null) {
            uiState.profiles + savedProfile
        } else {
            uiState.profiles.map { if (it.id == id) savedProfile else it }
        }
        uiState = uiState.copy(
            profiles = profiles,
            manageDraft = null,
            result = OperationResult(
                success = true,
                title = "已保存",
                message = "${savedProfile.name} · ${savedProfile.enabledModels.size} 个模型",
            ),
        ).fromProfile(savedProfile, id)
        saveProfilesSnapshot(profiles, id)
        repairIncompleteAgentProfiles(savedProfile)
    }

    fun deleteProvider(profileId: String) {
        if (uiState.profiles.size <= 1) {
            showValidation("至少保留一个模型提供方")
            return
        }
        val boundAgents = uiState.agentProfiles.filter { it.providerId == profileId }
        if (boundAgents.isNotEmpty()) {
            showValidation("模型提供方仍被 Agent 使用：${boundAgents.joinToString { it.name }}")
            return
        }
        val profiles = uiState.profiles.filterNot { it.id == profileId }
        val nextId = if (uiState.selectedProfileId == profileId) profiles.first().id else uiState.selectedProfileId
        val nextProfile = profiles.first { it.id == nextId }
        uiState = uiState.copy(
            profiles = profiles,
            manageDraft = null,
            result = OperationResult(true, "已删除", "模型提供方已删除"),
        ).fromProfile(nextProfile, nextId)
        saveProfilesSnapshot(profiles, nextId)
    }

    fun sendMessage() {
        if (uiState.sendingMessage) return
        if (uiState.loadingConversationMessages) {
            showValidation("会话消息仍在加载，请稍候再发送")
            return
        }
        if (uiState.attachingImage) {
            showValidation("图片仍在读取，请稍候再发送")
            return
        }
        if (uiState.attachingDocument) {
            showValidation("文档仍在读取，请稍候再发送")
            return
        }
        if (uiState.prompt.isBlank()) {
            showValidation("请输入消息")
            return
        }
        val userMessage = uiState.prompt.trim()
        val attachments = MessageAttachmentSelection(
            image = uiState.pendingImage,
            document = uiState.pendingDocument,
        )
        if (AgentCommand.matches(userMessage)) {
            attachments.agentRejectionReason()?.let { reason ->
                showValidation(reason)
                return
            }
            val runtimeSelection = validatedSelectedAgentRuntimeSelection() ?: return
            sendAgentRun(
                userMessage = userMessage,
                runtimeSelection = runtimeSelection,
                memoryRecallEnabled = uiState.agentMemoryRecallEnabled,
            )
            return
        }
        val config = validatedConfig() ?: return
        attachments.chatRejectionReason(config.apiMode)?.let { reason ->
            showValidation(reason)
            return
        }
        val profileSnapshot = selectedProfile()
        val currentConversation = uiState.conversations.firstOrNull { it.id == uiState.selectedConversationId }
        clearAgentStateForConversation(uiState.selectedConversationId)
        val userMessageId = newChatMessageId()
        val userChatMessage = ChatMessage(
            id = userMessageId,
            role = "user",
            text = userMessage,
            createdAt = System.currentTimeMillis(),
            parts = attachments.toUserMessageParts(userMessageId, userMessage),
        )
        val messagesWithUser = uiState.chatMessages + userChatMessage
        val initialContext = PreparedRequestContext.fromConversation(currentConversation)
        uiState = uiState.copy(
            sendingMessage = true,
            result = null,
            prompt = "",
            pendingImage = null,
            pendingDocument = null,
            activeAgentRun = null,
            pendingAgentApproval = null,
        ).withUpdatedCurrentConversation(
            messages = messagesWithUser,
            summary = initialContext.summary,
            summaryUntilMessageId = initialContext.summaryUntilMessageId,
            summaryUpdatedAt = initialContext.summaryUpdatedAt,
            summaryModel = initialContext.summaryModel,
        )
        val preRequestConversations = uiState.conversations.map { it.toStored() }
        val preRequestSelectedConversationId = uiState.selectedConversationId
        val baseMeta = config.toBaseMessageMeta(profileSnapshot)
        sendMessageJob = viewModelScope.launch {
            try {
                conversationPersistenceCoordinator.cancelPendingSaveAndJoin()
                // long: 等旧事务完全退出后再捕获删除意图；等待期间发生的删除或回滚必须进入发送前 Room 快照，不能被较早的集合覆盖。
                val preRequestPersistenceSnapshot = conversationPersistenceCoordinator.captureSnapshot(
                    conversations = preRequestConversations,
                    selectedConversationId = preRequestSelectedConversationId,
                )
                conversationSendCoordinator.execute(
                    request = ConversationSendRequest(
                        config = config,
                        messages = messagesWithUser,
                        conversation = currentConversation,
                        promptSettings = uiState.promptSettings,
                        persistenceSnapshot = preRequestPersistenceSnapshot,
                        initialContext = initialContext,
                    ),
                    onEvent = { event ->
                        handleConversationSendEvent(
                            event = event,
                            baseMeta = baseMeta,
                            userMessage = userMessage,
                            messagesWithUser = messagesWithUser,
                        )
                    },
                )
            } finally {
                sendMessageJob = null
            }
        }
    }

    private suspend fun handleConversationSendEvent(
        event: ConversationSendEvent,
        baseMeta: MessageMeta,
        userMessage: String,
        messagesWithUser: List<ChatMessage>,
    ) {
        // long: Coordinator 只冻结普通聊天的执行顺序；这里把稳定事件投影为当前会话 UI，删除确认由持久化协调器负责，ViewModel 只触发记忆候选和最终快照。
        when (event) {
            is ConversationSendEvent.SnapshotPersisted -> {
                // long: 该事件只标记发送前快照已经提交；显式删除 ID 的成功确认由同一个持久化协调器原子管理，ViewModel 不再维护第二份状态。
            }

            is ConversationSendEvent.ContextPrepared -> {
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = messagesWithUser,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(result = null)
            }

            is ConversationSendEvent.StreamDelta -> {
                withContext(Dispatchers.Main.immediate) {
                    scheduleStreamingAssistant(event.update, baseMeta)
                }
            }

            is ConversationSendEvent.Completed -> {
                flushStreamingAssistant(baseMeta)
                val response = event.response
                val finalMessages = uiState.chatMessages.upsertLastAssistant(
                    text = response.responseText,
                    reasoningSummaries = response.reasoningSummaries,
                    meta = baseMeta.copy(
                        requestUrl = response.requestUrl,
                        firstTokenLatencyMs = response.firstTokenLatencyMs,
                        latencyMs = response.latencyMs,
                    ),
                )
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = finalMessages,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                // long: 只有模型响应完整收敛后才生成长期记忆候选；取消或断流正文都不能成为新的跨会话事实来源。
                createMemoryCandidateAfterTurn(
                    userText = userMessage,
                    conversationId = uiState.selectedConversationId,
                    runId = null,
                )
                saveConversationSelection()
                saveCurrentProfileSelection()
            }

            is ConversationSendEvent.Cancelled -> {
                flushStreamingAssistant(baseMeta)
                val stoppedMessages = uiState.chatMessages.withCancelledGeneration(baseMeta)
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = stoppedMessages,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                saveConversationSelection()
            }

            is ConversationSendEvent.Failed -> {
                // long: 先强制刷出已收到的最后一个 delta，再将其标记为不完整并追加错误气泡，避免网络失败把用户已经看到的正文静默删除。
                flushStreamingAssistant(baseMeta)
                val failure = event.error as? ApiFailure
                val failureKind = failure?.kind?.title ?: FailureKind.UNKNOWN.title
                val failureMessage = event.error.message ?: "未知错误"
                val failedMessages = uiState.chatMessages.withFailedStreamingGeneration(
                    baseMeta = baseMeta,
                    errorKind = failureKind,
                    errorMessage = failureMessage,
                ) + ChatMessage(
                    role = "error",
                    text = event.error.toConversationErrorText(),
                    createdAt = System.currentTimeMillis(),
                    meta = baseMeta.copy(
                        finishReason = "failed",
                        errorKind = failureKind,
                        errorMessage = failureMessage,
                    ),
                )
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = failedMessages,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                saveConversationSelection()
            }
        }
    }

    private fun sendAgentRun(
        userMessage: String,
        runtimeSelection: AgentRuntimeSelection,
        conversationId: String = uiState.selectedConversationId.ifBlank { "conversation-" + System.currentTimeMillis() },
        retryOfRunId: String? = null,
        memoryRecallEnabled: Boolean = runtimeSelection.profile.memoryEnabled,
        workflowRunId: String? = null,
    ) {
        val effectiveMemoryRecallEnabled = memoryRecallEnabled && runtimeSelection.profile.memoryEnabled
        val currentConversation = uiState.conversations.firstOrNull { it.id == conversationId }
        clearAgentStateForConversation(conversationId)
        val userChatMessage = ChatMessage(
            role = "user",
            text = userMessage,
            createdAt = System.currentTimeMillis(),
        )
        val messagesWithUser = currentConversation?.messages.orEmpty() + userChatMessage
        val preparedContext = PreparedRequestContext.fromConversation(currentConversation)
        uiState = uiState.copy(
            sendingMessage = true,
            result = null,
            prompt = "",
            agentMemoryRecallEnabled = runtimeSelection.profile.memoryEnabled,
        ).withUpdatedConversation(
            conversationId = conversationId,
            messages = messagesWithUser,
            summary = preparedContext.summary,
            summaryUntilMessageId = preparedContext.summaryUntilMessageId,
            summaryUpdatedAt = preparedContext.summaryUpdatedAt,
            summaryModel = preparedContext.summaryModel,
        )
        // long: 待审批 Run 可能在用户决定前经历进程重建；先持久化发起消息，恢复后的审批卡片才能继续锚定到原消息和原 Run。
        saveConversationSelection()
        val goal = AgentCommand.goal(userMessage)
        sendMessageJob = viewModelScope.launch {
            try {
                val approvalGate = interactiveAgentApprovalGate(conversationId)
                val summary = agentRunUseCase.run(
                    conversationId = conversationId,
                    userMessageId = userChatMessage.id,
                    goal = goal,
                    config = runtimeSelection.config,
                    summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(uiState.promptSettings),
                    agentProfile = runtimeSelection.profile,
                    retryOfRunId = retryOfRunId,
                    memoryRecallEnabled = effectiveMemoryRecallEnabled,
                    invocationSource = if (workflowRunId == null) {
                        AgentInvocationSource.DIRECT
                    } else {
                        AgentInvocationSource.WORKFLOW
                    },
                    approvalGate = approvalGate,
                    onSnapshot = { snapshot ->
                        if (workflowRunId != null) {
                            withContext(Dispatchers.IO) {
                                workflowRepository.markAgentRunStarted(workflowRunId, snapshot.run.id)
                            }
                        }
                        publishAgentRunSnapshot(snapshot)
                    },
                )
                settleWorkflowLedger(
                    workflowRunId = workflowRunId,
                    agentStatus = AgentRunStatus.COMPLETED,
                    result = summary.responseText,
                )
                val finalMessages = messagesWithUser + ChatMessage(
                    role = "assistant",
                    text = summary.responseText,
                    createdAt = System.currentTimeMillis(),
                    origin = MessageOrigin.AGENT_RESULT,
                    verifiedAgentContext = summary.verifiedContext,
                )
                uiState = uiState
                    .withUpdatedConversation(
                        conversationId = conversationId,
                        messages = finalMessages,
                        summary = preparedContext.summary,
                        summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                        summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                        summaryModel = preparedContext.summaryModel,
                    )
                    .copy(sendingMessage = false, result = null)
                createMemoryCandidateAfterTurn(
                    userText = goal,
                    conversationId = conversationId,
                    runId = summary.runId,
                )
                clearPendingApprovalForConversation(conversationId)
                saveConversationSelection()
            } catch (error: CancellationException) {
                settleWorkflowLedger(
                    workflowRunId = workflowRunId,
                    agentStatus = AgentRunStatus.CANCELLED,
                    errorMessage = "用户停止工作流执行",
                )
                val stoppedMessages = messagesWithUser + ChatMessage(
                    role = "error",
                    text = "已停止 Agent 任务",
                    createdAt = System.currentTimeMillis(),
                )
                uiState = uiState
                    .withUpdatedConversation(
                        conversationId = conversationId,
                        messages = stoppedMessages,
                        summary = preparedContext.summary,
                        summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                        summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                        summaryModel = preparedContext.summaryModel,
                    )
                    .copy(sendingMessage = false, result = null)
                clearPendingApprovalForConversation(conversationId)
                saveConversationSelection()
            } catch (error: Throwable) {
                settleWorkflowLedger(
                    workflowRunId = workflowRunId,
                    agentStatus = AgentRunStatus.FAILED,
                    errorMessage = error.message ?: "未知错误",
                )
                val failedMessages = messagesWithUser + ChatMessage(
                    role = "error",
                    text = "Agent 任务失败\n${error.message ?: "未知错误"}",
                    createdAt = System.currentTimeMillis(),
                )
                uiState = uiState
                    .withUpdatedConversation(
                        conversationId = conversationId,
                        messages = failedMessages,
                        summary = preparedContext.summary,
                        summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                        summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                        summaryModel = preparedContext.summaryModel,
                    )
                    .copy(sendingMessage = false, result = null)
                clearPendingApprovalForConversation(conversationId)
                saveConversationSelection()
            } finally {
                pendingApprovalDecision = null
                sendMessageJob = null
                if (retryOfRunId != null) {
                    uiState = uiState.copy(retryingAgentRunId = null)
                    refreshAgentRunHistory()
                }
                if (workflowRunId != null) {
                    uiState = uiState.copy(runningWorkflowId = null)
                    refreshWorkflows()
                }
            }
        }
    }

    private suspend fun settleWorkflowLedger(
        workflowRunId: String? = null,
        agentRunId: String? = null,
        agentStatus: AgentRunStatus,
        result: String? = null,
        errorMessage: String? = null,
    ) {
        if (workflowRunId == null && agentRunId == null) return
        require(workflowRunId == null || agentRunId == null) { "工作流收敛只能使用一种关联 ID" }
        // long: 所有前台与恢复分支只报告 Agent 真实终态；本方法统一映射并写入 Workflow Ledger，避免某条异常路径漏写或写成不同状态。
        val workflowStatus = WorkflowAgentRunStatusPolicy.terminalStatus(agentStatus) ?: return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                if (workflowRunId != null) {
                    workflowRepository.completeRun(workflowRunId, workflowStatus, result, errorMessage)
                } else {
                    workflowRepository.completeByAgentRunId(requireNotNull(agentRunId), workflowStatus, result, errorMessage)
                }
            }
        }.onFailure { ledgerError ->
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                uiState = uiState.copy(workflowError = ledgerError.message ?: "工作流 Ledger 收敛失败")
            }
        }
    }

    private suspend fun reconcileWorkflowAfterResumeFailure(agentRunId: String, fallbackError: String) {
        val agentRun = withContext(NonCancellable + Dispatchers.IO) {
            agentRunRepository.runDetail(agentRunId)?.snapshot?.run
        } ?: return
        if (agentRun.status == AgentRunStatus.WAITING_APPROVAL) {
            // long: Skill 删除或升版会在批准决定落库前阻止恢复；Agent 与审批仍有效时，Workflow 必须继续等待用户处理，不能伪造失败终态。
            return
        }
        settleWorkflowLedger(
            agentRunId = agentRunId,
            agentStatus = agentRun.status,
            result = agentRun.result,
            errorMessage = agentRun.errorMessage ?: fallbackError,
        )
    }

    private fun interactiveAgentApprovalGate(conversationId: String): ApprovalGate {
        return object : ApprovalGate {
            override suspend fun requestApproval(
                runId: String,
                toolCall: ToolCall,
                definition: ToolDefinition,
            ): ApprovalDecision {
                return awaitAgentApproval(
                    conversationId = conversationId,
                    runId = runId,
                    toolCall = toolCall,
                    definition = definition,
                )
            }
        }
    }

    private suspend fun awaitAgentApproval(
        conversationId: String,
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): ApprovalDecision {
        val request = withContext(Dispatchers.IO) {
            agentRunRepository.createApprovalRequest(
                conversationId = conversationId,
                runId = runId,
                toolCall = toolCall,
                definition = definition,
            )
        }
        val deferred = CompletableDeferred<ApprovalDecision>()
        withContext(Dispatchers.Main.immediate) {
            // long: 审批请求是 Agent 从“模型建议”进入“真实执行”的安全闸口，UI 只展示 Runtime 已校验过的工具定义和参数，不接受模型自称的风险等级。
            pendingApprovalDecision = deferred
            rememberPendingApproval(AgentApprovalUiState.from(request))
        }
        return try {
            // long: 审批是用户确认真实副作用的安全闸口，等待时间不等同于 Agent 执行耗时；这里不主动过期，避免用户阅读工具参数稍久就把任务误判失败。
            deferred.await()
        } finally {
            val unresolvedStatus = when {
                deferred.isCancelled -> ApprovalRequestStatus.CANCELLED
                !deferred.isCompleted -> ApprovalRequestStatus.CANCELLED
                else -> null
            }
            if (unresolvedStatus != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    agentRunRepository.decideApprovalRequest(
                        requestId = request.id,
                        status = unresolvedStatus,
                        reason = "审批等待已取消",
                    )
                }
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (pendingApprovalDecision === deferred) {
                    pendingApprovalDecision = null
                    clearPendingApprovalForConversation(conversationId)
                }
            }
        }
    }

    private suspend fun publishAgentRunSnapshot(snapshot: AgentRunSnapshot) {
        withContext(Dispatchers.Main.immediate) {
            rememberAgentRun(snapshot)
        }
    }

    private fun rememberAgentRun(snapshot: AgentRunSnapshot) {
        activeAgentRunsByConversation[snapshot.run.conversationId] = snapshot
        val existingDetail = uiState.agentRunHistory.firstOrNull { it.snapshot.run.id == snapshot.run.id }
        val updatedHistory = if (uiState.agentRunHistory.isNotEmpty() || snapshot.run.retryOfRunId != null) {
            listOf(
                (existingDetail ?: AgentRunDetailRecord(snapshot = snapshot, approvals = emptyList()))
                    .copy(snapshot = snapshot),
            ) + uiState.agentRunHistory.filterNot { it.snapshot.run.id == snapshot.run.id }
        } else {
            uiState.agentRunHistory
        }
        val selectedRetryRunId = snapshot.run.id.takeIf { snapshot.run.retryOfRunId != null }
        if (snapshot.run.conversationId == uiState.selectedConversationId) {
            uiState = uiState.copy(
                activeAgentRun = snapshot,
                agentRunHistory = updatedHistory,
                selectedAgentRunId = selectedRetryRunId ?: uiState.selectedAgentRunId,
            )
        } else {
            uiState = uiState.copy(
                agentRunHistory = updatedHistory,
                selectedAgentRunId = selectedRetryRunId ?: uiState.selectedAgentRunId,
            )
        }
    }

    private fun rememberPendingApproval(approval: AgentApprovalUiState) {
        pendingAgentApprovalsByConversation[approval.conversationId] = approval
        if (approval.conversationId == uiState.selectedConversationId) {
            uiState = uiState.copy(pendingAgentApproval = approval)
        }
    }

    private fun restoreRecoveredAgentRuns(details: List<AgentRunDetailRecord>) {
        if (details.isEmpty()) return
        var restoredMissingUserMessage = false
        details.forEach { detail ->
            val run = detail.snapshot.run
            uiState.conversations
                .firstOrNull { it.id == run.conversationId }
                ?.let { conversation ->
                    val recoveredMessages = conversation.messages.withRecoveredAgentUserMessage(run)
                    if (recoveredMessages != conversation.messages) {
                        // long: 旧版本可能只持久化了 Run 和审批；用 Run 中的稳定消息 ID 补回 UI 锚点，避免审批存在但用户无入口继续。
                        uiState = uiState.withUpdatedConversation(
                            conversationId = conversation.id,
                            messages = recoveredMessages,
                            summary = conversation.summary,
                            summaryUntilMessageId = conversation.summaryUntilMessageId,
                            summaryUpdatedAt = conversation.summaryUpdatedAt,
                            summaryModel = conversation.summaryModel,
                        )
                        restoredMissingUserMessage = true
                    }
                }
            activeAgentRunsByConversation[run.conversationId] = detail.snapshot
            detail.approvals
                .firstOrNull { it.status == ApprovalRequestStatus.PENDING }
                ?.let { request ->
                    pendingAgentApprovalsByConversation[run.conversationId] =
                        AgentApprovalUiState.from(request).copy(restoredFromProcess = true)
                }
        }
        val selectedConversationId = uiState.selectedConversationId
        uiState = uiState.copy(
            activeAgentRun = activeAgentRunsByConversation[selectedConversationId],
            pendingAgentApproval = pendingAgentApprovalsByConversation[selectedConversationId],
            agentRunHistory = details + uiState.agentRunHistory.filterNot { existing ->
                details.any { it.snapshot.run.id == existing.snapshot.run.id }
            },
            selectedAgentRunId = uiState.selectedAgentRunId
                ?: details.firstOrNull()?.snapshot?.run?.id,
        )
        if (restoredMissingUserMessage) {
            saveConversationSelection()
        }
    }

    private suspend fun resumeRecoveredCommittedToolRuns(details: List<AgentRunDetailRecord>) {
        if (details.isEmpty()) return
        var conversationsChanged = false
        details.forEach { detail ->
            val source = detail.snapshot.run
            try {
                val summary = agentRunUseCase.resumeCommittedToolRun(
                    detail = detail,
                    onSnapshot = ::publishAgentRunSnapshot,
                )
                val workflowContinuation = withContext(NonCancellable + Dispatchers.IO) {
                    workflowRepository.completeByAgentRunId(
                        agentRunId = summary.runId,
                        status = WorkflowRunStatus.COMPLETED,
                        result = summary.responseText,
                    )?.takeIf { it.status == WorkflowRunStatus.RUNNING }
                }
                if (workflowContinuation != null) {
                    // long: 本阶段只恢复当前 Agent 工具的后置验证；Workflow 后续步骤保持新 Run 重试语义，避免把单工具幂等证明扩大为通用执行栈恢复。
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            workflowContinuation.id,
                            WorkflowRunStatus.FAILED,
                            errorMessage = "已恢复当前工具验证；后续步骤请创建关联新 Run 重试",
                        )
                    }
                }
                uiState.conversations.firstOrNull { it.id == source.conversationId }?.let { conversation ->
                    val recoveredMessages = conversation.messages.withRecoveredAgentUserMessage(source) + ChatMessage(
                        role = "assistant",
                        text = summary.responseText,
                        createdAt = System.currentTimeMillis(),
                        origin = MessageOrigin.AGENT_RESULT,
                        verifiedAgentContext = summary.verifiedContext,
                    )
                    uiState = uiState.withUpdatedConversation(
                        conversationId = conversation.id,
                        messages = recoveredMessages,
                        summary = conversation.summary,
                        summaryUntilMessageId = conversation.summaryUntilMessageId,
                        summaryUpdatedAt = conversation.summaryUpdatedAt,
                        summaryModel = conversation.summaryModel,
                    )
                    conversationsChanged = true
                }
            } catch (error: CancellationException) {
                // long: 恢复被取消后只按 Agent 的真实终态收敛 Workflow，绝不把当前步骤继续到后续工具。
                reconcileWorkflowAfterResumeFailure(source.id, "验证阶段恢复已取消")
                throw error
            } catch (error: Throwable) {
                // long: 回读或验证失败时同步关闭关联 Workflow，避免 Agent 已失败但工作流仍显示 RUNNING 或继续执行下一步骤。
                reconcileWorkflowAfterResumeFailure(source.id, error.message ?: "验证阶段恢复失败")
            }
        }
        if (conversationsChanged) saveConversationSelection()
        refreshAgentRunHistory()
        refreshWorkflows()
    }

    private suspend fun resumeRecoveredVerifiedToolRuns(details: List<AgentRunDetailRecord>) {
        if (details.isEmpty()) return
        var conversationsChanged = false
        details.forEach { detail ->
            val source = detail.snapshot.run
            try {
                val summary = agentRunUseCase.resumeVerifiedToolRun(
                    detail = detail,
                    onSnapshot = ::publishAgentRunSnapshot,
                )
                val workflowContinuation = withContext(NonCancellable + Dispatchers.IO) {
                    workflowRepository.completeByAgentRunId(
                        agentRunId = summary.runId,
                        status = WorkflowRunStatus.COMPLETED,
                        result = summary.responseText,
                    )?.takeIf { it.status == WorkflowRunStatus.RUNNING }
                }
                if (workflowContinuation != null) {
                    // long: 当前只恢复已经验证完成的 Agent 工具事实；Workflow 后续步骤仍需关联新 Run，避免把局部终态证明扩大成后台执行栈续跑。
                    withContext(NonCancellable + Dispatchers.IO) {
                        workflowRepository.completeRun(
                            workflowContinuation.id,
                            WorkflowRunStatus.FAILED,
                            errorMessage = "已恢复当前 Agent Run；Workflow 后续步骤请创建关联新 Run 重试",
                        )
                    }
                }
                uiState.conversations.firstOrNull { it.id == source.conversationId }?.let { conversation ->
                    val recoveredMessages = conversation.messages.withRecoveredAgentUserMessage(source) + ChatMessage(
                        role = "assistant",
                        text = summary.responseText,
                        createdAt = System.currentTimeMillis(),
                        origin = MessageOrigin.AGENT_RESULT,
                        verifiedAgentContext = summary.verifiedContext,
                    )
                    uiState = uiState.withUpdatedConversation(
                        conversationId = conversation.id,
                        messages = recoveredMessages,
                        summary = conversation.summary,
                        summaryUntilMessageId = conversation.summaryUntilMessageId,
                        summaryUpdatedAt = conversation.summaryUpdatedAt,
                        summaryModel = conversation.summaryModel,
                    )
                    conversationsChanged = true
                }
            } catch (error: CancellationException) {
                reconcileWorkflowAfterResumeFailure(source.id, "已验证结果恢复已取消")
                throw error
            } catch (error: Throwable) {
                reconcileWorkflowAfterResumeFailure(source.id, error.message ?: "已验证结果恢复失败")
            }
        }
        if (conversationsChanged) saveConversationSelection()
        refreshAgentRunHistory()
        refreshWorkflows()
    }

    private fun clearPendingApprovalForConversation(conversationId: String) {
        pendingAgentApprovalsByConversation.remove(conversationId)
        if (conversationId == uiState.selectedConversationId) {
            uiState = uiState.copy(pendingAgentApproval = null)
        }
    }

    private fun clearAgentStateForConversation(conversationId: String) {
        activeAgentRunsByConversation.remove(conversationId)
        pendingAgentApprovalsByConversation.remove(conversationId)
        if (conversationId == uiState.selectedConversationId) {
            uiState = uiState.copy(
                activeAgentRun = null,
                pendingAgentApproval = null,
            )
        }
    }

    private fun validatedConfig(): ProviderRequestConfig? {
        ProviderApiUrlBuilder.validate(uiState.baseUrl)?.let {
            showValidation(it)
            return null
        }
        if (uiState.enabledModels.isEmpty()) {
            showValidation("请先在设置页的模型提供方管理中勾选可对话模型")
            return null
        }
        if (uiState.model.isBlank()) {
            showValidation("请选择模型")
            return null
        }
        if (uiState.model !in uiState.enabledModels) {
            showValidation("这个模型没有在设置页的模型提供方管理中勾选")
            return null
        }
        val profile = selectedProfile()
        return ProviderRequestConfig(
            baseUrl = profile.baseUrl.trim(),
            apiKey = profile.apiKey.trim(),
            model = profile.model.trim(),
            userAgent = uiState.userAgent,
            apiMode = uiState.apiMode,
            streamingEnabled = uiState.streamingEnabled,
            reasoningSummaryEnabled = uiState.reasoningSummaryEnabled,
            maxTokens = ProviderProfile.FIXED_MAX_TOKENS,
        )
    }

    private fun validatedSelectedAgentRuntimeSelection(): AgentRuntimeSelection? {
        val profile = uiState.agentProfiles.firstOrNull { it.id == uiState.selectedAgentProfileId }
        if (profile == null) {
            showValidation("请先在设置页创建并选择 Agent Profile")
            return null
        }
        return validatedAgentRuntimeSelection(profile.snapshot())
    }

    private fun validatedAgentRuntimeSelection(profile: AgentProfileSnapshot): AgentRuntimeSelection? {
        runCatching { AgentProfilePolicy.validateRunnable(profile) }
            .onFailure {
                showValidation(it.message ?: "Agent Profile 配置无效")
                return null
            }
        val registered = uiState.registeredAgentTools.mapTo(hashSetOf()) { it.name }
        val unknownTools = profile.allowedToolNames.filter { it !in registered }
        if (unknownTools.isNotEmpty()) {
            showValidation("Agent Profile 包含未注册工具：${unknownTools.sorted().joinToString()}")
            return null
        }
        return runCatching {
            AgentRuntimeSelection(
                config = AgentProfileRuntimeConfigPolicy.resolve(profile, uiState.profiles, uiState.userAgent),
                profile = profile,
            )
        }.getOrElse { error ->
            showValidation(error.message ?: "Agent Profile 请求配置无效")
            null
        }
    }

    private fun selectedProfile(): ProviderProfile {
        val storedProfile = uiState.profiles.firstOrNull { it.id == uiState.selectedProfileId }
            ?: ProviderProfile.blank(uiState.selectedProfileId)
        return storedProfile.copy(
            name = uiState.profileName,
            baseUrl = uiState.baseUrl,
            apiKey = uiState.apiKey,
            model = uiState.model,
            availableModels = uiState.availableModels,
            enabledModels = uiState.enabledModels,
        )
    }

    private fun repairIncompleteAgentProfiles(provider: ProviderProfile) {
        val replacementModel = provider.model.takeIf { it in provider.enabledModels }
            ?: provider.enabledModels.firstOrNull()
            ?: return
        val repairs = uiState.agentProfiles.filter { it.providerId == provider.id && it.model.isBlank() }
        if (repairs.isEmpty()) return
        viewModelScope.launch {
            val updated = repairs.map { profile ->
                profile.copy(model = replacementModel, updatedAt = System.currentTimeMillis())
            }
            withContext(Dispatchers.IO) { updated.forEach { agentProfileStore.upsert(it) } }
            val byId = updated.associateBy { it.id }
            uiState = uiState.copy(
                agentProfiles = uiState.agentProfiles.map { byId[it.id] ?: it },
                result = OperationResult(true, "Agent 已就绪", "已为默认 Agent 绑定模型 $replacementModel"),
            )
        }
    }

    private fun saveCurrentProfileSelection() {
        val profiles = uiState.profiles.map {
            if (it.id == uiState.selectedProfileId) it.copy(model = uiState.model) else it
        }
        uiState = uiState.copy(profiles = profiles)
        saveProfilesSnapshot(profiles, uiState.selectedProfileId)
    }

    private fun saveConversationSelection() {
        conversationPersistenceCoordinator.saveLatest(
            conversations = uiState.conversations.map { it.toStored() },
            selectedConversationId = uiState.selectedConversationId,
        )
    }

    private fun saveProfilesSnapshot(profiles: List<ProviderProfile>, selectedProfileId: String) {
        saveProfilesJob?.cancel()
        saveProfilesJob = viewModelScope.launch {
            // long: Provider 保存包含 Keystore 加密和 Room 写入，必须放到后台；只保留最后一次快照，避免快速切换模型时旧写入覆盖新选择。
            configStore.save(profiles, selectedProfileId)
        }
    }

    private inline fun updatePromptSettings(block: PromptSettings.() -> PromptSettings) {
        val settings = uiState.promptSettings.block()
        uiState = uiState.copy(promptSettings = settings, result = null)
        uiPreferenceStore.savePromptSettings(settings)
    }

    private suspend fun syncStoredProfile(
        profile: ProviderProfile,
        showPopup: Boolean,
        keepBatchResult: Boolean,
    ) {
        ProviderApiUrlBuilder.validate(profile.baseUrl)?.let { message ->
            val result = OperationResult(false, "同步失败", message)
            applySyncFailure(profile.id, result, showPopup, keepBatchResult)
            return
        }

        uiState = uiState.copy(
            syncingProfileIds = uiState.syncingProfileIds + profile.id,
            result = null,
        )
        val config = ProviderRequestConfig(
            baseUrl = profile.baseUrl.trim(),
            apiKey = profile.apiKey.trim(),
            model = "",
            userAgent = uiState.userAgent,
        )
        runCatching { client.fetchModels(config) }
            .onSuccess { models ->
                val distinctModels = models.distinct()
                val selectedModel = profile.model
                    .takeIf { it in distinctModels }
                    ?: distinctModels.firstOrNull()
                    ?: ""
                val syncedProfile = profile.copy(
                    model = selectedModel,
                    availableModels = distinctModels,
                    enabledModels = distinctModels,
                    lastSyncedAt = nowSyncTimeText(),
                )
                val profiles = uiState.profiles.map {
                    if (it.id == profile.id) syncedProfile else it
                }
                val selectedId = uiState.selectedProfileId
                val nextState = uiState.copy(
                    profiles = profiles,
                    syncingProfileIds = uiState.syncingProfileIds - profile.id,
                    batchSyncResults = if (keepBatchResult) {
                        uiState.batchSyncResults + (profile.id to "同步成功")
                    } else {
                        uiState.batchSyncResults - profile.id
                    },
                    result = if (showPopup) {
                        OperationResult(true, "同步成功", "获取到 ${distinctModels.size} 个模型")
                    } else {
                        null
                    },
                )
                uiState = if (selectedId == profile.id) {
                    nextState.fromProfile(syncedProfile, selectedId)
                } else {
                    nextState
                }
                configStore.save(profiles, selectedId)
                repairIncompleteAgentProfiles(syncedProfile)
            }
            .onFailure { error ->
                val failure = error as? ApiFailure
                applySyncFailure(
                    profileId = profile.id,
                    result = OperationResult(
                        success = false,
                        title = "同步失败",
                        message = error.message ?: failure?.kind?.title ?: "未知错误",
                    ),
                    showPopup = showPopup,
                    keepBatchResult = keepBatchResult,
                )
            }
    }

    private fun applySyncFailure(
        profileId: String,
        result: OperationResult,
        showPopup: Boolean,
        keepBatchResult: Boolean,
    ) {
        uiState = uiState.copy(
            syncingProfileIds = uiState.syncingProfileIds - profileId,
            batchSyncResults = if (keepBatchResult) {
                uiState.batchSyncResults + (profileId to "同步失败")
            } else {
                uiState.batchSyncResults - profileId
            },
            result = if (showPopup) result else null,
        )
    }

    private fun updateAndSaveSelectedProfile(block: XiaoLingUiState.() -> XiaoLingUiState) {
        uiState = uiState.block()
        saveCurrentProfileSelection()
    }

    private inline fun updateDraft(block: ProviderEditDraft.() -> ProviderEditDraft) {
        uiState.manageDraft?.let {
            uiState = uiState.copy(manageDraft = it.block(), result = null)
        }
    }

    private fun showFailure(error: Throwable, loadingModels: Boolean? = null, sendingMessage: Boolean? = null) {
        val failure = error as? ApiFailure
        val draft = uiState.manageDraft
        uiState = uiState.copy(
            loadingModels = loadingModels ?: uiState.loadingModels,
            sendingMessage = sendingMessage ?: uiState.sendingMessage,
            manageDraft = draft?.copy(loadingModels = loadingModels ?: draft.loadingModels),
            result = OperationResult(
                success = false,
                title = failure?.kind?.title ?: FailureKind.UNKNOWN.title,
                message = error.message ?: "未知错误",
            ),
        )
    }

    private fun showValidation(message: String) {
        uiState = uiState.copy(
            result = OperationResult(
                success = false,
                title = "配置不完整",
                message = message,
            ),
        )
    }

    private fun showSuccess(title: String, message: String) {
        uiState = uiState.copy(
            result = OperationResult(
                success = true,
                title = title,
                message = message,
            ),
        )
    }

    private fun nowSyncTimeText(): String = SimpleDateFormat(FULL_TIME_PATTERN, Locale.getDefault()).format(Date())

    private fun Throwable.toConversationErrorText(): String {
        val failure = this as? ApiFailure
        val title = failure?.kind?.title ?: FailureKind.UNKNOWN.title
        val detail = message ?: "未知错误"
        return "$title\n$detail"
    }

    private fun scheduleStreamingAssistant(update: StreamDeltaUpdate, baseMeta: MessageMeta) {
        pendingStreamingUpdate = update
        if (streamingThrottleJob?.isActive == true) return
        // long: SSE delta 可能按 token 高频到达；UI 只保留最新累计文本并按 30ms 合并刷新，避免 Markdown 每个 token 都触发重组。
        streamingThrottleJob = viewModelScope.launch {
            delay(STREAMING_UI_THROTTLE_MS)
            flushStreamingAssistant(baseMeta)
        }
    }

    private fun flushStreamingAssistant(baseMeta: MessageMeta) {
        val update = pendingStreamingUpdate ?: return
        pendingStreamingUpdate = null
        streamingThrottleJob?.cancel()
        streamingThrottleJob = null
        val updatedMessages = uiState.chatMessages.upsertLastAssistant(
            text = update.accumulatedText,
            meta = baseMeta.copy(firstTokenLatencyMs = update.firstTokenLatencyMs),
        )
        // long: 流式期间只刷新内存 UI，不在这里写 SharedPreferences；最终成功或失败时再统一持久化完整会话。
        uiState = uiState
            .withUpdatedCurrentConversation(
                messages = updatedMessages,
                summary = uiState.conversationSummary,
            )
            .copy(result = null)
    }

    private fun ProviderRequestConfig.toBaseMessageMeta(profile: ProviderProfile) = MessageMeta(
        providerId = profile.id,
        providerName = profile.name,
        model = model.trim(),
        apiMode = apiMode,
        streaming = streamingEnabled,
    )

    private fun List<ChatMessage>.upsertLastAssistant(
        text: String,
        meta: MessageMeta?,
        reasoningSummaries: List<ModelReasoningSummary> = emptyList(),
    ): List<ChatMessage> {
        val last = lastOrNull()
        return if (last?.role == "assistant") {
            dropLast(1) + last.copy(
                text = text,
                meta = meta,
                parts = ProviderMessagePartPolicy.fromResponse(last.id, text, reasoningSummaries),
            )
        } else {
            val message = ChatMessage(
                role = "assistant",
                text = text,
                createdAt = System.currentTimeMillis(),
                meta = meta,
            )
            this + message.copy(
                parts = ProviderMessagePartPolicy.fromResponse(message.id, text, reasoningSummaries),
            )
        }
    }

    private fun List<ChatMessage>.withCancelledGeneration(baseMeta: MessageMeta): List<ChatMessage> {
        val last = lastOrNull()
        return if (last?.role == "assistant") {
            dropLast(1) + last.copy(
                meta = (last.meta ?: baseMeta).copy(
                    finishReason = "cancelled",
                    errorKind = "已取消",
                    errorMessage = "用户停止生成",
                ),
            )
        } else {
            this + ChatMessage(
                role = "error",
                text = "已停止生成",
                createdAt = System.currentTimeMillis(),
                meta = baseMeta.copy(
                    finishReason = "cancelled",
                    errorKind = "已取消",
                    errorMessage = "用户停止生成",
                ),
            )
        }
    }

    private fun XiaoLingUiState.fromProfile(profile: ProviderProfile, selectedId: String) = copy(
        selectedProfileId = selectedId,
        profileName = profile.name,
        baseUrl = profile.baseUrl,
        apiKey = profile.apiKey,
        model = profile.model.takeIf { it in profile.enabledModels } ?: profile.enabledModels.firstOrNull().orEmpty(),
        availableModels = profile.availableModels,
        enabledModels = profile.enabledModels,
        result = result,
    )

    private fun initialUiState(
        themeMode: AppThemeMode,
        promptSettings: PromptSettings,
        memoryCandidatesEnabled: Boolean,
        userAgent: String,
        reasoningSummaryEnabled: Boolean,
    ): XiaoLingUiState {
        val profile = ProviderProfile.blank()
        val now = System.currentTimeMillis()
        val conversation = StoredConversation(
            id = "conversation-$now",
            title = "新会话",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        return StoredProfiles(
            profiles = listOf(profile),
            selectedProfileId = profile.id,
        )
            .toUiState()
            .withConversations(
                StoredConversations(
                    conversations = listOf(conversation),
                    selectedConversationId = conversation.id,
                ),
            )
            .copy(
                themeMode = themeMode,
                promptSettings = promptSettings,
                memoryCandidatesEnabled = memoryCandidatesEnabled,
                userAgent = userAgent,
                reasoningSummaryEnabled = reasoningSummaryEnabled,
            )
    }

    private fun com.longdev.xiaoling.storage.StoredProfiles.toUiState(): XiaoLingUiState {
        val safeProfiles = profiles.ifEmpty { listOf(ProviderProfile.blank()) }
        val selected = safeProfiles.firstOrNull { it.id == selectedProfileId } ?: safeProfiles.first()
        return XiaoLingUiState(
            profiles = safeProfiles,
            selectedProfileId = selected.id,
        ).fromProfile(selected, selected.id)
    }

    private fun XiaoLingUiState.withConversations(stored: StoredConversations): XiaoLingUiState {
        val conversations = stored.conversations.map { it.toSession() }
            .collapseDuplicateEmptyConversations(stored.selectedConversationId)
            .ifEmpty {
                val now = System.currentTimeMillis()
                listOf(
                    ConversationSession(
                        id = "conversation-$now",
                        title = "新会话",
                        summary = "",
                        summaryUntilMessageId = null,
                        summaryUpdatedAt = null,
                        summaryModel = null,
                        messages = emptyList(),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        val selected = conversations.firstOrNull { it.id == stored.selectedConversationId } ?: conversations.first()
        return copy(
            conversations = conversations,
            selectedConversationId = selected.id,
            conversationTitle = selected.title,
            conversationSummary = selected.summary,
            chatMessages = selected.messages,
        )
    }

    private fun StoredConversation.toSession() = ConversationSession(
        id = id,
        title = title,
        summary = summary,
        summaryUntilMessageId = summaryUntilMessageId,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryModel = summaryModel,
        messages = messages.map { it.toChatMessage() },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun StoredConversationMessage.toChatMessage(): ChatMessage {
        return ChatMessage(
            id = id,
            role = role,
            text = text,
            createdAt = createdAt,
            origin = MessageOrigin.fromStored(origin, role),
            verifiedAgentContext = VerifiedAgentContextCodec.decode(verifiedAgentContext),
            meta = meta?.toMessageMeta(),
            // long: Repository 已完成损坏 part 过滤和可信证据重投影；UI 保留该结果，渲染与再次保存时仍由 effectiveParts() 重检信任边界。
            parts = parts,
        )
    }

    private fun ConversationSession.toStored() = StoredConversation(
        id = id,
        title = title,
        summary = summary,
        summaryUntilMessageId = summaryUntilMessageId,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryModel = summaryModel,
        messages = messages.map { it.toStored() },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ChatMessage.toStored() = StoredConversationMessage(
        id = id,
        role = role,
        text = text,
        createdAt = createdAt,
        origin = origin.name,
        verifiedAgentContext = verifiedAgentContext?.let(VerifiedAgentContextCodec::encode),
        meta = meta?.toStoredMeta(),
        parts = effectiveParts(),
    )

    private fun StoredMessageMeta.toMessageMeta() = MessageMeta(
        providerId = providerId,
        providerName = providerName,
        model = model,
        apiMode = apiMode?.let { runCatching { ApiMode.valueOf(it) }.getOrNull() },
        streaming = streaming,
        requestUrl = requestUrl,
        firstTokenLatencyMs = firstTokenLatencyMs,
        latencyMs = latencyMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        finishReason = finishReason,
        errorKind = errorKind,
        errorMessage = errorMessage,
    )

    private fun MessageMeta.toStoredMeta() = StoredMessageMeta(
        providerId = providerId,
        providerName = providerName,
        model = model,
        apiMode = apiMode?.name,
        streaming = streaming,
        requestUrl = requestUrl,
        firstTokenLatencyMs = firstTokenLatencyMs,
        latencyMs = latencyMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        finishReason = finishReason,
        errorKind = errorKind,
        errorMessage = errorMessage,
    )

    companion object {
        private const val DEFAULT_AGENT_PROFILE_ID = "agent-profile-default"
        private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private const val SUMMARY_MAX_CHARS = 4_000
        private const val STREAMING_UI_THROTTLE_MS = 30L
        private const val AGENT_RUN_HISTORY_LIMIT = 50
        private const val MEMORY_MANAGEMENT_LIMIT = 200
        private const val MEMORY_CANDIDATE_LIMIT = 100
        private const val MEMORY_SEARCH_DEBOUNCE_MS = 250L
    }
}
