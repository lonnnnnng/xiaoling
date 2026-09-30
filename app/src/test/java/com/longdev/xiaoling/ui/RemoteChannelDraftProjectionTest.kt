package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.RemoteChannelDraft
import com.longdev.xiaoling.share.SharedDraftImport
import com.longdev.xiaoling.share.SharedDraftPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChannelDraftProjectionTest {
    @Test
    fun remoteDraftReusesSharedDraftPayloadWithoutAddingExecutionPermission() {
        val draft = RemoteChannelDraft(
            channelId = "telegram",
            messageId = "42",
            senderId = "user-1",
            conversationKey = "chat-1",
            payload = SharedDraftPayload("hello", null),
        )

        val projected = draft.toSharedDraftImport()
        assertTrue(projected is SharedDraftImport.Accepted)
        assertEquals(SharedDraftPayload("hello", null), (projected as SharedDraftImport.Accepted).payload)
    }
}
