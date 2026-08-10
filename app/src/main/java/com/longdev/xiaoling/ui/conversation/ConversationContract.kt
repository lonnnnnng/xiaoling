package com.longdev.xiaoling.ui.conversation

import com.longdev.xiaoling.agent.AgentCommand
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.share.SharedDraftPayload
import com.longdev.xiaoling.ui.AgentApprovalUiState
import com.longdev.xiaoling.ui.ChatMessage
import com.longdev.xiaoling.ui.ConversationSession
import com.longdev.xiaoling.ui.PersonalTaskCompletionUiState
import com.longdev.xiaoling.ui.PersonalTaskFailureUiState
import com.longdev.xiaoling.ui.PersonalTaskOperationUiPhase
import com.longdev.xiaoling.ui.knowledgeReferencesForDisplay

internal interface ConversationActions {
    fun selectConversation(conversationId: String)

    fun openNewConversation()

    fun deleteCurrentConversation()

    fun updateThemeMode(value: AppThemeMode)

    fun selectProvider(profileId: String)

    fun updateModel(value: String)

    fun updateResponsesEnabled(value: Boolean)

    fun updateStreamingEnabled(value: Boolean)

    fun updateReasoningSummaryEnabled(value: Boolean)

    fun updateAgentMemoryRecallEnabled(value: Boolean)

    fun selectAgentProfile(profileId: String)

    fun updatePrompt(value: String)

    fun updatePersonalTaskMode(enabled: Boolean)

    fun removePendingImage()

    fun removePendingDocument()

    fun openPendingSharedDraft()

    fun discardPendingSharedDraft()

    fun createAgentNoteDraftFromSharedText()

    fun createAgentMemoryDraftFromSharedText()

    fun createAgentCalendarEventDraftFromSharedText()

    fun createAgentAllDayCalendarEventDraftFromSharedText()

    fun createPersonalTaskDraftFromSharedText()

    fun sendMessage()

    fun stopGenerating()

    fun confirmPendingPersonalTaskPlan()

    fun cancelPendingPersonalTaskPlan()

    fun openWorkflowManagement(workflowId: String? = null)

    fun openInspectedTask(taskName: String)

    fun openConversation(conversationId: String)

    fun openCalendarEvent(eventId: String)

    fun openLocalNote(noteId: String)

    fun openMemory(memoryId: String)

    fun approvePendingAgentTool()

    fun rejectPendingAgentTool()

    fun refreshKnowledgeReferenceStatuses(references: List<KnowledgeReference>)

    fun requestImageAttachment()

    fun requestDocumentAttachment()

    fun requestVoiceInput()

    fun openKnowledgeDocument(documentId: String)
}

internal data class ConversationUiState(
    val conversationId: String = "",
    val header: ConversationHeaderUiState = ConversationHeaderUiState(),
    val provider: ConversationProviderUiState = ConversationProviderUiState(),
    val messages: ConversationMessagesUiState = ConversationMessagesUiState(),
    val composer: ConversationComposerUiState = ConversationComposerUiState(),
)

internal data class ConversationHeaderUiState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val conversations: List<ConversationSession> = emptyList(),
    val title: String = "",
)

internal data class ConversationProviderUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val selectedProfileName: String = "",
    val hasEnabledModels: Boolean = false,
)

internal data class ConversationMessagesUiState(
    val chatMessages: List<ChatMessage> = emptyList(),
    val knowledgeReferenceStatuses: Map<KnowledgeReference, KnowledgeReferenceStatus> = emptyMap(),
    val failedKnowledgeReferenceStatuses: Set<KnowledgeReference> = emptySet(),
    val answerabilityNotices: Map<String, KnowledgeAnswerabilityUserNotice> = emptyMap(),
    val displayedKnowledgeReferences: List<KnowledgeReference> = emptyList(),
    val activeAgentRun: AgentRunSnapshot? = null,
    val pendingAgentApproval: AgentApprovalUiState? = null,
    val waitingForModelStart: Boolean = false,
)

internal data class ConversationComposerUiState(
    val prompt: String = "",
    val sendingMessage: Boolean = false,
    val model: String = "",
    val enabledModels: List<String> = emptyList(),
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val reasoningSummaryEnabled: Boolean = false,
    val agentMemoryRecallEnabled: Boolean = true,
    val agentProfiles: List<AgentProfileRecord> = emptyList(),
    val selectedAgentProfileId: String = "",
    val pendingImage: ImageAttachment? = null,
    val pendingDocument: DocumentAttachment? = null,
    val pendingSharedDraft: SharedDraftPayload? = null,
    val sharedDraftImported: Boolean = false,
    val attachingImage: Boolean = false,
    val attachingDocument: Boolean = false,
    val loadingConversationMessages: Boolean = false,
    val agentCommand: Boolean = false,
    val personalTaskMode: Boolean = false,
    val awaitingPersonalTaskPlanConfirmation: Boolean = false,
    val personalTaskOperationPhase: PersonalTaskOperationUiPhase? = null,
    val personalTaskFailure: PersonalTaskFailureUiState? = null,
    val personalTaskCompletion: PersonalTaskCompletionUiState? = null,
    val canSend: Boolean = false,
    val controlsEnabled: Boolean = false,
    val attachmentEnabled: Boolean = false,
    val voiceInputEnabled: Boolean = false,
    val memoryOptionEnabled: Boolean = false,
)

