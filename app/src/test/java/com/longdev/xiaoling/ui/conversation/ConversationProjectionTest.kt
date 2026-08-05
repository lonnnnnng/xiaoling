package com.longdev.xiaoling.ui.conversation

import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.ui.AgentApprovalUiState
import com.longdev.xiaoling.ui.ChatMessage
import com.longdev.xiaoling.ui.PersonalTaskCompletionUiState
import com.longdev.xiaoling.ui.PersonalTaskFailureUiState
import com.longdev.xiaoling.ui.PersonalTaskOperationUiPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationProjectionTest {
    @Test
    fun agentCommandCanRunWithoutOrdinaryChatModelButKeepsAttachmentsDisabled() {
        val state = project(
            prompt = "/agent 查找旧会话",
            enabledModels = emptyList(),
            agentProfiles = listOf(agentProfile(memoryEnabled = false)),
            selectedAgentProfileId = "agent",
        )

        assertTrue(state.composer.agentCommand)
        assertTrue(state.composer.canSend)
        assertFalse(state.composer.attachmentEnabled)
        assertFalse(state.composer.memoryOptionEnabled)
    }

    @Test
    fun personalTaskModeUsesAgentProfileAndWaitsForPlanConfirmation() {
        val ready = project(
            prompt = "查看当前时间并记到笔记",
            enabledModels = emptyList(),
            agentProfiles = listOf(agentProfile(memoryEnabled = true)),
            selectedAgentProfileId = "agent",
            personalTaskMode = true,
        )
        val awaitingConfirmation = project(
            prompt = "另一个任务",
            enabledModels = emptyList(),
            agentProfiles = listOf(agentProfile(memoryEnabled = true)),
            selectedAgentProfileId = "agent",
            personalTaskMode = true,
            awaitingPersonalTaskPlanConfirmation = true,
        )

        assertTrue(ready.composer.personalTaskMode)
        assertTrue(ready.composer.agentCommand)
        assertTrue(ready.composer.canSend)
        assertFalse(ready.composer.attachmentEnabled)
        assertFalse(awaitingConfirmation.composer.canSend)
        assertFalse(awaitingConfirmation.composer.controlsEnabled)
    }

    @Test
    fun waitingIndicatorOnlyAppearsBeforeModelContentAndWithoutApproval() {
        val waiting = project(
            sendingMessage = true,
            chatMessages = listOf(ChatMessage(id = "assistant", role = "assistant", text = "")),
        )
        val approval = project(
            sendingMessage = true,
            chatMessages = listOf(ChatMessage(id = "assistant", role = "assistant", text = "")),
            pendingAgentApproval = AgentApprovalUiState(
                requestId = "approval",
                runId = "run",
                conversationId = "conversation",
                toolCallId = "tool-call",
                toolName = "notes.create",
                toolDescription = "创建笔记",
                riskLabel = "需确认",
                arguments = emptyMap(),
                expiresAt = Long.MAX_VALUE,
            ),
        )

        assertTrue(waiting.messages.waitingForModelStart)
        assertFalse(approval.messages.waitingForModelStart)
    }

    @Test
    fun personalTaskProgressOwnsWaitingStateAndFailureKeepsRetryableGoal() {
        val planning = project(
            prompt = "整理今天的任务",
            sendingMessage = true,
            personalTaskMode = true,
            personalTaskOperationPhase = PersonalTaskOperationUiPhase.GENERATING_PLAN,
        )
        val failed = project(
            prompt = "整理今天的任务",
            personalTaskMode = true,
            personalTaskFailure = PersonalTaskFailureUiState(
                goal = "整理今天的任务",
                title = "响应格式错误",
                message = "模型没有返回有效计划",
            ),
        )

        assertEquals(PersonalTaskOperationUiPhase.GENERATING_PLAN, planning.composer.personalTaskOperationPhase)
        assertFalse(planning.messages.waitingForModelStart)
        assertEquals("整理今天的任务", failed.composer.personalTaskFailure?.goal)
        assertTrue(failed.composer.canSend)
    }

    @Test
    fun completedPersonalTaskKeepsResultEntryVisibleWithoutBlockingNextTask() {
        val completed = PersonalTaskCompletionUiState(
            workflowId = "workflow-1",
            title = "任务目标已验证完成",
            message = "已验证步骤 2/2，可查看任务证据",
        )

        val state = project(
            personalTaskMode = true,
            personalTaskCompletion = completed,
        )

        assertEquals(completed, state.composer.personalTaskCompletion)
        assertTrue(state.composer.canSend)
    }

    @Test
    fun ordinaryChatRequiresEnabledModelAndIdleComposer() {
        val withoutModel = project(enabledModels = emptyList())
        val attaching = project(attachingImage = true)
        val loadingConversation = project(loadingConversationMessages = true)

        assertFalse(withoutModel.composer.canSend)
        assertFalse(withoutModel.composer.controlsEnabled)
        assertFalse(withoutModel.composer.attachmentEnabled)
        assertFalse(attaching.composer.canSend)
        assertFalse(attaching.composer.attachmentEnabled)
        assertFalse(loadingConversation.composer.canSend)
        assertFalse(loadingConversation.composer.attachmentEnabled)
    }

    @Test
    fun displayedKnowledgeReferencesAreDeduplicatedAcrossMessages() {
        val reference = knowledgeReference()
        val state = project(
            chatMessages = listOf(
                toolMessage(id = "first", reference = reference),
                toolMessage(id = "second", reference = reference),
            ),
        )

        assertEquals(listOf(reference), state.messages.displayedKnowledgeReferences)
    }

    private fun project(
        prompt: String = "普通消息",
        sendingMessage: Boolean = false,
        enabledModels: List<String> = listOf("model"),
        chatMessages: List<ChatMessage> = emptyList(),
        agentProfiles: List<AgentProfileRecord> = emptyList(),
        selectedAgentProfileId: String = "",
        pendingAgentApproval: AgentApprovalUiState? = null,
        attachingImage: Boolean = false,
        loadingConversationMessages: Boolean = false,
        personalTaskMode: Boolean = false,
        awaitingPersonalTaskPlanConfirmation: Boolean = false,
        personalTaskOperationPhase: PersonalTaskOperationUiPhase? = null,
        personalTaskFailure: PersonalTaskFailureUiState? = null,
        personalTaskCompletion: PersonalTaskCompletionUiState? = null,
    ) = ConversationProjection.project(
        prompt = prompt,
        sendingMessage = sendingMessage,
        enabledModels = enabledModels,
        chatMessages = chatMessages,
        agentProfiles = agentProfiles,
        selectedAgentProfileId = selectedAgentProfileId,
        pendingAgentApproval = pendingAgentApproval,
        attachingImage = attachingImage,
        loadingConversationMessages = loadingConversationMessages,
        personalTaskMode = personalTaskMode,
        awaitingPersonalTaskPlanConfirmation = awaitingPersonalTaskPlanConfirmation,
        personalTaskOperationPhase = personalTaskOperationPhase,
        personalTaskFailure = personalTaskFailure,
        personalTaskCompletion = personalTaskCompletion,
    )

    private fun toolMessage(id: String, reference: KnowledgeReference) = ChatMessage(
        id = id,
        role = "assistant",
        text = "答案",
        origin = MessageOrigin.AGENT_RESULT,
        verifiedAgentContext = VerifiedAgentContext(
            runId = "run-$id",
            toolName = "knowledge.search",
            arguments = emptyMap(),
            success = true,
            verificationStatus = AgentVerificationStatus.READABLE_ONLY,
            rawResult = "证据",
            knowledgeReferences = listOf(reference),
        ),
    )

    private fun knowledgeReference() = KnowledgeReference(
        retrievalId = "retrieval",
        documentId = "document",
        documentName = "知识.md",
        documentRevision = 1,
        chunkId = "chunk",
        chunkSequence = 0,
        startOffset = 0,
        endOffset = 4,
    )

    private fun agentProfile(memoryEnabled: Boolean) = AgentProfileRecord(
        id = "agent",
        name = "Agent",
        avatar = "A",
        providerId = "provider",
        model = "model",
        apiMode = ApiMode.RESPONSES,
        systemPrompt = "system",
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = listOf("app.current_time"),
        allowedSkillIds = emptyList(),
        memoryEnabled = memoryEnabled,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
