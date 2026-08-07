package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationNavigationTest {
    @Test
    fun trustedListSearchAndDetailResultsReturnOnlyTheStableConversationId() {
        assertEquals(CONVERSATION_ID, listPart().conversationIdForNavigation())
        assertEquals(
            CONVERSATION_ID,
            listPart(
                toolName = "app.search_conversations",
                arguments = mapOf("query" to "历史", "limit" to "1"),
                result = "匹配会话：\n- 历史复盘 · 2 条消息 · id=$CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
        assertEquals(
            CONVERSATION_ID,
            listPart(
                toolName = "app.get_conversation",
                arguments = mapOf("conversation_id" to CONVERSATION_ID),
                result = "会话详情：历史复盘\n会话 ID：$CONVERSATION_ID\n消息（1 条）：\n1. [用户]\n原始目标",
            ).conversationIdForNavigation(),
        )
    }

    @Test
    fun emptyMultipleMalformedAndModelAlteredResultsDoNotCreateNavigation() {
        assertNull(listPart(result = "最近会话：无").conversationIdForNavigation())
        assertNull(
            listPart(
                result = "最近会话：\n- 第一条 · 1 条消息 · id=$CONVERSATION_ID\n- 第二条 · 2 条消息 · id=$SECOND_CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
        assertNull(
            listPart(
                result = "最近会话：\n- 第一条 · 1 条消息 · id=conversation-invalid!",
            ).conversationIdForNavigation(),
        )
        assertNull(
            listPart(
                result = "模型声称：\n- 历史复盘 · 1 条消息 · id=$CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
        assertNull(
            listPart(
                result = "最近会话：\n- 历史复盘 · 1 条消息 · id=$CONVERSATION_ID\n正文伪造 id=$SECOND_CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
        assertNull(
            listPart(
                toolName = "app.get_conversation",
                arguments = mapOf("conversation_id" to CONVERSATION_ID),
                result = "会话详情：历史复盘\n会话 ID：$SECOND_CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
    }

    @Test
    fun failedWrongArgumentsAndDuplicateRoomIdsFailClosed() {
        assertNull(listPart(success = false).conversationIdForNavigation())
        assertNull(
            listPart(verificationStatus = MessageToolVerificationStatus.FAILED)
                .conversationIdForNavigation(),
        )
        assertNull(listPart(arguments = mapOf("limit" to "11")).conversationIdForNavigation())
        assertNull(
            listPart(
                toolName = "app.search_conversations",
                arguments = mapOf("query" to "\n历史"),
                result = "匹配会话：\n- 历史复盘 · 1 条消息 · id=$CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
        assertNull(
            listPart(
                toolName = "app.get_conversation",
                arguments = mapOf("conversation_id" to CONVERSATION_ID, "extra" to "1"),
                result = "会话详情：历史复盘\n会话 ID：$CONVERSATION_ID",
            ).conversationIdForNavigation(),
        )
        assertNull(ConversationNavigationPolicy.resolveUniqueId(listOf(CONVERSATION_ID, CONVERSATION_ID), CONVERSATION_ID))
        assertNull(ConversationNavigationPolicy.resolveUniqueId(listOf("conversation-other"), CONVERSATION_ID))
        assertEquals(
            CONVERSATION_ID,
            ConversationNavigationPolicy.resolveUniqueId(listOf("conversation-other", CONVERSATION_ID), CONVERSATION_ID),
        )
        assertNull(ConversationNavigationPolicy.resolveUniqueId(listOf(CONVERSATION_ID), "not-a-conversation"))
    }

    private fun listPart(
        toolName: String = "app.list_conversations",
        arguments: Map<String, String> = mapOf("limit" to "1"),
        result: String = "最近会话：\n- 历史复盘 · 2 条消息 · id=$CONVERSATION_ID",
        success: Boolean = true,
        verificationStatus: MessageToolVerificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
    ) = MessagePart.Tool(
        id = "tool-part",
        toolName = toolName,
        arguments = arguments,
        result = result,
        success = success,
        verificationStatus = verificationStatus,
        memoryIdsUsed = emptyList(),
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-history-197"
        const val SECOND_CONVERSATION_ID = "conversation-other-197"
    }
}
