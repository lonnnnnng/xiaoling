package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.prompt.PromptSettings
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationRequestContextPreparerTest {
    @Test
    fun shortConversationBuildsDirectRequestWithoutSummary() = runTest {
        var summaryRequested = false
        val settings = PromptSettings()
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, _, _, _ ->
                summaryRequested = true
                "不应生成摘要"
            },
            currentTimeMillis = { 123L },
        )

        val prepared = preparer.prepare(
            config = config(),
            messages = listOf(
                ChatMessage(role = "user", text = "你好"),
                ChatMessage(role = "assistant", text = "你好，有什么可以帮你？"),
            ),
            conversation = null,
            promptSettings = settings,
        )

        assertEquals(listOf("system", "user", "assistant"), prepared.requestMessages.map { it.role })
        assertEquals(PromptPolicy.chatSystemPrompt(settings), prepared.requestMessages[0].content)
        assertEquals("你好", prepared.requestMessages[1].content)
        assertEquals(
            "{\"evidence_status\":\"untrusted\",\"message_source\":\"ordinary_assistant\",\"content\":\"你好，有什么可以帮你？\"}",
            prepared.requestMessages[2].content,
        )
        assertEquals("", prepared.summary)
        assertNull(prepared.summaryUntilMessageId)
        assertNull(prepared.summaryUpdatedAt)
        assertNull(prepared.summaryModel)
        assertFalse(summaryRequested)
    }

    @Test
    fun longConversationCompressesOnlyMessagesBeforeRecentWindow() = runTest {
        val messages = (1..17).map { index ->
            ChatMessage(
                id = "message-$index",
                role = "user",
                text = "消息$index",
                createdAt = index.toLong(),
            )
        }
        var compressedMessageIds = emptyList<String>()
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, existingSummary, messagesToCompress, _ ->
                assertEquals("", existingSummary)
                compressedMessageIds = messagesToCompress.map(ChatMessage::id)
                "压缩摘要"
            },
            currentTimeMillis = { 456L },
        )

        val prepared = preparer.prepare(
            config = config(model = " test-model "),
            messages = messages,
            conversation = null,
            promptSettings = PromptSettings(),
        )

        assertEquals(listOf("message-1"), compressedMessageIds)
        assertEquals("压缩摘要", prepared.summary)
        assertEquals("message-1", prepared.summaryUntilMessageId)
        assertEquals(456L, prepared.summaryUpdatedAt)
        assertEquals("test-model", prepared.summaryModel)
        assertEquals(18, prepared.requestMessages.size)
        assertEquals("以下是较早对话的持续摘要，请在回答当前问题时一并参考：\n压缩摘要", prepared.requestMessages[1].content)
        assertEquals("消息2", prepared.requestMessages[2].content)
        assertEquals("消息17", prepared.requestMessages.last().content)
    }

    @Test
    fun matchingSummaryBoundaryReusesStoredSummaryWithoutModelCall() = runTest {
        val messages = (1..17).map { index ->
            ChatMessage(
                id = "message-$index",
                role = "user",
                text = "消息$index",
                createdAt = index.toLong(),
            )
        }
        var summaryRequested = false
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, _, _, _ ->
                summaryRequested = true
                "重复摘要"
            },
            currentTimeMillis = { 999L },
        )

        val prepared = preparer.prepare(
            config = config(),
            messages = messages,
            conversation = ConversationSession(
                id = "conversation-1",
                title = "会话",
                summary = "已有摘要",
                summaryUntilMessageId = "message-1",
                summaryUpdatedAt = 100L,
                summaryModel = "old-model",
                messages = messages,
                createdAt = 1L,
                updatedAt = 2L,
            ),
            promptSettings = PromptSettings(),
        )

        assertFalse(summaryRequested)
        assertEquals("已有摘要", prepared.summary)
        assertEquals("message-1", prepared.summaryUntilMessageId)
        assertEquals(100L, prepared.summaryUpdatedAt)
        assertEquals("old-model", prepared.summaryModel)
        assertEquals("消息2", prepared.requestMessages[2].content)
    }

    @Test
    fun staleKnowledgeMessageInvalidatesStoredSummaryBeforeWindowing() = runTest {
        val staleReference = KnowledgeReference(
            retrievalId = "retrieval-stale",
            documentId = "document-stale",
            documentName = "旧知识.md",
            documentRevision = 1,
            chunkId = "chunk-stale-r1-0",
            chunkSequence = 0,
            startOffset = 0,
            endOffset = 8,
        )
        val staleKnowledgeMessage = ChatMessage(
            id = "knowledge-message",
            role = "assistant",
            text = "已经失效的知识正文",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = VerifiedAgentContext(
                runId = "run-stale",
                toolName = "knowledge.search",
                arguments = mapOf("query" to "旧知识"),
                success = true,
                verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                rawResult = "已经失效的知识正文",
                knowledgeReferences = listOf(staleReference),
            ),
        )
        val recentMessages = (1..16).map { index ->
            ChatMessage(id = "recent-$index", role = "user", text = "最近消息$index")
        }
        var summaryRequested = false
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { emptySet() },
            generateSummary = { _, _, _, _ ->
                summaryRequested = true
                "不应生成摘要"
            },
            currentTimeMillis = { 999L },
        )

        val prepared = preparer.prepare(
            config = config(),
            messages = listOf(staleKnowledgeMessage) + recentMessages,
            conversation = ConversationSession(
                id = "conversation-stale",
                title = "会话",
                summary = "包含旧知识的摘要",
                summaryUntilMessageId = staleKnowledgeMessage.id,
                summaryUpdatedAt = 100L,
                summaryModel = "old-model",
                messages = listOf(staleKnowledgeMessage) + recentMessages,
                createdAt = 1L,
                updatedAt = 2L,
            ),
            promptSettings = PromptSettings(),
        )

        assertFalse(summaryRequested)
        assertEquals("", prepared.summary)
        assertNull(prepared.summaryUntilMessageId)
        assertNull(prepared.summaryUpdatedAt)
        assertNull(prepared.summaryModel)
        assertEquals(17, prepared.requestMessages.size)
        assertFalse(prepared.requestMessages.any { it.content.contains("已经失效的知识正文") })
    }

    @Test
    fun summaryCancellationIsPropagatedInsteadOfFallingBack() = runTest {
        val messages = (1..17).map { index ->
            ChatMessage(id = "message-$index", role = "user", text = "消息$index")
        }
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, _, _, _ -> throw CancellationException("停止生成") },
        )

        try {
            preparer.prepare(
                config = config(),
                messages = messages,
                conversation = null,
                promptSettings = PromptSettings(),
            )
            fail("摘要取消必须继续传播")
        } catch (expected: CancellationException) {
            assertEquals("停止生成", expected.message)
        }
    }

    @Test
    fun missingStoredSummaryBoundaryRebuildsWithoutReusingOldSummary() = runTest {
        val messages = (1..18).map { index ->
            ChatMessage(id = "message-$index", role = "user", text = "消息$index")
        }
        var suppliedExistingSummary: String? = null
        var compressedMessageIds = emptyList<String>()
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, existingSummary, messagesToCompress, _ ->
                suppliedExistingSummary = existingSummary
                compressedMessageIds = messagesToCompress.map(ChatMessage::id)
                "重建摘要"
            },
            currentTimeMillis = { 321L },
        )

        val prepared = preparer.prepare(
            config = config(),
            messages = messages,
            conversation = ConversationSession(
                id = "conversation-missing-boundary",
                title = "会话",
                summary = "边界已经丢失的旧摘要",
                summaryUntilMessageId = "missing-message",
                summaryUpdatedAt = 100L,
                summaryModel = "old-model",
                messages = messages,
                createdAt = 1L,
                updatedAt = 2L,
            ),
            promptSettings = PromptSettings(),
        )

        assertEquals("", suppliedExistingSummary)
        assertEquals(listOf("message-1", "message-2"), compressedMessageIds)
        assertEquals("重建摘要", prepared.summary)
        assertEquals("message-2", prepared.summaryUntilMessageId)
        assertEquals(321L, prepared.summaryUpdatedAt)
        assertEquals("test-model", prepared.summaryModel)
    }

    @Test
    fun storedSummaryBoundaryAfterCurrentTargetRebuildsFromCurrentHistory() = runTest {
        val messages = (1..17).map { index ->
            ChatMessage(id = "message-$index", role = "user", text = "消息$index")
        }
        var suppliedExistingSummary: String? = null
        var compressedMessageIds = emptyList<String>()
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, existingSummary, messagesToCompress, _ ->
                suppliedExistingSummary = existingSummary
                compressedMessageIds = messagesToCompress.map(ChatMessage::id)
                "重建摘要"
            },
        )

        val prepared = preparer.prepare(
            config = config(),
            messages = messages,
            conversation = ConversationSession(
                id = "conversation-forward-boundary",
                title = "会话",
                summary = "边界已经超前的旧摘要",
                summaryUntilMessageId = "message-17",
                summaryUpdatedAt = 100L,
                summaryModel = "old-model",
                messages = messages,
                createdAt = 1L,
                updatedAt = 2L,
            ),
            promptSettings = PromptSettings(),
        )

        assertEquals("", suppliedExistingSummary)
        assertEquals(listOf("message-1"), compressedMessageIds)
        assertEquals("重建摘要", prepared.summary)
        assertEquals("message-1", prepared.summaryUntilMessageId)
    }

    @Test
    fun onlyResponsesRecentWindowCarriesUserImage() = runTest {
        val oldAttachment = ImageAttachmentPolicy.create(
            fileName = "old.png",
            mimeType = "image/png",
            data = pngSignature() + byteArrayOf(1),
        )
        val recentAttachment = ImageAttachmentPolicy.create(
            fileName = "recent.png",
            mimeType = "image/png",
            data = pngSignature() + byteArrayOf(2),
        )
        val messages = listOf(
            userImageMessage("message-1", "旧图片", oldAttachment),
        ) + (2..16).map { index ->
            ChatMessage(id = "message-$index", role = "user", text = "消息$index")
        } + userImageMessage("message-17", "最近图片", recentAttachment)
        val preparer = ConversationRequestContextPreparer(
            retainCurrentKnowledgeReferences = { references -> references.toSet() },
            generateSummary = { _, _, _, _ -> "图片窗口摘要" },
        )

        val responses = preparer.prepare(
            config = config(apiMode = ApiMode.RESPONSES),
            messages = messages,
            conversation = null,
            promptSettings = PromptSettings(),
        )
        val chatCompletions = preparer.prepare(
            config = config(apiMode = ApiMode.CHAT_COMPLETIONS),
            messages = messages,
            conversation = null,
            promptSettings = PromptSettings(),
        )

        assertEquals(listOf(recentAttachment), responses.requestMessages.flatMap { it.images })
        assertTrue(chatCompletions.requestMessages.flatMap { it.images }.isEmpty())
    }

    private fun userImageMessage(
        id: String,
        text: String,
        attachment: com.longdev.xiaoling.model.ImageAttachment,
    ) = ChatMessage(
        id = id,
        role = "user",
        text = text,
        origin = MessageOrigin.USER,
        parts = listOf(
            MessagePart.Image(id = "$id-image", attachment = attachment),
            MessagePart.Text(id = "$id-text", text = text),
        ),
    )

    private fun pngSignature(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun config(
        model: String = "test-model",
        apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    ) = ProviderRequestConfig(
        baseUrl = "https://example.com/v1",
        apiKey = "test-key",
        model = model,
        apiMode = apiMode,
    )
}
