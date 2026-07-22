package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ModelResponseResult
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.network.StreamDeltaUpdate
import com.longdev.xiaoling.prompt.PromptSettings
import com.longdev.xiaoling.storage.StoredConversation
import kotlinx.coroutines.CancellationException

internal data class ConversationPersistenceSnapshot(
    val conversations: List<StoredConversation>,
    val selectedConversationId: String,
    val deletedConversationIds: Set<String>,
)

internal data class ConversationSendRequest(
    val config: ProviderRequestConfig,
    val messages: List<ChatMessage>,
    val conversation: ConversationSession?,
    val promptSettings: PromptSettings,
    val persistenceSnapshot: ConversationPersistenceSnapshot,
    val initialContext: PreparedRequestContext,
)

internal sealed interface ConversationSendEvent {
    data class SnapshotPersisted(
        val deletedConversationIds: Set<String>,
    ) : ConversationSendEvent

    data class ContextPrepared(
        val context: PreparedRequestContext,
    ) : ConversationSendEvent

    data class StreamDelta(
        val update: StreamDeltaUpdate,
    ) : ConversationSendEvent

    data class Completed(
        val context: PreparedRequestContext,
        val response: ModelResponseResult,
    ) : ConversationSendEvent

    data class Cancelled(
        val context: PreparedRequestContext,
    ) : ConversationSendEvent

    data class Failed(
        val context: PreparedRequestContext,
        val error: Throwable,
    ) : ConversationSendEvent
}

internal class ConversationSendCoordinator(
    private val persistSnapshot: suspend (ConversationPersistenceSnapshot) -> Unit,
    private val prepareRequestContext: suspend (
        ProviderRequestConfig,
        List<ChatMessage>,
        ConversationSession?,
        PromptSettings,
    ) -> PreparedRequestContext,
    private val sendRequest: suspend (
        ProviderRequestConfig,
        List<RequestMessage>,
        suspend (StreamDeltaUpdate) -> Unit,
    ) -> ModelResponseResult,
) {
    suspend fun execute(
        request: ConversationSendRequest,
        onEvent: suspend (ConversationSendEvent) -> Unit,
    ) {
        var preparedContext = request.initialContext
        try {
            // long: 用户消息和附件必须先完整落入 Room，随后才能准备上下文并发起网络请求，避免进程退出后只留下上游请求而本地输入不可恢复。
            persistSnapshot(request.persistenceSnapshot)
            onEvent(ConversationSendEvent.SnapshotPersisted(request.persistenceSnapshot.deletedConversationIds))
            preparedContext = prepareRequestContext(
                request.config,
                request.messages,
                request.conversation,
                request.promptSettings,
            )
            onEvent(ConversationSendEvent.ContextPrepared(preparedContext))
            val response = sendRequest(request.config, preparedContext.requestMessages) { update ->
                onEvent(ConversationSendEvent.StreamDelta(update))
            }
            onEvent(ConversationSendEvent.Completed(preparedContext, response))
        } catch (error: CancellationException) {
            // long: 用户停止必须先让 UI 用最近已准备上下文收敛部分回答，再继续传播取消以触发底层 OkHttp Call.cancel()。
            try {
                onEvent(ConversationSendEvent.Cancelled(preparedContext))
            } finally {
                throw error
            }
        } catch (error: Throwable) {
            // long: Room、上下文准备或模型请求任一环节失败时，都以最后已确认的摘要边界收敛；未完成准备时只能沿用初始上下文，不能猜造新摘要。
            onEvent(ConversationSendEvent.Failed(preparedContext, error))
        }
    }
}
