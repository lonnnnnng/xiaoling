package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserState
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.MessageOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerabilityNoticeProjectionTest {
    @Test
    fun noticeProjectionKeepsPublishedAnswerAndReferencesUnchanged() {
        val reference = KnowledgeReference(
            retrievalId = "retrieval-notice",
            documentId = "document-notice",
            documentName = "知识.md",
            documentRevision = 1,
            chunkId = "chunk-notice",
            chunkSequence = 0,
            startOffset = 0,
            endOffset = 10,
        )
        val message = ChatMessage(
            id = "message-notice",
            role = "assistant",
            text = "原始 Agent 答案",
            createdAt = 2L,
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = VerifiedAgentContext(
                runId = "run-notice",
                toolName = "knowledge.search",
                arguments = emptyMap(),
                success = true,
                verificationStatus = AgentVerificationStatus.VERIFIED,
                rawResult = "候选正文",
                knowledgeReferences = listOf(reference),
            ),
        )
        val conversation = ConversationSession(
            id = "conversation-notice",
            title = "测试",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = listOf(message),
            createdAt = 1L,
            updatedAt = 2L,
        )
        val state = XiaoLingUiState(
            conversations = listOf(conversation),
            selectedConversationId = conversation.id,
            chatMessages = listOf(message),
        )
        val notice = KnowledgeAnswerabilityUserNotice(
            state = KnowledgeAnswerabilityUserState.NOT_ANSWERED,
            title = "本地知识未直接回答问题",
            detail = "只读观察",
        )

        val updated = state.withAnswerabilityNotice(message.id, notice)

        assertEquals(state.chatMessages, updated.chatMessages)
        assertEquals(state.conversations, updated.conversations)
        assertEquals(listOf(reference), updated.chatMessages.single().verifiedAgentContext?.knowledgeReferences)
        assertEquals(mapOf(message.id to notice), updated.answerabilityNotices)
    }

    @Test
    fun deletingConversationPrunesItsNoticeWithoutTouchingOtherMessages() {
        val first = ChatMessage(id = "message-first", role = "assistant", text = "first")
        val second = ChatMessage(id = "message-second", role = "assistant", text = "second")
        val notice = KnowledgeAnswerabilityUserNotice(
            state = KnowledgeAnswerabilityUserState.UNKNOWN,
            title = "尚未确认",
            detail = "只读观察",
        )
        val state = XiaoLingUiState(
            conversations = listOf(
                conversation(id = "conversation-first", message = first),
                conversation(id = "conversation-second", message = second),
            ),
            answerabilityNotices = mapOf(first.id to notice, second.id to notice),
        )

        val updated = state
            .withoutAnswerabilityNoticesForConversation("conversation-first")
            .copy(conversations = state.conversations.drop(1))
            .pruneAnswerabilityNotices()

        assertEquals(mapOf(second.id to notice), updated.answerabilityNotices)
    }

    private fun conversation(id: String, message: ChatMessage) = ConversationSession(
        id = id,
        title = id,
        summary = "",
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = listOf(message),
        createdAt = 1L,
        updatedAt = 1L,
    )
}