internal object ConversationProjection {
    @Suppress("LongParameterList")
    fun project(
        themeMode: AppThemeMode = AppThemeMode.SYSTEM,
        profiles: List<ProviderProfile> = emptyList(),
        profileName: String = "",
        model: String = "",
        enabledModels: List<String> = emptyList(),
        prompt: String = "",
        sendingMessage: Boolean = false,
        apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
        streamingEnabled: Boolean = false,
        reasoningSummaryEnabled: Boolean = false,
        agentMemoryRecallEnabled: Boolean = true,
        agentProfiles: List<AgentProfileRecord> = emptyList(),
        selectedAgentProfileId: String = "",
        pendingImage: ImageAttachment? = null,
        pendingDocument: DocumentAttachment? = null,
        pendingSharedDraft: SharedDraftPayload? = null,
        sharedDraftImported: Boolean = false,
        attachingImage: Boolean = false,
        attachingDocument: Boolean = false,
        loadingConversationMessages: Boolean = false,
        chatMessages: List<ChatMessage> = emptyList(),
        knowledgeReferenceStatuses: Map<KnowledgeReference, KnowledgeReferenceStatus> = emptyMap(),
        failedKnowledgeReferenceStatuses: Set<KnowledgeReference> = emptySet(),
        answerabilityNotices: Map<String, KnowledgeAnswerabilityUserNotice> = emptyMap(),
        conversations: List<ConversationSession> = emptyList(),
        selectedConversationId: String = "",
        conversationTitle: String = "",
        activeAgentRun: AgentRunSnapshot? = null,
        pendingAgentApproval: AgentApprovalUiState? = null,
        personalTaskMode: Boolean = false,
        awaitingPersonalTaskPlanConfirmation: Boolean = false,
        personalTaskOperationPhase: PersonalTaskOperationUiPhase? = null,
        personalTaskFailure: PersonalTaskFailureUiState? = null,
        personalTaskCompletion: PersonalTaskCompletionUiState? = null,
    ): ConversationUiState {
        val agentCommand = AgentCommand.matches(prompt) || personalTaskMode
        val attaching = attachingImage || attachingDocument
        val ordinaryChatEnabled = enabledModels.isNotEmpty()
        val selectedAgent = agentProfiles.firstOrNull { profile -> profile.id == selectedAgentProfileId }
        val displayedReferences = chatMessages
            .flatMap(ChatMessage::knowledgeReferencesForDisplay)
            .distinct()
        // long: Agent Profile 可以独立提供运行模型，因此 `/agent` 在普通聊天模型列表为空时仍可发送；附件入口继续依赖普通 Provider 模型，避免改变既有选择器边界。
        val canUseComposer = ordinaryChatEnabled || agentCommand
        val canSend = !sendingMessage && !attaching && !loadingConversationMessages &&
            !awaitingPersonalTaskPlanConfirmation && prompt.isNotBlank() && canUseComposer
        val waitingForModelStart = personalTaskOperationPhase == null && sendingMessage && chatMessages.lastOrNull()
            ?.takeIf { message -> message.role == "assistant" }
            ?.text
            .isNullOrBlank() && pendingAgentApproval == null

        return ConversationUiState(
            conversationId = selectedConversationId,
            header = ConversationHeaderUiState(
                themeMode = themeMode,
                conversations = conversations,
                title = conversationTitle,
            ),
            provider = ConversationProviderUiState(
                profiles = profiles,
                selectedProfileName = profileName,
                hasEnabledModels = ordinaryChatEnabled,
            ),
            messages = ConversationMessagesUiState(
                chatMessages = chatMessages,
                knowledgeReferenceStatuses = knowledgeReferenceStatuses,
                failedKnowledgeReferenceStatuses = failedKnowledgeReferenceStatuses,
                answerabilityNotices = answerabilityNotices,
                displayedKnowledgeReferences = displayedReferences,
                activeAgentRun = activeAgentRun,
                pendingAgentApproval = pendingAgentApproval,
                waitingForModelStart = waitingForModelStart,
            ),
            composer = ConversationComposerUiState(
                prompt = prompt,
                sendingMessage = sendingMessage,
                model = model,
                enabledModels = enabledModels,
                apiMode = apiMode,
                streamingEnabled = streamingEnabled,
                reasoningSummaryEnabled = reasoningSummaryEnabled,
                agentMemoryRecallEnabled = agentMemoryRecallEnabled,
                agentProfiles = agentProfiles,
                selectedAgentProfileId = selectedAgentProfileId,
                pendingImage = pendingImage,
                pendingDocument = pendingDocument,
                pendingSharedDraft = pendingSharedDraft,
                sharedDraftImported = sharedDraftImported,
                attachingImage = attachingImage,
                attachingDocument = attachingDocument,
                loadingConversationMessages = loadingConversationMessages,
                agentCommand = agentCommand,
                personalTaskMode = personalTaskMode,
                awaitingPersonalTaskPlanConfirmation = awaitingPersonalTaskPlanConfirmation,
                personalTaskOperationPhase = personalTaskOperationPhase,
                personalTaskFailure = personalTaskFailure,
                personalTaskCompletion = personalTaskCompletion,
                canSend = canSend,
                controlsEnabled = !sendingMessage && !awaitingPersonalTaskPlanConfirmation && canUseComposer,
                attachmentEnabled = !sendingMessage && !attaching && !loadingConversationMessages &&
                    ordinaryChatEnabled && !personalTaskMode && !awaitingPersonalTaskPlanConfirmation,
                voiceInputEnabled = !sendingMessage && !attaching && !loadingConversationMessages &&
                    !awaitingPersonalTaskPlanConfirmation,
                memoryOptionEnabled = !sendingMessage && selectedAgent?.memoryEnabled == true,
            ),
        )
    }
}
