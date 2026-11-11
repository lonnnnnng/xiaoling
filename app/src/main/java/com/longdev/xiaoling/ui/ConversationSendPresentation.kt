package com.longdev.xiaoling.ui

import com.longdev.xiaoling.genui.GenUiActionLeases
import com.longdev.xiaoling.model.ModelReasoningSummary
import com.longdev.xiaoling.model.ProviderMessagePartPolicy
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import com.longdev.xiaoling.network.StreamDeltaUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ConversationSendPresentation(
    private val scope: CoroutineScope,
    private val readState: () -> XiaoLingUiState,
    private val writeState: (XiaoLingUiState) -> Unit,
    private val genUiLeases: GenUiActionLeases,
    private val onCompleted: suspend (String, String) -> Unit,
    private val saveConversationSelection: () -> Unit,
    private val saveCurrentProfileSelection: () -> Unit,
) {
    private var uiState: XiaoLingUiState
        get() = readState()
        set(value) = writeState(value)
    private var activeMeta: MessageMeta? = null
    private var streamingThrottleJob: Job? = null
    private var pendingStreamingUpdate: StreamDeltaUpdate? = null

    fun begin(meta: MessageMeta) { activeMeta = meta }

    fun cancelBeforeNavigation() {
        val meta = activeMeta ?: return
        // long: 必须在选择协调器捕获会话集合之前收敛旧正文；只拒收迟到回调会让旧快照永久保留接收中状态。
        flushStreamingAssistant(meta)
        activeMeta = null
        uiState = uiState.withUpdatedCurrentConversation(
            messages = uiState.chatMessages.withCancelledGeneration(meta),
            summary = uiState.conversationSummary,
        ).copy(sendingMessage = false, result = null)
        saveConversationSelection()
    }

    suspend fun handle(
        event: ConversationSendEvent,
        baseMeta: MessageMeta,
        userMessage: String,
        messagesWithUser: List<ChatMessage>,
    ) {
        // long: 发送身份由宿主校验；这里集中收敛正文、终态和流式缓冲，让导航取消与网络结束共享同一条消息处理路径。
        when (event) {
            is ConversationSendEvent.SnapshotPersisted -> {
                // long: 该事件只标记发送前快照已经提交；显式删除 ID 的成功确认由同一个持久化协调器原子管理，ViewModel 不再维护第二份状态。
            }

            is ConversationSendEvent.ContextPrepared -> {
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = messagesWithUser,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(result = null)
            }

            is ConversationSendEvent.StreamDelta -> {
                withContext(Dispatchers.Main.immediate) {
                    scheduleStreamingAssistant(event.update, baseMeta)
                }
            }

            is ConversationSendEvent.Completed -> {
                flushStreamingAssistant(baseMeta)
                activeMeta = null
                val response = event.response
                val finalMessages = uiState.chatMessages.upsertLastAssistant(
                    text = response.responseText,
                    reasoningSummaries = response.reasoningSummaries,
                    meta = baseMeta.copy(
                        requestUrl = response.requestUrl,
                        firstTokenLatencyMs = response.firstTokenLatencyMs,
                        latencyMs = response.latencyMs,
                    ),
                )
                // long: 完整普通响应只在这个发送完成事件签发一次；持久正文恢复后默认没有执行引用。
                val completed = finalMessages.lastOrNull { it.role == "assistant" }
                completed?.takeIf { response.actionCompletionConfirmed }
                    ?.let { genUiLeases.issue(uiState.selectedConversationId, it.id, it.text) }
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = finalMessages,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                        genUiActionLeases = genUiLeases.available(uiState.selectedConversationId),
                    )
                // long: 只有模型响应完整收敛后才生成长期记忆候选；取消或断流正文都不能成为新的跨会话事实来源。
                onCompleted(userMessage, uiState.selectedConversationId)
                saveConversationSelection()
                saveCurrentProfileSelection()
            }

            is ConversationSendEvent.Cancelled -> {
                flushStreamingAssistant(baseMeta)
                activeMeta = null
                val stoppedMessages = uiState.chatMessages.withCancelledGeneration(baseMeta)
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = stoppedMessages,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                saveConversationSelection()
            }

            is ConversationSendEvent.Failed -> {
                // long: 先强制刷出已收到的最后一个 delta，再将其标记为不完整并追加错误气泡，避免网络失败把用户已经看到的正文静默删除。
                flushStreamingAssistant(baseMeta)
                activeMeta = null
                val failure = event.error as? ApiFailure
                val failureKind = failure?.kind?.title ?: FailureKind.UNKNOWN.title
                val failureMessage = event.error.message ?: "未知错误"
                val failedMessages = uiState.chatMessages.withFailedStreamingGeneration(
                    baseMeta = baseMeta,
                    errorKind = failureKind,
                    errorMessage = failureMessage,
                ) + ChatMessage(
                    role = "error",
                    text = event.error.toConversationErrorText(),
                    createdAt = System.currentTimeMillis(),
                    meta = baseMeta.copy(
                        finishReason = "failed",
                        errorKind = failureKind,
                        errorMessage = failureMessage,
                    ),
                )
                uiState = uiState
                    .withUpdatedCurrentConversation(
                        messages = failedMessages,
                        summary = event.context.summary,
                        summaryUntilMessageId = event.context.summaryUntilMessageId,
                        summaryUpdatedAt = event.context.summaryUpdatedAt,
                        summaryModel = event.context.summaryModel,
                    )
                    .copy(
                        sendingMessage = false,
                        result = null,
                    )
                saveConversationSelection()
            }
        }
    }

    private fun Throwable.toConversationErrorText(): String {
        val failure = this as? ApiFailure
        val title = failure?.kind?.title ?: FailureKind.UNKNOWN.title
        val detail = message ?: "未知错误"
        return "$title\n$detail"
    }

    private fun scheduleStreamingAssistant(update: StreamDeltaUpdate, baseMeta: MessageMeta) {
        pendingStreamingUpdate = update
        if (streamingThrottleJob?.isActive == true) return
        // long: SSE delta 可能按 token 高频到达；UI 只保留最新累计文本并按 30ms 合并刷新，避免 Markdown 每个 token 都触发重组。
        streamingThrottleJob = scope.launch {
            delay(30L)
            flushStreamingAssistant(baseMeta)
        }
    }

    private fun flushStreamingAssistant(baseMeta: MessageMeta) {
        val update = pendingStreamingUpdate ?: return
        pendingStreamingUpdate = null
        streamingThrottleJob?.cancel()
        streamingThrottleJob = null
        val updatedMessages = uiState.chatMessages.upsertLastAssistant(
            text = update.accumulatedText,
            meta = baseMeta.copy(firstTokenLatencyMs = update.firstTokenLatencyMs),
        )
        // long: 流式期间只刷新内存 UI，不在这里写 SharedPreferences；最终成功或失败时再统一持久化完整会话。
        uiState = uiState
            .withUpdatedCurrentConversation(
                messages = updatedMessages,
                summary = uiState.conversationSummary,
            )
            .copy(result = null)
    }

    private fun List<ChatMessage>.upsertLastAssistant(
        text: String,
        meta: MessageMeta?,
        reasoningSummaries: List<ModelReasoningSummary> = emptyList(),
    ): List<ChatMessage> {
        val last = lastOrNull()
        return if (last?.role == "assistant") {
            dropLast(1) + last.copy(
                text = text,
                meta = meta,
                parts = ProviderMessagePartPolicy.fromResponse(last.id, text, reasoningSummaries),
            )
        } else {
            val message = ChatMessage(
                role = "assistant",
                text = text,
                createdAt = System.currentTimeMillis(),
                meta = meta,
            )
            this + message.copy(
                parts = ProviderMessagePartPolicy.fromResponse(message.id, text, reasoningSummaries),
            )
        }
    }

    private fun List<ChatMessage>.withCancelledGeneration(baseMeta: MessageMeta): List<ChatMessage> {
        val last = lastOrNull()
        return if (last?.role == "assistant") {
            dropLast(1) + last.copy(
                meta = (last.meta ?: baseMeta).copy(
                    finishReason = "cancelled",
                    errorKind = "已取消",
                    errorMessage = "用户停止生成",
                ),
            )
        } else {
            this + ChatMessage(
                role = "error",
                text = "已停止生成",
                createdAt = System.currentTimeMillis(),
                meta = baseMeta.copy(
                    finishReason = "cancelled",
                    errorKind = "已取消",
                    errorMessage = "用户停止生成",
                ),
            )
        }
    }

}
