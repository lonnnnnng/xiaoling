package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.McpServerConfig
import com.longdev.xiaoling.agent.McpToolDescriptor
import com.longdev.xiaoling.agent.GitHubSkillCandidate
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistentSummary
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSampleSummary
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.prompt.PromptSettings
import com.longdev.xiaoling.share.SharedDraftPayload
import com.longdev.xiaoling.storage.SecureConfigStore
import com.longdev.xiaoling.system.ProcessExitObservation
import com.longdev.xiaoling.ui.workflow.WorkflowDeviceActionApprovalEvidence
import com.longdev.xiaoling.ui.workflow.WorkflowDeviceObservationUiState
import com.longdev.xiaoling.ui.workflow.WorkflowRetryConfirmationUiState
import com.longdev.xiaoling.ui.agenttask.AgentRetryConfirmationUiState
import com.longdev.xiaoling.ui.memory.AgentMemoryEditUiState
import com.longdev.xiaoling.ui.provider.ProviderEditDraft

data class XiaoLingUiState(
    val genUiActionLeases: Map<String, com.longdev.xiaoling.genui.GenUiActionLease> = emptyMap(),
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
    val personalTaskMode: Boolean = false,
    val personalTaskEnvironment: PersonalTaskEnvironment = PersonalTaskEnvironment(),
    val pendingPersonalTaskPlan: PendingPersonalTaskPlanUiState? = null,
    val personalTaskOperationPhase: PersonalTaskOperationUiPhase? = null,
    val personalTaskFailure: PersonalTaskFailureUiState? = null,
    val personalTaskCompletion: PersonalTaskCompletionUiState? = null,
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val reasoningSummaryEnabled: Boolean = false,
    val pendingImage: ImageAttachment? = null,
    val pendingDocument: DocumentAttachment? = null,
    val pendingSharedDraft: SharedDraftPayload? = null,
    val pendingSharedDraftIsRemote: Boolean = false,
    val sharedDraftImported: Boolean = false,
    val sharedDraftImportedIsRemote: Boolean = false,
    val sharedDraftNavigationVersion: Long = 0L,
    val attachingImage: Boolean = false,
    val attachingDocument: Boolean = false,
    val loadingConversationMessages: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val knowledgeReferenceStatuses: Map<KnowledgeReference, KnowledgeReferenceStatus> = emptyMap(),
    val failedKnowledgeReferenceStatuses: Set<KnowledgeReference> = emptySet(),
    val answerabilityNotices: Map<String, KnowledgeAnswerabilityUserNotice> = emptyMap(),
    val conversations: List<ConversationSession> = emptyList(),
    val selectedConversationId: String = "",
    val conversationTitle: String = "",
    val conversationSummary: String = "",
    val manageDraft: ProviderEditDraft? = null,
    val syncingProfileIds: Set<String> = emptySet(),
    val syncingAllProfiles: Boolean = false,
    val batchSyncResults: Map<String, String> = emptyMap(),
    val activeAgentRun: AgentRunSnapshot? = null,
    val directAgentCurrentFact: DirectAgentCurrentFactUiState? = null,
    val refreshingDirectAgentCurrentFact: Boolean = false,
    val agentMemoryRecallEnabled: Boolean = true,
    val answerabilityShadowEnabled: Boolean = false,
    val answerabilityShadowSampleSummary: KnowledgeAnswerabilityShadowSampleSummary =
        KnowledgeAnswerabilityShadowSampleSummary(),
    val answerabilityShadowPersistentSummary: KnowledgeAnswerabilityShadowPersistentSummary =
        KnowledgeAnswerabilityShadowPersistentSummary(),
    val pendingAgentApproval: AgentApprovalUiState? = null,
    val loadingAgentRunHistory: Boolean = false,
    val agentRunHistory: List<AgentRunDetailRecord> = emptyList(),
    val selectedAgentRunId: String? = null,
    val agentRunHistoryError: String? = null,
    val loadingProcessExitObservations: Boolean = false,
    val processExitObservations: List<ProcessExitObservation> = emptyList(),
    val processExitObservationError: String? = null,
    val retryingAgentRunId: String? = null,
    val stoppingAgentRunIds: Set<String> = emptySet(),
    val pendingAgentRetryConfirmation: AgentRetryConfirmationUiState? = null,
    val childBatchDraftOpen: Boolean = false,
    val childBatchRunning: Boolean = false,
    val childBatchParentRunId: String? = null,
    val agentRetryNavigationConversationId: String? = null,
    val loadingMemories: Boolean = false,
    val memories: List<AgentMemoryRecord> = emptyList(),
    val memoryCandidatesEnabled: Boolean = false,
    val loadingMemoryCandidates: Boolean = false,
    val memoryCandidates: List<AgentMemoryCandidateRecord> = emptyList(),
    val memoryMaintenanceRunning: Boolean = false,
    val memoryMaintenanceMessage: String? = null,
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
    val githubSkillCandidates: List<GitHubSkillCandidate> = emptyList(),
    val mutatingSkillIds: Set<String> = emptySet(),
    val skillError: String? = null,
    val pendingLocalSkillDelete: AgentSkillRecord? = null,
    val loadingMcpServers: Boolean = false,
    val savingMcpServer: Boolean = false,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val mutatingMcpServerIds: Set<String> = emptySet(),
    val mcpToolsByServer: Map<String, List<McpToolDescriptor>> = emptyMap(),
    val loadingMcpToolServerIds: Set<String> = emptySet(),
    val mutatingMcpToolKeys: Set<String> = emptySet(),
    val mcpError: String? = null,
    val pendingMcpServerDelete: McpServerConfig? = null,
    val agentProfiles: List<AgentProfileRecord> = emptyList(),
    val selectedAgentProfileId: String = "",
    val registeredAgentTools: List<ToolDefinition> = emptyList(),
    val mutatingAgentProfileIds: Set<String> = emptySet(),
    val agentProfileError: String? = null,
    val loadingWorkflows: Boolean = false,
    val workflows: List<WorkflowRecord> = emptyList(),
    val workflowRuns: List<WorkflowRunDetail> = emptyList(),
    val workflowDeviceObservationsByAgentRunId: Map<String, List<WorkflowDeviceObservationUiState>> = emptyMap(),
    val workflowDeviceActionApprovalsByAgentRunId: Map<String, List<WorkflowDeviceActionApprovalEvidence>> = emptyMap(),
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
    val scheduledTaskResultWorkflowId: String? = null,
    val scheduledTaskResultTaskId: String? = null,
    val scheduledTaskResultWorkflowRunId: String? = null,
    val scheduledTaskResultNavigationVersion: Long = 0L,
    val backupBusy: Boolean = false,
    val backupRestartRequired: Boolean = false,
    val result: OperationResult? = null,
)

internal fun mergeAnswerabilityShadowInitializationState(
    initializedState: XiaoLingUiState,
    runtimeState: XiaoLingUiState,
): XiaoLingUiState = initializedState.copy(
    answerabilityShadowEnabled = runtimeState.answerabilityShadowEnabled,
    answerabilityShadowSampleSummary = runtimeState.answerabilityShadowSampleSummary,
    // long: 匿名账本可能在 Profile/会话加载完成前已经读回；启动整表状态重建必须保留该结果，不能让默认零值覆盖真实跨进程累计。
    answerabilityShadowPersistentSummary = runtimeState.answerabilityShadowPersistentSummary,
    // long: 通知令牌可能在冷启动初始化期间先被 Activity 消费；Room 全量状态装载不能覆盖这条已校验但尚未由 Compose 路由的一次性请求。
    scheduledTaskResultWorkflowId = runtimeState.scheduledTaskResultWorkflowId,
    scheduledTaskResultTaskId = runtimeState.scheduledTaskResultTaskId,
    scheduledTaskResultWorkflowRunId = runtimeState.scheduledTaskResultWorkflowRunId,
    scheduledTaskResultNavigationVersion = runtimeState.scheduledTaskResultNavigationVersion,
)
