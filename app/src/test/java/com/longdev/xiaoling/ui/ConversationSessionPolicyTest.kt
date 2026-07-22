package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSessionPolicyTest {
    @Test
    fun firstUserMessageCreatesTrimmedEighteenCharacterTitle() {
        val messages = listOf(
            ChatMessage(id = "assistant-1", role = "assistant", text = "忽略模型消息", createdAt = 1L),
            ChatMessage(
                id = "user-1",
                role = "user",
                text = "  一二三四五六七八九十一二三四五六七八九十  ",
                createdAt = 2L,
            ),
        )

        assertEquals("一二三四五六七八九十一二三四五六七八", messages.firstUserTitle())
        assertEquals(
            "新会话",
            listOf(
                ChatMessage(id = "user-blank", role = "user", text = "   "),
                ChatMessage(id = "user-later", role = "user", text = "不能跳过首条用户消息"),
            ).firstUserTitle(),
        )
    }

    @Test
    fun duplicateEmptyConversationsKeepPreferredPlaceholderAndEveryRealConversation() {
        val real = session(
            id = "real",
            messages = listOf(ChatMessage(id = "user-real", role = "user", text = "已保存内容")),
            updatedAt = 1L,
        )
        val olderEmpty = session(id = "empty-old", updatedAt = 2L)
        val preferredEmpty = session(id = "empty-current", updatedAt = 3L)

        val collapsed = listOf(olderEmpty, real, preferredEmpty)
            .collapseDuplicateEmptyConversations(preferredId = preferredEmpty.id)

        assertEquals(listOf(real, preferredEmpty), collapsed)
    }

    @Test
    fun selectedConversationUpdatePreservesCreationAndSynchronizesVisibleState() {
        val existing = session(id = "selected", updatedAt = 20L).copy(createdAt = 10L)
        val state = XiaoLingUiState(
            conversations = listOf(existing),
            selectedConversationId = existing.id,
            conversationTitle = existing.title,
            conversationSummary = existing.summary,
            chatMessages = existing.messages,
        )
        val messages = listOf(
            ChatMessage(id = "user-1", role = "user", text = "新的会话标题", createdAt = 30L),
        )

        val updated = state.withUpdatedConversation(
            conversationId = existing.id,
            messages = messages,
            summary = "新摘要",
            summaryUntilMessageId = "user-1",
            summaryUpdatedAt = 90L,
            summaryModel = "test-model",
            currentTimeMillis = { 100L },
        )

        val conversation = updated.conversations.single()
        assertEquals("新的会话标题", conversation.title)
        assertEquals(10L, conversation.createdAt)
        assertEquals(100L, conversation.updatedAt)
        assertEquals("新摘要", conversation.summary)
        assertEquals("user-1", conversation.summaryUntilMessageId)
        assertEquals("test-model", conversation.summaryModel)
        assertEquals(messages, updated.chatMessages)
        assertEquals(conversation.title, updated.conversationTitle)
        assertEquals(conversation.summary, updated.conversationSummary)
    }

    @Test
    fun nonSelectedConversationUpdateDoesNotPolluteVisibleConversation() {
        val visibleMessage = ChatMessage(id = "visible-user", role = "user", text = "当前窗口")
        val visible = session(id = "visible", messages = listOf(visibleMessage), updatedAt = 10L)
            .copy(title = "当前会话", summary = "当前摘要")
        val background = session(id = "background", updatedAt = 20L)
        val state = XiaoLingUiState(
            conversations = listOf(visible, background),
            selectedConversationId = visible.id,
            conversationTitle = visible.title,
            conversationSummary = visible.summary,
            chatMessages = visible.messages,
        )
        val backgroundMessages = listOf(
            ChatMessage(id = "background-user", role = "user", text = "后台结果", createdAt = 30L),
        )

        val updated = state.withUpdatedConversation(
            conversationId = background.id,
            messages = backgroundMessages,
            summary = "后台摘要",
            currentTimeMillis = { 100L },
        )

        assertEquals(visible.id, updated.selectedConversationId)
        assertEquals(visible.title, updated.conversationTitle)
        assertEquals(visible.summary, updated.conversationSummary)
        assertEquals(visible.messages, updated.chatMessages)
        assertEquals(backgroundMessages, updated.conversations.single { it.id == background.id }.messages)
    }

    @Test
    fun blankConversationIdCreatesStableIsolatedConversationFromInjectedClock() {
        val messages = listOf(
            ChatMessage(id = "user-new", role = "user", text = "创建新会话", createdAt = 150L),
        )

        val updated = XiaoLingUiState().withUpdatedConversation(
            conversationId = "",
            messages = messages,
            summary = "",
            currentTimeMillis = { 200L },
        )

        val conversation = updated.conversations.single()
        assertEquals("conversation-200", conversation.id)
        assertEquals(200L, conversation.createdAt)
        assertEquals(200L, conversation.updatedAt)
        assertEquals("", updated.selectedConversationId)
        assertEquals(emptyList<ChatMessage>(), updated.chatMessages)
    }

    @Test
    fun currentConversationUpdateInheritsExistingSummaryMetadata() {
        val existing = session(id = "selected", updatedAt = 20L).copy(
            summary = "旧摘要",
            summaryUntilMessageId = "boundary-1",
            summaryUpdatedAt = 15L,
            summaryModel = "summary-model",
        )
        val state = XiaoLingUiState(
            conversations = listOf(existing),
            selectedConversationId = existing.id,
            conversationTitle = existing.title,
            conversationSummary = existing.summary,
        )
        val messages = listOf(ChatMessage(id = "user-next", role = "user", text = "继续", createdAt = 30L))

        val updated = state.withUpdatedCurrentConversation(
            messages = messages,
            summary = "更新摘要",
            currentTimeMillis = { 100L },
        )

        val conversation = updated.conversations.single()
        assertEquals("boundary-1", conversation.summaryUntilMessageId)
        assertEquals(15L, conversation.summaryUpdatedAt)
        assertEquals("summary-model", conversation.summaryModel)
        assertEquals("更新摘要", updated.conversationSummary)
        assertEquals(messages, updated.chatMessages)
    }

    private fun session(
        id: String,
        messages: List<ChatMessage> = emptyList(),
        updatedAt: Long,
    ) = ConversationSession(
        id = id,
        title = "新会话",
        summary = "",
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = messages,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
