package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.retainCurrentKnowledgeReferences
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.prompt.PromptSettings
import kotlinx.coroutines.CancellationException

internal data class PreparedRequestContext(
    val requestMessages: List<RequestMessage>,
    val summary: String,
    val summaryUntilMessageId: String?,
    val summaryUpdatedAt: Long?,
    val summaryModel: String?,
) {
    companion object {
        fun fromConversation(
            conversation: ConversationSession?,
        ): PreparedRequestContext {
            return PreparedRequestContext(
                requestMessages = emptyList(),
                summary = conversation?.summary.orEmpty(),
                summaryUntilMessageId = conversation?.summaryUntilMessageId,
                summaryUpdatedAt = conversation?.summaryUpdatedAt,
                summaryModel = conversation?.summaryModel,
            )
        }
    }
}

internal data class CurrentKnowledgeContext(
    val messages: List<ChatMessage>,
    val removedStaleKnowledgeMessage: Boolean,
)

internal fun List<ChatMessage>.projectCurrentKnowledgeContext(
    currentReferences: Set<KnowledgeReference>,
): CurrentKnowledgeContext {
    var removedStaleKnowledgeMessage = false
    val filtered = mapNotNull { message ->
        val context = message.verifiedAgentContext ?: return@mapNotNull message
        val messageReferences = buildList {
            addAll(context.knowledgeReferences)
            context.toolExecutions.forEach { addAll(it.knowledgeReferences) }
        }
        if (messageReferences.any { it !in currentReferences }) {
            // long: Agent 展示正文可能已经合并知识片段；引用失效时必须移除整条请求消息，Room 审计仍保持原样。
            removedStaleKnowledgeMessage = true
            return@mapNotNull null
        }
        val projectedContext = context.retainCurrentKnowledgeReferences(currentReferences)
        if (projectedContext == null &&
            (context.toolName == "knowledge.search" || context.toolExecutions.any { it.toolName == "knowledge.search" })
        ) {
            removedStaleKnowledgeMessage = true
            return@mapNotNull null
        }
        message.copy(verifiedAgentContext = projectedContext)
    }
    return CurrentKnowledgeContext(filtered, removedStaleKnowledgeMessage)
}

