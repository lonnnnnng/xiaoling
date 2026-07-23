package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSelectionPolicyTest {
    @Test
    fun openNewReusesSelectedEmptyConversationWithoutChangingPlaceholderSet() {
        val real = session(
            id = "real",
            title = "真实会话",
            messages = listOf(ChatMessage(id = "real-user", role = "user", text = "已有内容")),
            updatedAt = 10L,
        )
        val selectedEmpty = session(id = "selected-empty", title = "当前空会话", updatedAt = 20L)
        val otherEmpty = session(id = "other-empty", title = "其他空会话", updatedAt = 30L)
        val state = visibleState(
            conversations = listOf(real, selectedEmpty, otherEmpty),
            selected = selectedEmpty,
            loading = true,
            result = OperationResult(false, "旧提示", "旧内容"),
        )

        val plan = state.planOpenNewConversation(currentTimeMillis = { 100L })
        val immediate = plan
        val projected = state.withImmediateConversationSelection(
            selection = immediate,
            activeAgentRun = null,
            pendingAgentApproval = null,
        )

        assertEquals(
            listOf("real", "selected-empty", "other-empty"),
            immediate.conversations.map(ConversationSession::id),
        )
        assertEquals(selectedEmpty, immediate.conversation)
        assertTrue(immediate.restoreRuntimeState)
        assertEquals(selectedEmpty.id, projected.selectedConversationId)
        assertEquals(selectedEmpty.title, projected.conversationTitle)
        assertEquals(emptyList<ChatMessage>(), projected.chatMessages)
        assertFalse(projected.loadingConversationMessages)
        assertNull(projected.result)
    }

    @Test
    fun openNewReusesLatestEmptyConversationAndCollapsesOlderPlaceholders() {
        val selected = session(
            id = "selected-real",
            title = "当前会话",
            messages = listOf(ChatMessage(id = "selected-user", role = "user", text = "当前内容")),
            updatedAt = 40L,
        )
        val olderEmpty = session(id = "empty-old", title = "较早空会话", updatedAt = 20L)
        val latestEmpty = session(id = "empty-latest", title = "最新空会话", summary = "草稿摘要", updatedAt = 30L)
        val otherReal = session(
            id = "other-real",
            title = "其他会话",
            messages = listOf(ChatMessage(id = "other-user", role = "user", text = "其他内容")),
            updatedAt = 10L,
        )
        val state = visibleState(
            conversations = listOf(selected, olderEmpty, otherReal, latestEmpty),
            selected = selected,
        )

        val immediate = state.planOpenNewConversation(currentTimeMillis = { 100L })
        val projected = state.withImmediateConversationSelection(immediate, null, null)

        assertEquals(
            listOf("selected-real", "other-real", "empty-latest"),
            immediate.conversations.map(ConversationSession::id),
        )
        assertEquals(latestEmpty.id, projected.selectedConversationId)
        assertTrue(immediate.restoreRuntimeState)
        assertEquals(latestEmpty.title, projected.conversationTitle)
        assertEquals(latestEmpty.summary, projected.conversationSummary)
        assertEquals(emptyList<ChatMessage>(), projected.chatMessages)
    }

    @Test
    fun openNewCreatesStablePlaceholderWhenNoEmptyConversationExists() {
        val selected = session(
            id = "selected-real",
            title = "当前会话",
            messages = listOf(ChatMessage(id = "selected-user", role = "user", text = "当前内容")),
            updatedAt = 40L,
        )
        val state = visibleState(conversations = listOf(selected), selected = selected)

        val immediate = state.planOpenNewConversation(currentTimeMillis = { 123L })
        val projected = state.withImmediateConversationSelection(immediate, null, null)

        val created = immediate.conversation
        assertEquals("conversation-123", created.id)
        assertEquals("新会话", created.title)
        assertEquals("", created.summary)
        assertEquals(emptyList<ChatMessage>(), created.messages)
        assertEquals(123L, created.createdAt)
        assertEquals(123L, created.updatedAt)
        assertFalse(immediate.restoreRuntimeState)
        assertEquals(
            listOf("selected-real", created.id),
            immediate.conversations.map(ConversationSession::id),
        )
        assertEquals(created.id, projected.selectedConversationId)
        assertNull(projected.activeAgentRun)
        assertNull(projected.pendingAgentApproval)
    }

    @Test
    fun deletingCurrentConversationLoadsMostRecentlyUpdatedRemainingConversation() {
        val selected = session(id = "selected", title = "待删除", updatedAt = 40L)
        val older = session(id = "older", title = "较早会话", updatedAt = 10L)
        val latest = session(id = "latest", title = "最近会话", updatedAt = 30L)
        val middle = session(id = "middle", title = "中间会话", updatedAt = 20L)
        val state = visibleState(
            conversations = listOf(older, selected, latest, middle),
            selected = selected,
        )

        val plan = state.planCurrentConversationDeletion(currentTimeMillis = { 100L })
        val load = plan as ConversationSelectionPlan.Load

        assertEquals(listOf(older, latest, middle), load.conversations)
        assertEquals(latest, load.conversation)
        assertTrue(load.conversations.none { it.id == selected.id })
    }

    @Test
    fun deletingOnlyConversationCreatesImmediateEmptyReplacement() {
        val selected = session(id = "selected", title = "待删除", updatedAt = 40L)
        val state = visibleState(conversations = listOf(selected), selected = selected)

        val plan = state.planCurrentConversationDeletion(currentTimeMillis = { 456L })
        val immediate = plan as ConversationSelectionPlan.Immediate
        val projected = state.withImmediateConversationSelection(immediate, null, null)

        val replacement = immediate.conversation
        assertEquals("conversation-456", replacement.id)
        assertFalse(immediate.restoreRuntimeState)
        assertEquals(listOf(replacement), immediate.conversations)
        assertEquals(replacement.id, projected.selectedConversationId)
        assertEquals("新会话", projected.conversationTitle)
        assertEquals("", projected.conversationSummary)
        assertEquals(emptyList<ChatMessage>(), projected.chatMessages)
        assertFalse(projected.loadingConversationMessages)
        assertNull(projected.result)
    }

    private fun visibleState(
        conversations: List<ConversationSession>,
        selected: ConversationSession,
        loading: Boolean = false,
        result: OperationResult? = null,
    ) = XiaoLingUiState(
        conversations = conversations,
        selectedConversationId = selected.id,
        conversationTitle = selected.title,
        conversationSummary = selected.summary,
        chatMessages = selected.messages,
        loadingConversationMessages = loading,
        result = result,
    )

    private fun session(
        id: String,
        title: String,
        summary: String = "",
        messages: List<ChatMessage> = emptyList(),
        updatedAt: Long,
    ) = ConversationSession(
        id = id,
        title = title,
        summary = summary,
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = messages,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
