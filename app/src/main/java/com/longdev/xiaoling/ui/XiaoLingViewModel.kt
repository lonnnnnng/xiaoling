package com.longdev.xiaoling.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.xiaoling.agent.AgentCommand
import com.longdev.xiaoling.agent.AgentRunUseCase
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.ApprovalDecision
import com.longdev.xiaoling.agent.ApprovalGate
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.ProviderApiUrlBuilder
import com.longdev.xiaoling.network.FailureKind
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.network.StreamDeltaUpdate
import com.longdev.xiaoling.prompt.PromptDefaults
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.prompt.PromptSettings
import com.longdev.xiaoling.storage.ConversationRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.SecureConfigStore
import com.longdev.xiaoling.storage.StoredConversation
import com.longdev.xiaoling.storage.StoredConversationMessage
import com.longdev.xiaoling.storage.StoredMessageMeta
import com.longdev.xiaoling.storage.StoredConversations
import com.longdev.xiaoling.storage.StoredProfiles
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
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

data class XiaoLingUiState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val promptSettings: PromptSettings = PromptSettings(),
    val profiles: List<ProviderProfile> = emptyList(),
    val selectedProfileId: String = "",
    val profileName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val prompt: String = SecureConfigStore.DEFAULT_PROMPT,
    val availableModels: List<String> = emptyList(),
    val enabledModels: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val sendingMessage: Boolean = false,
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val conversations: List<ConversationSession> = emptyList(),
    val selectedConversationId: String = "",
    val conversationTitle: String = "",
    val conversationSummary: String = "",
    val manageDraft: ProviderEditDraft? = null,
    val syncingProfileIds: Set<String> = emptySet(),
    val syncingAllProfiles: Boolean = false,
    val batchSyncResults: Map<String, String> = emptyMap(),
    val activeAgentRun: AgentRunSnapshot? = null,
    val pendingAgentApproval: AgentApprovalUiState? = null,
    val loadingAgentRunHistory: Boolean = false,
    val agentRunHistory: List<AgentRunDetailRecord> = emptyList(),
    val selectedAgentRunId: String? = null,
    val agentRunHistoryError: String? = null,
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

data class ChatMessage(
    val role: String,
    val text: String,
    val id: String = newChatMessageId(),
    val createdAt: Long = System.currentTimeMillis(),
    val meta: MessageMeta? = null,
)

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

private data class PreparedRequestContext(
    val requestMessages: List<RequestMessage>,
    val summary: String,
    val summaryUntilMessageId: String?,
    val summaryUpdatedAt: Long?,
    val summaryModel: String?,
) {
    companion object {
        fun fromConversation(
            conversation: ConversationSession?,
        ): PreparedRequestContext {
            return PreparedRequestContext(
                requestMessages = emptyList(),
                summary = conversation?.summary.orEmpty(),
                summaryUntilMessageId = conversation?.summaryUntilMessageId,
                summaryUpdatedAt = conversation?.summaryUpdatedAt,
                summaryModel = conversation?.summaryModel,
            )
        }
    }
}

class XiaoLingViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ProviderRepository(application)
    private val conversationStore = ConversationRepository(application)
    private val uiPreferenceStore = UiPreferenceStore(application)
    private val client = OpenAiCompatibleClient()
    private val agentRunUseCase = AgentRunUseCase(application, client)
    private val agentRunRepository = RoomAgentRunRepository(application)
    private var streamingThrottleJob: Job? = null
    private var pendingStreamingUpdate: StreamDeltaUpdate? = null
    private var pendingApprovalDecision: CompletableDeferred<ApprovalDecision>? = null
    private val activeAgentRunsByConversation = mutableMapOf<String, AgentRunSnapshot>()
    private val pendingAgentApprovalsByConversation = mutableMapOf<String, AgentApprovalUiState>()
    private var sendMessageJob: Job? = null
    private var saveProfilesJob: Job? = null
    private var saveConversationsJob: Job? = null

    var uiState by mutableStateOf(
        initialUiState(
            themeMode = uiPreferenceStore.loadThemeMode(),
            promptSettings = uiPreferenceStore.loadPromptSettings(),
        ),
    )
        private set

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // long: 进程重建后不恢复已经丢失的协程和网络请求；先把上次遗留的中间态 Run 收敛成可审计终态，再加载会话，避免 UI 展示不可继续的假活跃任务。
                agentRunRepository.closeInterruptedRuns()
            }
            val storedProfiles = configStore.load()
            val storedConversations = conversationStore.load()
            // long: Room/Keystore 读取放在协程里执行，首屏先用安全的空白状态，避免应用启动阶段因为解密或数据库迁移阻塞主线程。
            uiState = storedProfiles
                .toUiState()
                .withConversations(storedConversations)
                .copy(
                    themeMode = uiState.themeMode,
                    promptSettings = uiState.promptSettings,
                    result = uiState.result,
                )
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

    fun updateDraftName(value: String) = updateDraft { copy(name = value) }
    fun updateDraftBaseUrl(value: String) = updateDraft { copy(baseUrl = value) }
    fun updateDraftApiKey(value: String) = updateDraft { copy(apiKey = value) }
    fun updateApiMode(value: ApiMode) {
        uiState = uiState.copy(apiMode = value, result = null)
    }
    fun updateStreamingEnabled(value: Boolean) {
        uiState = uiState.copy(streamingEnabled = value, result = null)
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
                    agentRunHistoryError = error.message ?: "无法读取 Agent 运行记录",
                )
            }
        }
    }

    fun selectAgentRun(runId: String) {
        if (uiState.agentRunHistory.none { it.snapshot.run.id == runId }) return
        uiState = uiState.copy(selectedAgentRunId = runId)
    }

    fun approvePendingAgentTool() {
        val pending = uiState.pendingAgentApproval ?: return
        completePendingAgentApproval(
            pending = pending,
            status = ApprovalRequestStatus.APPROVED,
            approved = true,
            reason = "用户已批准：${pending.toolName}",
        )
    }

    fun rejectPendingAgentTool() {
        val pending = uiState.pendingAgentApproval ?: return
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

    fun openNewConversation() {
        val current = uiState.conversations.firstOrNull { it.id == uiState.selectedConversationId }
        if (current != null && current.messages.isEmpty()) {
            uiState = uiState.copy(
                selectedConversationId = current.id,
                conversationTitle = current.title,
                conversationSummary = current.summary,
                chatMessages = emptyList(),
                activeAgentRun = activeAgentRunsByConversation[current.id],
                pendingAgentApproval = pendingAgentApprovalsByConversation[current.id],
                result = null,
            )
            saveConversationSelection()
            return
        }
        val reusableEmptyConversation = uiState.conversations
            .filter { it.messages.isEmpty() }
            .maxByOrNull { it.updatedAt }
        if (reusableEmptyConversation != null) {
            uiState = uiState.copy(
                conversations = uiState.conversations
                    .collapseDuplicateEmptyConversations(reusableEmptyConversation.id),
                selectedConversationId = reusableEmptyConversation.id,
                conversationTitle = reusableEmptyConversation.title,
                conversationSummary = reusableEmptyConversation.summary,
                chatMessages = emptyList(),
                activeAgentRun = activeAgentRunsByConversation[reusableEmptyConversation.id],
                pendingAgentApproval = pendingAgentApprovalsByConversation[reusableEmptyConversation.id],
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
            conversations = uiState.conversations + conversation,
            selectedConversationId = conversation.id,
            conversationTitle = conversation.title,
            conversationSummary = "",
            chatMessages = emptyList(),
            activeAgentRun = null,
            pendingAgentApproval = null,
            result = null,
        )
        saveConversationSelection()
    }

    fun selectConversation(conversationId: String) {
        val conversation = uiState.conversations.firstOrNull { it.id == conversationId } ?: return
        uiState = uiState.copy(
            selectedConversationId = conversation.id,
            conversationTitle = conversation.title,
            conversationSummary = conversation.summary,
            chatMessages = conversation.messages,
            activeAgentRun = activeAgentRunsByConversation[conversation.id],
            pendingAgentApproval = pendingAgentApprovalsByConversation[conversation.id],
            result = null,
        )
        saveConversationSelection()
    }

    fun deleteCurrentConversation() {
        val currentId = uiState.selectedConversationId
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
                result = null,
            )
            saveConversationSelection()
            return
        }

        val next = remaining.maxBy { it.updatedAt }
        uiState = uiState.copy(
            conversations = remaining,
            selectedConversationId = next.id,
            conversationTitle = next.title,
            conversationSummary = next.summary,
            chatMessages = next.messages,
            activeAgentRun = activeAgentRunsByConversation[next.id],
            pendingAgentApproval = pendingAgentApprovalsByConversation[next.id],
            result = OperationResult(true, "已删除", "当前会话已删除"),
        )
        saveConversationSelection()
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
    }

    fun deleteProvider(profileId: String) {
        if (uiState.profiles.size <= 1) {
            showValidation("至少保留一个模型提供方")
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
        if (uiState.prompt.isBlank()) {
            showValidation("请输入消息")
            return
        }
        val userMessage = uiState.prompt.trim()
        if (AgentCommand.matches(userMessage)) {
            val config = validatedConfig() ?: return
            sendAgentRun(userMessage, config)
            return
        }
        val config = validatedConfig() ?: return
        val profileSnapshot = selectedProfile()
        val currentConversation = uiState.conversations.firstOrNull { it.id == uiState.selectedConversationId }
        clearAgentStateForConversation(uiState.selectedConversationId)
        val userChatMessage = ChatMessage(
            role = "user",
            text = userMessage,
            createdAt = System.currentTimeMillis(),
        )
        val messagesWithUser = uiState.chatMessages + userChatMessage
        var preparedContext = PreparedRequestContext.fromConversation(currentConversation)
        uiState = uiState.copy(
            sendingMessage = true,
            result = null,
            prompt = "",
            activeAgentRun = null,
            pendingAgentApproval = null,
        ).withUpdatedCurrentConversation(
            messages = messagesWithUser,
            summary = preparedContext.summary,
            summaryUntilMessageId = preparedContext.summaryUntilMessageId,
            summaryUpdatedAt = preparedContext.summaryUpdatedAt,
            summaryModel = preparedContext.summaryModel,
        )
        val baseMeta = config.toBaseMessageMeta(profileSnapshot)
        sendMessageJob = viewModelScope.launch {
            try {
                preparedContext = prepareRequestContext(config, messagesWithUser, currentConversation)
                withContext(Dispatchers.Main.immediate) {
                    uiState = uiState
                        .withUpdatedCurrentConversation(
                            messages = messagesWithUser,
                            summary = preparedContext.summary,
                            summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                            summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                            summaryModel = preparedContext.summaryModel,
                        )
                        .copy(result = null)
                }
                client.sendMessage(config, preparedContext.requestMessages) { update ->
                    withContext(Dispatchers.Main.immediate) {
                        scheduleStreamingAssistant(update, baseMeta)
                    }
                }
                    .also { response ->
                        flushStreamingAssistant(baseMeta)
                        val finalMessages = uiState.chatMessages.upsertLastAssistant(
                            text = response.responseText,
                            meta = baseMeta.copy(
                                requestUrl = response.requestUrl,
                                firstTokenLatencyMs = response.firstTokenLatencyMs,
                                latencyMs = response.latencyMs,
                            ),
                        )
                        uiState = uiState
                            .withUpdatedCurrentConversation(
                                messages = finalMessages,
                                summary = preparedContext.summary,
                                summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                                summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                                summaryModel = preparedContext.summaryModel,
                            )
                            .copy(
                                sendingMessage = false,
                                result = null,
                            )
                        saveConversationSelection()
                        saveCurrentProfileSelection()
                    }
            } catch (error: CancellationException) {
                flushStreamingAssistant(baseMeta)
                val stoppedMessages = uiState.chatMessages.withCancelledGeneration(baseMeta)
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = stoppedMessages,
                        summary = preparedContext.summary,
                        summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                        summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                        summaryModel = preparedContext.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                saveConversationSelection()
            } catch (error: Throwable) {
                flushStreamingAssistant(baseMeta)
                val failure = error as? ApiFailure
                val failedMessages = uiState.chatMessages + ChatMessage(
                    role = "error",
                    text = error.toConversationErrorText(),
                    createdAt = System.currentTimeMillis(),
                    meta = baseMeta.copy(
                        errorKind = failure?.kind?.title ?: FailureKind.UNKNOWN.title,
                        errorMessage = error.message ?: "未知错误",
                    ),
                )
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = failedMessages,
                        summary = preparedContext.summary,
                        summaryUntilMessageId = preparedContext.summaryUntilMessageId,
                        summaryUpdatedAt = preparedContext.summaryUpdatedAt,
                        summaryModel = preparedContext.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                saveConversationSelection()
            } finally {
                sendMessageJob = null
            }
        }
    }

    private fun sendAgentRun(userMessage: String, config: ProviderRequestConfig) {
        val conversationId = uiState.selectedConversationId.ifBlank { "conversation-${System.currentTimeMillis()}" }
        val currentConversation = uiState.conversations.firstOrNull { it.id == conversationId }
        clearAgentStateForConversation(conversationId)
        val userChatMessage = ChatMessage(
            role = "user",
            text = userMessage,
            createdAt = System.currentTimeMillis(),
        )
        val messagesWithUser = uiState.chatMessages + userChatMessage
        val preparedContext = PreparedRequestContext.fromConversation(currentConversation)
        uiState = uiState.copy(
            sendingMessage = true,
            result = null,
            prompt = "",
        ).withUpdatedConversation(
            conversationId = conversationId,
            messages = messagesWithUser,
            summary = preparedContext.summary,
            summaryUntilMessageId = preparedContext.summaryUntilMessageId,
            summaryUpdatedAt = preparedContext.summaryUpdatedAt,
            summaryModel = preparedContext.summaryModel,
        )
        val goal = AgentCommand.goal(userMessage)
        sendMessageJob = viewModelScope.launch {
            try {
                val approvalGate = interactiveAgentApprovalGate(conversationId)
                val summary = agentRunUseCase.run(
                    conversationId = conversationId,
                    userMessageId = userChatMessage.id,
                    goal = goal,
                    config = config,
                    summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(uiState.promptSettings),
                    approvalGate = approvalGate,
                    onSnapshot = ::publishAgentRunSnapshot,
                )
                val finalMessages = messagesWithUser + ChatMessage(
                    role = "assistant",
                    text = summary.responseText,
                    createdAt = System.currentTimeMillis(),
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
                clearPendingApprovalForConversation(conversationId)
                saveConversationSelection()
            } catch (error: CancellationException) {
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
            }
        }
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
        if (snapshot.run.conversationId == uiState.selectedConversationId) {
            uiState = uiState.copy(activeAgentRun = snapshot)
        }
    }

    private fun rememberPendingApproval(approval: AgentApprovalUiState) {
        pendingAgentApprovalsByConversation[approval.conversationId] = approval
        if (approval.conversationId == uiState.selectedConversationId) {
            uiState = uiState.copy(pendingAgentApproval = approval)
        }
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
            apiMode = uiState.apiMode,
            streamingEnabled = uiState.streamingEnabled,
            maxTokens = ProviderProfile.FIXED_MAX_TOKENS,
        )
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

    private fun saveCurrentProfileSelection() {
        val profiles = uiState.profiles.map {
            if (it.id == uiState.selectedProfileId) it.copy(model = uiState.model) else it
        }
        uiState = uiState.copy(profiles = profiles)
        saveProfilesSnapshot(profiles, uiState.selectedProfileId)
    }

    private fun saveConversationSelection() {
        saveConversationsSnapshot(uiState.conversations.map { it.toStored() }, uiState.selectedConversationId)
    }

    private fun saveProfilesSnapshot(profiles: List<ProviderProfile>, selectedProfileId: String) {
        saveProfilesJob?.cancel()
        saveProfilesJob = viewModelScope.launch {
            // long: Provider 保存包含 Keystore 加密和 Room 写入，必须放到后台；只保留最后一次快照，避免快速切换模型时旧写入覆盖新选择。
            configStore.save(profiles, selectedProfileId)
        }
    }

    private fun saveConversationsSnapshot(conversations: List<StoredConversation>, selectedConversationId: String) {
        saveConversationsJob?.cancel()
        saveConversationsJob = viewModelScope.launch {
            // long: 会话内容可能越来越长，保存全量快照不能卡 UI；取消前一次后台保存可以让最终落盘状态跟当前界面一致。
            conversationStore.save(conversations, selectedConversationId)
        }
    }

    private inline fun updatePromptSettings(block: PromptSettings.() -> PromptSettings) {
        val settings = uiState.promptSettings.block()
        uiState = uiState.copy(promptSettings = settings, result = null)
        uiPreferenceStore.savePromptSettings(settings)
    }

    private fun XiaoLingUiState.withUpdatedCurrentConversation(
        messages: List<ChatMessage>,
        summary: String,
        summaryUntilMessageId: String? = conversations.firstOrNull { it.id == selectedConversationId }?.summaryUntilMessageId,
        summaryUpdatedAt: Long? = conversations.firstOrNull { it.id == selectedConversationId }?.summaryUpdatedAt,
        summaryModel: String? = conversations.firstOrNull { it.id == selectedConversationId }?.summaryModel,
    ): XiaoLingUiState {
        return withUpdatedConversation(
            conversationId = selectedConversationId,
            messages = messages,
            summary = summary,
            summaryUntilMessageId = summaryUntilMessageId,
            summaryUpdatedAt = summaryUpdatedAt,
            summaryModel = summaryModel,
        )
    }

    private fun XiaoLingUiState.withUpdatedConversation(
        conversationId: String,
        messages: List<ChatMessage>,
        summary: String,
        summaryUntilMessageId: String? = conversations.firstOrNull { it.id == conversationId }?.summaryUntilMessageId,
        summaryUpdatedAt: Long? = conversations.firstOrNull { it.id == conversationId }?.summaryUpdatedAt,
        summaryModel: String? = conversations.firstOrNull { it.id == conversationId }?.summaryModel,
    ): XiaoLingUiState {
        val now = System.currentTimeMillis()
        val currentId = conversationId.ifBlank { "conversation-$now" }
        val current = conversations.firstOrNull { it.id == currentId }
        val title = current?.title
            ?.takeUnless { it == "新会话" && messages.any { message -> message.role == "user" } }
            ?: messages.firstUserTitle()
        val updatedConversation = ConversationSession(
            id = currentId,
            title = title,
            summary = summary,
            summaryUntilMessageId = summaryUntilMessageId,
            summaryUpdatedAt = summaryUpdatedAt,
            summaryModel = summaryModel,
            messages = messages,
            createdAt = current?.createdAt ?: now,
            updatedAt = now,
        )
        val updatedConversations = if (conversations.any { it.id == currentId }) {
            conversations.map { if (it.id == currentId) updatedConversation else it }
        } else {
            conversations + updatedConversation
        }.collapseDuplicateEmptyConversations(selectedConversationId.ifBlank { currentId })
        // long: Agent Run 可能在用户切到其他会话后才完成；落库和会话消息必须回写发起 Run 的会话，不能污染用户当前正在看的会话。
        if (currentId != selectedConversationId) {
            return copy(conversations = updatedConversations)
        }
        return copy(
            conversations = updatedConversations,
            selectedConversationId = currentId,
            conversationTitle = title,
            conversationSummary = summary,
            chatMessages = messages,
        )
    }

    private suspend fun prepareRequestContext(
        config: ProviderRequestConfig,
        messages: List<ChatMessage>,
        conversation: ConversationSession?,
    ): PreparedRequestContext {
        val contextMessages = messages.filter { it.role == "user" || it.role == "assistant" }
        if (contextMessages.size <= RECENT_CONTEXT_MESSAGE_LIMIT && conversation?.summary.isNullOrBlank()) {
            return PreparedRequestContext(
                requestMessages = buildRequestMessages(contextMessages, summary = ""),
                summary = "",
                summaryUntilMessageId = null,
                summaryUpdatedAt = null,
                summaryModel = null,
            )
        }

        val olderMessages = contextMessages.dropLast(RECENT_CONTEXT_MESSAGE_LIMIT)
        val targetSummaryMessage = olderMessages.lastOrNull()
        val existingSummary = conversation?.summary.orEmpty()
        if (targetSummaryMessage == null) {
            return PreparedRequestContext(
                requestMessages = buildRequestMessages(contextMessages, existingSummary),
                summary = existingSummary,
                summaryUntilMessageId = conversation?.summaryUntilMessageId,
                summaryUpdatedAt = conversation?.summaryUpdatedAt,
                summaryModel = conversation?.summaryModel,
            )
        }

        if (existingSummary.isNotBlank() && conversation?.summaryUntilMessageId == targetSummaryMessage.id) {
            return PreparedRequestContext(
                requestMessages = buildRequestMessages(contextMessages, existingSummary),
                summary = existingSummary,
                summaryUntilMessageId = conversation.summaryUntilMessageId,
                summaryUpdatedAt = conversation.summaryUpdatedAt,
                summaryModel = conversation.summaryModel,
            )
        }

        val messagesToCompress = messagesNeedingCompression(
            contextMessages = contextMessages,
            previousSummaryUntilMessageId = conversation?.summaryUntilMessageId,
            targetSummaryMessageId = targetSummaryMessage.id,
        )
        // long: 长会话压缩只处理“上次摘要边界之后、最近窗口之前”的旧消息，避免每轮都把完整历史重新塞给摘要模型。
        val summary = runCatching {
            generateConversationSummary(
                config = config,
                existingSummary = existingSummary,
                messagesToCompress = messagesToCompress,
            )
        }.getOrElse {
            localFallbackSummary(existingSummary, messagesToCompress)
        }
        val now = System.currentTimeMillis()
        return PreparedRequestContext(
            requestMessages = buildRequestMessages(contextMessages, summary),
            summary = summary,
            summaryUntilMessageId = targetSummaryMessage.id,
            summaryUpdatedAt = now,
            summaryModel = config.model.trim(),
        )
    }

    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        summary: String,
    ): List<RequestMessage> {
        val requestMessages = mutableListOf(
            RequestMessage(
                role = "system",
                content = PromptPolicy.chatSystemPrompt(uiState.promptSettings),
            ),
        )
        if (summary.isNotBlank()) {
            // long: 摘要作为 system 上下文放在最前面，让模型能参考早期信息，同时避免把全部历史反复塞进请求。
            requestMessages += RequestMessage(
                role = "system",
                content = "以下是较早对话的持续摘要，请在回答当前问题时一并参考：\n$summary",
            )
        }
        val recentMessages = if (summary.isBlank() && messages.size <= RECENT_CONTEXT_MESSAGE_LIMIT) {
            messages
        } else {
            messages.takeLast(RECENT_CONTEXT_MESSAGE_LIMIT)
        }
        recentMessages
            .forEach { message ->
                requestMessages += RequestMessage(
                    role = message.role,
                    content = if (message.role == "assistant") {
                        // long: 普通 assistant 历史只用于语言上下文，显式降级其证据身份，避免模型把旧回复中的工具幻觉当成本轮已执行事实。
                        PromptPolicy.ordinaryAssistantHistory(message.text)
                    } else {
                        message.text
                    },
                )
            }
        return requestMessages
    }

    private fun messagesNeedingCompression(
        contextMessages: List<ChatMessage>,
        previousSummaryUntilMessageId: String?,
        targetSummaryMessageId: String,
    ): List<ChatMessage> {
        val targetIndex = contextMessages.indexOfFirst { it.id == targetSummaryMessageId }
        if (targetIndex < 0) return emptyList()
        val previousIndex = previousSummaryUntilMessageId
            ?.let { id -> contextMessages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: -1
        return contextMessages.subList(previousIndex + 1, targetIndex + 1)
    }

    private suspend fun generateConversationSummary(
        config: ProviderRequestConfig,
        existingSummary: String,
        messagesToCompress: List<ChatMessage>,
    ): String {
        if (messagesToCompress.isEmpty()) return existingSummary
        val transcript = messagesToCompress.toSummaryTranscript()
        val prompt = buildString {
            appendLine("已有摘要：")
            appendLine(existingSummary.ifBlank { "无" })
            appendLine()
            appendLine("新增对话：")
            appendLine(transcript)
        }
        val summaryConfig = config.copy(streamingEnabled = false)
        val result = client.sendMessage(
            config = summaryConfig,
            messages = listOf(
                RequestMessage(
                    role = "system",
                    content = PromptPolicy.summarySystemPrompt(uiState.promptSettings),
                ),
                RequestMessage(role = "user", content = prompt),
            ),
        )
        return result.responseText.trim().take(SUMMARY_MAX_CHARS)
    }

    private fun localFallbackSummary(
        existingSummary: String,
        messagesToCompress: List<ChatMessage>,
    ): String {
        val transcript = messagesToCompress.toSummaryTranscript()
        return buildString {
            if (existingSummary.isNotBlank()) {
                appendLine(existingSummary.trim())
                appendLine()
            }
            appendLine("以下内容由本地记录压缩生成：")
            append(transcript.takeLast(SUMMARY_MAX_CHARS))
        }.trim().takeLast(SUMMARY_MAX_CHARS)
    }

    private fun List<ChatMessage>.toSummaryTranscript(): String {
        return joinToString("\n") { message ->
            val label = if (message.role == "assistant") {
                "assistant（普通对话回复，不代表工具或记忆操作已经执行）"
            } else {
                "user"
            }
            "$label: ${message.text.trim()}"
        }
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

    private fun List<ChatMessage>.upsertLastAssistant(text: String, meta: MessageMeta?): List<ChatMessage> {
        val last = lastOrNull()
        return if (last?.role == "assistant") {
            dropLast(1) + last.copy(text = text, meta = meta)
        } else {
            this + ChatMessage(
                role = "assistant",
                text = text,
                createdAt = System.currentTimeMillis(),
                meta = meta,
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

    private fun List<ChatMessage>.firstUserTitle(): String {
        return firstOrNull { it.role == "user" }
            ?.text
            ?.trim()
            ?.take(18)
            ?.ifBlank { null }
            ?: "新会话"
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

    private fun initialUiState(themeMode: AppThemeMode, promptSettings: PromptSettings): XiaoLingUiState {
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
            .copy(themeMode = themeMode, promptSettings = promptSettings)
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

    private fun List<ConversationSession>.collapseDuplicateEmptyConversations(preferredId: String): List<ConversationSession> {
        val realConversations = filter { it.messages.isNotEmpty() }
        val emptyConversations = filter { it.messages.isEmpty() }
        val keptEmptyConversation = emptyConversations
            .firstOrNull { it.id == preferredId }
            ?: emptyConversations.maxByOrNull { it.updatedAt }
        // long: 空白会话只是“准备输入”的占位，不承载业务记录；只保留一个，避免会话列表出现多个不可区分的新会话。
        return if (keptEmptyConversation == null) {
            realConversations
        } else {
            realConversations + keptEmptyConversation
        }
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

    private fun StoredConversationMessage.toChatMessage() = ChatMessage(
        id = id,
        role = role,
        text = text,
        createdAt = createdAt,
        meta = meta?.toMessageMeta(),
    )

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
        meta = meta?.toStoredMeta(),
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
        private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private const val RECENT_CONTEXT_MESSAGE_LIMIT = 16
        private const val SUMMARY_MAX_CHARS = 4_000
        private const val STREAMING_UI_THROTTLE_MS = 30L
        private const val AGENT_RUN_HISTORY_LIMIT = 50
    }
}
