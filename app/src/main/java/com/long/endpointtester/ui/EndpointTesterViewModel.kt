package com.longdev.endpointtester.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.endpointtester.model.ApiMode
import com.longdev.endpointtester.model.EndpointConfig
import com.longdev.endpointtester.model.ProviderProfile
import com.longdev.endpointtester.network.ApiFailure
import com.longdev.endpointtester.network.EndpointUrlBuilder
import com.longdev.endpointtester.network.FailureKind
import com.longdev.endpointtester.network.OpenAiCompatibleClient
import com.longdev.endpointtester.network.RequestMessage
import com.longdev.endpointtester.network.StreamDeltaUpdate
import com.longdev.endpointtester.storage.ConversationStore
import com.longdev.endpointtester.storage.SecureConfigStore
import com.longdev.endpointtester.storage.StoredConversation
import com.longdev.endpointtester.storage.StoredConversationMessage
import com.longdev.endpointtester.storage.StoredConversations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TesterUiState(
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
    val testingModel: Boolean = false,
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
    val footer: String? = null,
)

data class ConversationSession(
    val id: String,
    val title: String,
    val summary: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class OperationResult(
    val success: Boolean,
    val title: String,
    val message: String,
    val endpoint: String? = null,
    val latencyMs: Long? = null,
    val firstTokenLatencyMs: Long? = null,
)

class EndpointTesterViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = SecureConfigStore(application)
    private val conversationStore = ConversationStore(application)
    private val client = OpenAiCompatibleClient()

    var uiState by mutableStateOf(
        configStore.load()
            .toUiState()
            .withConversations(conversationStore.load()),
    )
        private set

    fun selectProfile(profileId: String) {
        val profile = uiState.profiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.fromProfile(profile, profileId)
        configStore.save(uiState.profiles, profileId)
    }

    fun updateModel(value: String) {
        if (value.isBlank()) return
        val allowed = uiState.enabledModels
        if (allowed.isNotEmpty() && value !in allowed) {
            showValidation("这个模型没有在管理页勾选")
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

    fun openNewConversation() {
        val current = uiState.conversations.firstOrNull { it.id == uiState.selectedConversationId }
        if (current != null && current.messages.isEmpty()) {
            uiState = uiState.copy(
                selectedConversationId = current.id,
                conversationTitle = current.title,
                conversationSummary = current.summary,
                chatMessages = emptyList(),
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
            result = null,
        )
        saveConversationSelection()
    }

    fun deleteCurrentConversation() {
        val currentId = uiState.selectedConversationId
        val remaining = uiState.conversations.filterNot { it.id == currentId }
        if (remaining.isEmpty()) {
            val now = System.currentTimeMillis()
            val conversation = ConversationSession(
                id = "conversation-$now",
                title = "新会话",
                summary = "",
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
        EndpointUrlBuilder.validate(baseUrl)?.let {
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
        EndpointUrlBuilder.validate(draft.baseUrl)?.let {
            showValidation(it)
            return
        }

        val config = EndpointConfig(
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
        EndpointUrlBuilder.validate(draft.baseUrl)?.let {
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
        configStore.save(profiles, id)
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
        configStore.save(profiles, nextId)
    }

    fun testModel() {
        val config = validatedConfig() ?: return
        if (uiState.prompt.isBlank()) {
            showValidation("请输入消息")
            return
        }
        val userMessage = uiState.prompt.trim()
        val userChatMessage = ChatMessage("user", userMessage, nowTimeText())
        val messagesWithUser = uiState.chatMessages + userChatMessage
        val summaryForRequest = summarizeOlderMessages(messagesWithUser)
        val requestMessages = buildRequestMessages(messagesWithUser, summaryForRequest)
        uiState = uiState.copy(
            testingModel = true,
            result = null,
            prompt = "",
        ).withUpdatedCurrentConversation(messagesWithUser, summaryForRequest)
        saveConversationSelection()
        viewModelScope.launch {
            runCatching {
                client.testModel(config, requestMessages) { update ->
                    withContext(Dispatchers.Main.immediate) {
                        updateStreamingAssistant(update)
                    }
                }
            }
                .onSuccess { test ->
                    val timingFooter = if (config.streamingEnabled) {
                        "首字 ${test.firstTokenLatencyMs?.toSecondsText() ?: "-"} · 耗时 ${test.latencyMs.toSecondsText()}"
                    } else {
                        "耗时 ${test.latencyMs.toSecondsText()}"
                    }
                    val finalMessages = uiState.chatMessages.upsertLastAssistant(
                        text = test.responseText,
                        footer = timingFooter,
                    )
                    uiState = uiState
                        .withUpdatedCurrentConversation(finalMessages, summarizeOlderMessages(finalMessages))
                        .copy(
                        testingModel = false,
                        result = null,
                    )
                    saveConversationSelection()
                    saveCurrentProfileSelection()
                }
                .onFailure { error ->
                    val failedMessages = uiState.chatMessages + ChatMessage(
                        role = "error",
                        text = error.toConversationErrorText(),
                        footer = "请求失败",
                    )
                    uiState = uiState
                        .withUpdatedCurrentConversation(failedMessages, summarizeOlderMessages(failedMessages))
                        .copy(
                        testingModel = false,
                        result = null,
                    )
                    saveConversationSelection()
                }
        }
    }

    private fun validatedConfig(): EndpointConfig? {
        EndpointUrlBuilder.validate(uiState.baseUrl)?.let {
            showValidation(it)
            return null
        }
        if (uiState.enabledModels.isEmpty()) {
            showValidation("请先在管理页勾选可测试模型")
            return null
        }
        if (uiState.model.isBlank()) {
            showValidation("请选择模型")
            return null
        }
        if (uiState.model !in uiState.enabledModels) {
            showValidation("这个模型没有在管理页勾选")
            return null
        }
        val profile = selectedProfile()
        return EndpointConfig(
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
        configStore.save(profiles, uiState.selectedProfileId)
    }

    private fun saveConversationSelection() {
        conversationStore.save(uiState.conversations.map { it.toStored() }, uiState.selectedConversationId)
    }

    private fun TesterUiState.withUpdatedCurrentConversation(
        messages: List<ChatMessage>,
        summary: String,
    ): TesterUiState {
        val now = System.currentTimeMillis()
        val currentId = selectedConversationId.ifBlank { "conversation-$now" }
        val current = conversations.firstOrNull { it.id == currentId }
        val title = current?.title
            ?.takeUnless { it == "新会话" && messages.any { message -> message.role == "user" } }
            ?: messages.firstUserTitle()
        val updatedConversation = ConversationSession(
            id = currentId,
            title = title,
            summary = summary,
            messages = messages,
            createdAt = current?.createdAt ?: now,
            updatedAt = now,
        )
        val updatedConversations = if (conversations.any { it.id == currentId }) {
            conversations.map { if (it.id == currentId) updatedConversation else it }
        } else {
            conversations + updatedConversation
        }.collapseDuplicateEmptyConversations(currentId)
        return copy(
            conversations = updatedConversations,
            selectedConversationId = currentId,
            conversationTitle = title,
            conversationSummary = summary,
            chatMessages = messages,
        )
    }

    private fun summarizeOlderMessages(messages: List<ChatMessage>): String {
        val contextMessages = messages.filter { it.role == "user" || it.role == "assistant" }
        if (contextMessages.size <= RECENT_CONTEXT_MESSAGE_LIMIT) return ""
        val olderMessages = contextMessages.dropLast(RECENT_CONTEXT_MESSAGE_LIMIT)
        // long: 长会话继续完整发送会快速耗尽上下文窗口，这里把较早轮次压成本地摘要，实际请求只保留摘要和最近对话。
        val transcript = olderMessages.joinToString("\n") { message ->
            val label = if (message.role == "assistant") "assistant" else "user"
            "$label: ${message.text.trim()}"
        }
        return transcript.takeLast(SUMMARY_MAX_CHARS).prependIndent("  ").let {
            "较早对话摘要（由本地记录压缩生成，用于延续上下文）：\n$it"
        }
    }

    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        summary: String,
    ): List<RequestMessage> {
        val requestMessages = mutableListOf<RequestMessage>()
        if (summary.isNotBlank()) {
            // long: 摘要作为 system 上下文放在最前面，让模型能参考早期信息，同时避免把全部历史反复塞进请求。
            requestMessages += RequestMessage(
                role = "system",
                content = summary,
            )
        }
        messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(RECENT_CONTEXT_MESSAGE_LIMIT)
            .forEach { message ->
                requestMessages += RequestMessage(
                    role = message.role,
                    content = message.text,
                )
            }
        return requestMessages
    }

    private suspend fun syncStoredProfile(
        profile: ProviderProfile,
        showPopup: Boolean,
        keepBatchResult: Boolean,
    ) {
        EndpointUrlBuilder.validate(profile.baseUrl)?.let { message ->
            val result = OperationResult(false, "同步失败", message)
            applySyncFailure(profile.id, result, showPopup, keepBatchResult)
            return
        }

        uiState = uiState.copy(
            syncingProfileIds = uiState.syncingProfileIds + profile.id,
            result = null,
        )
        val config = EndpointConfig(
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

    private fun updateAndSaveSelectedProfile(block: TesterUiState.() -> TesterUiState) {
        uiState = uiState.block()
        saveCurrentProfileSelection()
    }

    private inline fun updateDraft(block: ProviderEditDraft.() -> ProviderEditDraft) {
        uiState.manageDraft?.let {
            uiState = uiState.copy(manageDraft = it.block(), result = null)
        }
    }

    private fun showFailure(error: Throwable, loadingModels: Boolean? = null, testingModel: Boolean? = null) {
        val failure = error as? ApiFailure
        val draft = uiState.manageDraft
        uiState = uiState.copy(
            loadingModels = loadingModels ?: uiState.loadingModels,
            testingModel = testingModel ?: uiState.testingModel,
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

    private fun Long.toSecondsText(): String = String.format(Locale.US, "%.2f s", this / 1000.0)

    private fun nowTimeText(): String = SimpleDateFormat(FULL_TIME_PATTERN, Locale.getDefault()).format(Date())

    private fun nowSyncTimeText(): String = SimpleDateFormat(FULL_TIME_PATTERN, Locale.getDefault()).format(Date())

    private fun Throwable.toConversationErrorText(): String {
        val failure = this as? ApiFailure
        val title = failure?.kind?.title ?: FailureKind.UNKNOWN.title
        val detail = message ?: "未知错误"
        return "$title\n$detail"
    }

    private fun updateStreamingAssistant(update: StreamDeltaUpdate) {
        val footer = "首字 ${update.firstTokenLatencyMs.toSecondsText()} · 接收中"
        val updatedMessages = uiState.chatMessages.upsertLastAssistant(
            text = update.accumulatedText,
            footer = footer,
        )
        // long: 流式返回期间会持续刷新对话区，当前会话的内存快照也同步更新，避免切换会话或失败收尾时拿到旧消息。
        uiState = uiState
            .withUpdatedCurrentConversation(updatedMessages, summarizeOlderMessages(updatedMessages))
            .copy(result = null)
    }

    private fun List<ChatMessage>.upsertLastAssistant(text: String, footer: String?): List<ChatMessage> {
        val last = lastOrNull()
        return if (last?.role == "assistant") {
            dropLast(1) + last.copy(text = text, footer = footer)
        } else {
            this + ChatMessage("assistant", text, footer)
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

    private fun TesterUiState.fromProfile(profile: ProviderProfile, selectedId: String) = copy(
        selectedProfileId = selectedId,
        profileName = profile.name,
        baseUrl = profile.baseUrl,
        apiKey = profile.apiKey,
        model = profile.model.takeIf { it in profile.enabledModels } ?: profile.enabledModels.firstOrNull().orEmpty(),
        availableModels = profile.availableModels,
        enabledModels = profile.enabledModels,
        result = result,
    )

    private fun com.longdev.endpointtester.storage.StoredProfiles.toUiState(): TesterUiState {
        val safeProfiles = profiles.ifEmpty { listOf(ProviderProfile.blank()) }
        val selected = safeProfiles.firstOrNull { it.id == selectedProfileId } ?: safeProfiles.first()
        return TesterUiState(
            profiles = safeProfiles,
            selectedProfileId = selected.id,
        ).fromProfile(selected, selected.id)
    }

    private fun TesterUiState.withConversations(stored: StoredConversations): TesterUiState {
        val conversations = stored.conversations.map { it.toSession() }
            .collapseDuplicateEmptyConversations(stored.selectedConversationId)
            .ifEmpty {
                val now = System.currentTimeMillis()
                listOf(
                    ConversationSession(
                        id = "conversation-$now",
                        title = "新会话",
                        summary = "",
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
        messages = messages.map { it.toChatMessage() },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun StoredConversationMessage.toChatMessage() = ChatMessage(
        role = role,
        text = text,
        footer = footer,
    )

    private fun ConversationSession.toStored() = StoredConversation(
        id = id,
        title = title,
        summary = summary,
        messages = messages.map { it.toStored() },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ChatMessage.toStored() = StoredConversationMessage(
        role = role,
        text = text,
        footer = footer,
    )

    companion object {
        private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private const val RECENT_CONTEXT_MESSAGE_LIMIT = 16
        private const val SUMMARY_MAX_CHARS = 4_000
    }
}
