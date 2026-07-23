package com.longdev.xiaoling.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ConversationLoadRequest(
    val conversation: ConversationSession,
    val conversations: List<ConversationSession>,
    val result: OperationResult?,
)

internal sealed interface ConversationLoadEvent {
    data object Loading : ConversationLoadEvent

    data class Loaded(
        val request: ConversationLoadRequest,
        val messages: List<ChatMessage>,
    ) : ConversationLoadEvent

    data class Failed(
        val request: ConversationLoadRequest,
        val error: Throwable,
    ) : ConversationLoadEvent
}

internal class ConversationLoadCoordinator(
    private val scope: CoroutineScope,
    private val loadMessages: suspend (conversationId: String) -> List<ChatMessage>,
) {
    private var activeGeneration = 0L
    private var latestLoadJob: Job? = null

    fun load(
        request: ConversationLoadRequest,
        onEvent: (ConversationLoadEvent) -> Unit,
    ): Job {
        val generation = ++activeGeneration
        latestLoadJob?.cancel()
        val loadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val messages = loadMessages(request.conversation.id)
                // long: 取消是协作式的，底层 Room 查询或测试 loader 仍可能在取消后返回；只有当前选择代次可以投影消息，避免旧会话覆盖新会话。
                if (generation == activeGeneration) {
                    onEvent(ConversationLoadEvent.Loaded(request, messages))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // long: 读取失败同样必须确认仍是当前选择，避免旧删除路径的失败回调回滚新一代删除意图或覆盖当前提示。
                if (generation == activeGeneration) {
                    onEvent(ConversationLoadEvent.Failed(request, error))
                }
            } finally {
                if (generation == activeGeneration) {
                    latestLoadJob = null
                }
            }
        }
        // long: Loading 回调可以立即触发新的会话选择；先登记本次 Job，重入时才能取消正确实例，外层也不会在回调返回后覆盖内层最新 Job。
        latestLoadJob = loadJob
        onEvent(ConversationLoadEvent.Loading)
        loadJob.start()
        return loadJob
    }

    fun cancelPendingLoad() {
        activeGeneration += 1L
        latestLoadJob?.cancel()
        latestLoadJob = null
    }
}
