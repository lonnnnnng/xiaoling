package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLoadProjectionPolicyTest {
    @Test
    fun loadingClearsPreviousResultWithoutChangingVisibleConversation() {
        val visible = session(
            id = "visible",
            title = "当前会话",
            summary = "当前摘要",
            messages = listOf(ChatMessage(id = "visible-message", role = "user", text = "当前消息")),
        )
        val state = XiaoLingUiState(
            conversations = listOf(visible),
            selectedConversationId = visible.id,
            conversationTitle = visible.title,
            conversationSummary = visible.summary,
            chatMessages = visible.messages,
            result = OperationResult(false, "旧提示", "旧提示内容"),
        )

        val projected = state.withConversationLoadEvent(ConversationLoadEvent.Loading)

        assertTrue(projected.loadingConversationMessages)
        assertEquals(null, projected.result)
        assertEquals(visible.id, projected.selectedConversationId)
        assertEquals(visible.messages, projected.chatMessages)
    }

    @Test
    fun loadedAtomicallySelectsConversationAndKeepsBinaryOnlyForVisibleMessages() {
        val oldSelected = session(
            id = "old",
            title = "旧会话",
            summary = "旧摘要",
            messages = listOf(imageMessage("old-message", 1)),
        )
        val oldDocument = session(
            id = "old-document",
            title = "旧文档会话",
            messages = listOf(documentMessage("old-document-message")),
        )
        val target = session(
            id = "target",
            title = "目标会话",
            summary = "目标摘要",
            messages = emptyList(),
        )
        val loadedMessages = listOf(imageMessage("target-message", 2))
        val success = OperationResult(true, "已删除", "当前会话已删除")
        val request = ConversationLoadRequest(
            conversation = target,
            conversations = listOf(oldSelected, oldDocument, target),
            result = success,
        )

        val projected = XiaoLingUiState(
            conversations = request.conversations,
            selectedConversationId = oldSelected.id,
            conversationTitle = oldSelected.title,
            conversationSummary = oldSelected.summary,
            chatMessages = oldSelected.messages,
            loadingConversationMessages = true,
        ).withConversationLoadEvent(
            event = ConversationLoadEvent.Loaded(request, loadedMessages),
            activeAgentRun = null,
            pendingAgentApproval = null,
        )

        assertFalse(projected.loadingConversationMessages)
        assertEquals(target.id, projected.selectedConversationId)
        assertEquals(target.title, projected.conversationTitle)
        assertEquals(target.summary, projected.conversationSummary)
        assertEquals(loadedMessages, projected.chatMessages)
        assertEquals(loadedMessages, projected.conversations.single { it.id == target.id }.messages)
        assertEquals(
            listOf(MessagePart.Text(id = "old-message-text", text = "图片消息")),
            projected.conversations.single { it.id == oldSelected.id }.messages.single().parts,
        )
        assertEquals(
            listOf(MessagePart.Text(id = "old-document-message-text", text = "文档消息")),
            projected.conversations.single { it.id == oldDocument.id }.messages.single().parts,
        )
        val visibleImage = projected.chatMessages.single().parts.filterIsInstance<MessagePart.Image>().single()
        assertArrayEquals(pngSignature() + byteArrayOf(2), visibleImage.attachment.copyData())
        assertEquals(success, projected.result)
    }

    @Test
    fun failedStopsLoadingAndKeepsErrorMessageOrStableFallback() {
        val state = XiaoLingUiState(
            selectedConversationId = "selected",
            conversationTitle = "当前会话",
            loadingConversationMessages = true,
        )
        val request = ConversationLoadRequest(
            conversation = session(id = "target", title = "目标会话"),
            conversations = emptyList(),
            result = null,
        )

        val projected = state.withConversationLoadEvent(
            ConversationLoadEvent.Failed(request, IllegalStateException("Room 读取失败")),
        )

        assertFalse(projected.loadingConversationMessages)
        assertEquals("selected", projected.selectedConversationId)
        assertEquals("当前会话", projected.conversationTitle)
        assertEquals(false, projected.result?.success)
        assertEquals("会话读取失败", projected.result?.title)
        assertEquals("Room 读取失败", projected.result?.message)
        assertEquals(
            "无法加载会话消息",
            state.withConversationLoadEvent(
                ConversationLoadEvent.Failed(request, IllegalStateException()),
            ).result?.message,
        )
    }

    private fun imageMessage(id: String, marker: Byte): ChatMessage {
        val attachment = ImageAttachmentPolicy.create(
            fileName = "$id.png",
            mimeType = "image/png",
            data = pngSignature() + byteArrayOf(marker),
        )
        return ChatMessage(
            id = id,
            role = "user",
            text = "图片消息",
            origin = MessageOrigin.USER,
            parts = listOf(
                MessagePart.Image(id = "$id-image", attachment = attachment),
                MessagePart.Text(id = "$id-text", text = "图片消息"),
            ),
        )
    }

    private fun documentMessage(id: String): ChatMessage {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "$id.md",
            mimeType = "text/markdown",
            data = "文档内容".toByteArray(),
        )
        return ChatMessage(
            id = id,
            role = "user",
            text = "文档消息",
            origin = MessageOrigin.USER,
            parts = listOf(
                MessagePart.Document(id = "$id-document", attachment = attachment),
                MessagePart.Text(id = "$id-text", text = "文档消息"),
            ),
        )
    }

    private fun session(
        id: String,
        title: String = "新会话",
        summary: String = "",
        messages: List<ChatMessage> = emptyList(),
    ) = ConversationSession(
        id = id,
        title = title,
        summary = summary,
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = messages,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun pngSignature(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