internal class ConversationRequestContextPreparer(
    private val retainCurrentKnowledgeReferences: suspend (List<KnowledgeReference>) -> Set<KnowledgeReference>,
    private val generateSummary: suspend (
        ProviderRequestConfig,
        String,
        List<ChatMessage>,
        PromptSettings,
    ) -> String,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun prepare(
        config: ProviderRequestConfig,
        messages: List<ChatMessage>,
        conversation: ConversationSession?,
        promptSettings: PromptSettings,
    ): PreparedRequestContext {
        val currentKnowledgeContext = messages
            .filter(ChatMessage::isEligibleForConversationContext)
            .retainCurrentKnowledgeReferences()
        val contextMessages = currentKnowledgeContext.messages
        // long: 旧摘要可能已经吸收被禁用、替换或删除的知识正文；一旦发现失效引用，摘要及边界元数据必须一起作废。
        val storedSummary = conversation?.summary.orEmpty().takeUnless {
            currentKnowledgeContext.removedStaleKnowledgeMessage
        }.orEmpty()
        val storedSummaryUntilMessageId = conversation?.summaryUntilMessageId.takeUnless {
            currentKnowledgeContext.removedStaleKnowledgeMessage
        }
        val targetSummaryIndex = contextMessages.size - RECENT_CONTEXT_MESSAGE_LIMIT - 1
        val storedSummaryBoundaryIndex = storedSummaryUntilMessageId
            ?.let { id -> contextMessages.indexOfFirst { it.id == id } }
            ?: -1
        val storedSummaryBoundaryIsCurrent = storedSummary.isBlank() || (
            storedSummaryBoundaryIndex >= 0 &&
                targetSummaryIndex >= 0 &&
                storedSummaryBoundaryIndex <= targetSummaryIndex
        )
        // long: 找不到摘要边界或边界已跑到当前压缩目标之后时，都无法证明增量起点；旧摘要必须作废，避免重复压缩或生成反向区间。
        val reusableSummary = storedSummary.takeIf { storedSummaryBoundaryIsCurrent }.orEmpty()
        val reusableSummaryUntilMessageId = storedSummaryUntilMessageId.takeIf {
            storedSummaryBoundaryIsCurrent && reusableSummary.isNotBlank()
        }
        if (contextMessages.size <= RECENT_CONTEXT_MESSAGE_LIMIT && reusableSummary.isBlank()) {
            return PreparedRequestContext(
                requestMessages = buildRequestMessages(contextMessages, summary = "", config, promptSettings),
                summary = "",
                summaryUntilMessageId = null,
                summaryUpdatedAt = null,
                summaryModel = null,
            )
        }

        val targetSummaryMessage = contextMessages[targetSummaryIndex]
        if (reusableSummary.isNotBlank() && reusableSummaryUntilMessageId == targetSummaryMessage.id) {
            return PreparedRequestContext(
                requestMessages = buildRequestMessages(contextMessages, reusableSummary, config, promptSettings),
                summary = reusableSummary,
                summaryUntilMessageId = reusableSummaryUntilMessageId,
                summaryUpdatedAt = conversation?.summaryUpdatedAt,
                summaryModel = conversation?.summaryModel,
            )
        }
        val messagesToCompress = messagesNeedingCompression(
            contextMessages = contextMessages,
            previousSummaryUntilMessageId = reusableSummaryUntilMessageId,
            targetSummaryMessageId = targetSummaryMessage.id,
        )
        // long: 摘要器只接收最近窗口之前的历史增量，避免普通聊天在每轮请求中重复发送完整历史。
        val summary = runCatching {
            generateSummary(config, reusableSummary, messagesToCompress, promptSettings)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            localFallbackSummary(reusableSummary, messagesToCompress)
        }
        return PreparedRequestContext(
            requestMessages = buildRequestMessages(contextMessages, summary, config, promptSettings),
            summary = summary,
            summaryUntilMessageId = targetSummaryMessage.id,
            summaryUpdatedAt = currentTimeMillis(),
            summaryModel = config.model.trim(),
        )
    }

    private suspend fun List<ChatMessage>.retainCurrentKnowledgeReferences(): CurrentKnowledgeContext {
        val containsKnowledgeExecution = any { message ->
            val context = message.verifiedAgentContext ?: return@any false
            context.toolName == "knowledge.search" || context.toolExecutions.any { it.toolName == "knowledge.search" }
        }
        val references = flatMap { message ->
            val context = message.verifiedAgentContext ?: return@flatMap emptyList()
            buildList {
                addAll(context.knowledgeReferences)
                context.toolExecutions.forEach { addAll(it.knowledgeReferences) }
            }
        }.distinct()
        if (references.isEmpty() && !containsKnowledgeExecution) {
            return CurrentKnowledgeContext(this, removedStaleKnowledgeMessage = false)
        }
        val currentReferences = runCatching {
            retainCurrentKnowledgeReferences(references)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptySet()
        }
        return projectCurrentKnowledgeContext(currentReferences)
    }

    private fun messagesNeedingCompression(
        contextMessages: List<ChatMessage>,
        previousSummaryUntilMessageId: String?,
        targetSummaryMessageId: String,
    ): List<ChatMessage> {
        val targetIndex = contextMessages.indexOfFirst { it.id == targetSummaryMessageId }
        if (targetIndex < 0) return emptyList()
        val previousIndex = previousSummaryUntilMessageId
            ?.let { id -> contextMessages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: -1
        return contextMessages.subList(previousIndex + 1, targetIndex + 1)
    }

    private fun localFallbackSummary(
        existingSummary: String,
        messagesToCompress: List<ChatMessage>,
    ): String {
        return PromptPolicy.localFallbackSummary(
            existingSummary = existingSummary,
            messages = messagesToCompress.map { it.toPromptContextMessage() },
            maxChars = SUMMARY_MAX_CHARS,
        ).trim()
    }

    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        summary: String,
        config: ProviderRequestConfig,
        promptSettings: PromptSettings,
    ): List<RequestMessage> {
        return buildList {
            add(RequestMessage(role = "system", content = PromptPolicy.chatSystemPrompt(promptSettings)))
            if (summary.isNotBlank()) {
                add(
                    RequestMessage(
                        role = "system",
                        content = "以下是较早对话的持续摘要，请在回答当前问题时一并参考：\n$summary",
                    ),
                )
            }
            val recentMessages = if (summary.isBlank() && messages.size <= RECENT_CONTEXT_MESSAGE_LIMIT) {
                messages
            } else {
                messages.takeLast(RECENT_CONTEXT_MESSAGE_LIMIT)
            }
            val olderVerifiedAgentResults = messages
                .dropLast(recentMessages.size)
                .filter { it.origin == MessageOrigin.AGENT_RESULT && it.verifiedAgentContext != null }
                .takeLast(VERIFIED_AGENT_CONTEXT_LIMIT)
            // long: 最近窗口之外只保留带结构化可信上下文的 Agent 结果，普通历史由持续摘要承接。
            (olderVerifiedAgentResults + recentMessages).forEach { message ->
                add(
                    RequestMessage(
                        role = message.role,
                        content = PromptPolicy.historyContent(message.toPromptContextMessage()),
                        // long: 原始附件只属于 Responses 的用户输入，且仅随最近窗口发送；摘要与窗口外可信 Agent 结果不能把附件提升为执行证据。
                        images = message.imagesForRequest(config.apiMode),
                        documents = message.documentsForRequest(config.apiMode),
                    ),
                )
            }
        }
    }

    private fun ChatMessage.toPromptContextMessage() = com.longdev.xiaoling.prompt.PromptContextMessage(
        origin = origin,
        content = text,
        verifiedAgentContext = verifiedAgentContext,
    )

    private companion object {
        const val RECENT_CONTEXT_MESSAGE_LIMIT = 16
        const val VERIFIED_AGENT_CONTEXT_LIMIT = 8
        const val SUMMARY_MAX_CHARS = 4_000
    }
}
