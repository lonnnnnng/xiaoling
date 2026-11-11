package com.longdev.xiaoling.ui

import com.longdev.xiaoling.genui.GenUiActionLeases
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import com.longdev.xiaoling.network.StreamDeltaUpdate
import com.longdev.xiaoling.ui.conversation.isStreamingInProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationSendPresentationTest {
    @Test fun navigationSettlesBufferedAndVisibleDeltasBeforeSnapshotAndRejectsOldOwner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (flushBeforeNavigation in listOf(false, true)) {
                var state = initialState()
                var saved: XiaoLingUiState? = null
                var memoryCandidates = 0
                val presentation = ConversationSendPresentation(this, { state }, { state = it }, GenUiActionLeases(),
                    { _, _ -> memoryCandidates++ }, { saved = state }, {})
                val scope = ConversationSendScope()
                val identity = scope.begin("a")
                val meta = MessageMeta(streaming = true)
                presentation.begin(meta)
                presentation.handle(ConversationSendEvent.StreamDelta(StreamDeltaUpdate("局部", "局部", 20L)), meta, "问题", state.chatMessages)
                if (flushBeforeNavigation) { advanceTimeBy(31); advanceUntilIdle() }
                scope.invalidate()
                presentation.cancelBeforeNavigation()
                val selection = state.planOpenNewConversation { 30L }
                state = state.withImmediateConversationSelection(selection, null, null)
                advanceUntilIdle()
                val old = state.conversations.single { it.id == "a" }.messages.last()
                assertEquals("局部", old.text)
                assertEquals("cancelled", old.meta?.finishReason)
                assertFalse(old.isStreamingInProgress())
                assertFalse(old.isEligibleForConversationContext())
                assertEquals(old, saved!!.chatMessages.last())
                assertTrue(state.chatMessages.isEmpty())
                assertFalse(scope.accepts(identity, "a"))
                assertEquals(0, memoryCandidates)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedStreamRetainsDeliveredTextWithoutMemoryCandidateOrActionLease() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var state = initialState()
            var memoryCandidates = 0
            val leases = GenUiActionLeases()
            val presentation = ConversationSendPresentation(this, { state }, { state = it }, leases,
                { _, _ -> memoryCandidates++ }, {}, {})
            val meta = MessageMeta(streaming = true)
            presentation.begin(meta)
            presentation.handle(ConversationSendEvent.StreamDelta(StreamDeltaUpdate("局部", "局部", 20L)), meta, "问题", state.chatMessages)
            presentation.handle(ConversationSendEvent.Failed(PreparedRequestContext.fromConversation(state.conversations.single()),
                ApiFailure(FailureKind.RESPONSE, "服务端报告生成失败")), meta, "问题", state.chatMessages)
            advanceUntilIdle()
            val partial = state.chatMessages.single { it.role == "assistant" }
            assertEquals("局部", partial.text)
            assertFalse(partial.isEligibleForConversationContext())
            assertFalse(partial.isStreamingInProgress())
            assertFalse(state.sendingMessage)
            assertEquals(0, memoryCandidates)
            assertTrue(leases.available("a").isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    private fun initialState(): XiaoLingUiState {
        val messages = listOf(ChatMessage(role = "user", text = "问题", createdAt = 1L))
        val session = ConversationSession("a", "问题", "", null, null, null, messages, 1L, 1L)
        return XiaoLingUiState(selectedConversationId = "a", conversations = listOf(session), chatMessages = messages, sendingMessage = true)
    }
}
