package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

object AgentMessagePartPolicy {
    fun resolve(
        messageId: String,
        text: String,
        origin: MessageOrigin,
        verifiedContext: VerifiedAgentContext?,
        storedParts: List<MessagePart>,
    ): List<MessagePart> {
        val textPart = MessagePart.Text(id = "$messageId-text", text = text)
        if (origin == MessageOrigin.USER) {
            val attachmentParts = storedParts.filter { it is MessagePart.Image || it is MessagePart.Document }
            // long: USER 附件是一次发送的唯一输入事实；Room 中出现重复或 Image/Document 混合说明持久化边界已损坏，拒绝恢复以免静默丢失用户原始材料。
            require(attachmentParts.size <= 1) { "用户消息只能携带一个图片或文档附件" }
            val projected = attachmentParts + textPart
            val safeStoredParts = storedParts.filter {
                it is MessagePart.Image || it is MessagePart.Document || it is MessagePart.Text
            }
            // long: 用户附件是对话输入事实，不是工具执行证据；保留唯一附件并剔除任何伪造 Tool/Reasoning part。
            return safeStoredParts.takeIf { it.hasSameContentAs(projected) } ?: projected
        }
        // long: 普通模型文本即使伪造了工具完成文案或异常携带可信上下文，也不能进入 Tool part；只有应用写入的 Agent 结果可投影执行事实。
        if (origin != MessageOrigin.AGENT_RESULT || verifiedContext == null) {
            val reasoningParts = if (origin == MessageOrigin.ORDINARY_ASSISTANT) {
                storedParts.filterIsInstance<MessagePart.Reasoning>()
                    .filter { it.text.isNotBlank() && it.summaryIndex >= 0 }
                    .distinctBy { it.providerItemId to it.summaryIndex }
            } else {
                emptyList()
            }
            val projected = reasoningParts + textPart
            val safeStoredParts = storedParts.filter { part ->
                part is MessagePart.Text || part in reasoningParts
            }
            return safeStoredParts.takeIf { it.hasSameContentAs(projected) } ?: projected
        }
        val projected = listOf(textPart) + verifiedContext.effectiveExecutions().mapIndexed { index, execution ->
            MessagePart.Tool(
                id = "$messageId-tool-$index",
                toolName = execution.toolName,
                arguments = execution.arguments.toSortedMap(),
                result = execution.rawResult,
                success = execution.success,
                verificationStatus = execution.verificationStatus.toMessageStatus(),
                memoryIdsUsed = execution.memoryIdsUsed.distinct(),
                knowledgeReferences = execution.knowledgeReferences.distinct(),
            )
        }
        // long: Room 中的 part ID 用于后续分支、重生成和局部更新；只有内容仍与可信 Agent 上下文逐项一致时才保留，漂移时回退重新投影。
        return storedParts.takeIf { it.hasSameContentAs(projected) } ?: projected
    }

    private fun VerifiedAgentContext.effectiveExecutions(): List<VerifiedToolExecution> {
        return toolExecutions.ifEmpty {
            listOf(
                VerifiedToolExecution(
                    toolName = toolName,
                    arguments = arguments,
                    success = success,
                    verificationStatus = verificationStatus,
                    rawResult = rawResult,
                    memoryIdsUsed = memoryIdsUsed,
                    knowledgeReferences = knowledgeReferences,
                ),
            )
        }
    }

    private fun AgentVerificationStatus.toMessageStatus(): MessageToolVerificationStatus = when (this) {
        AgentVerificationStatus.VERIFIED -> MessageToolVerificationStatus.VERIFIED
        AgentVerificationStatus.FAILED -> MessageToolVerificationStatus.FAILED
        AgentVerificationStatus.READABLE_ONLY -> MessageToolVerificationStatus.READABLE_ONLY
    }

    private fun List<MessagePart>.hasSameContentAs(expected: List<MessagePart>): Boolean {
        if (size != expected.size) return false
        return zip(expected).all { (stored, projected) ->
            when {
                stored is MessagePart.Text && projected is MessagePart.Text -> stored.text == projected.text
                stored is MessagePart.Reasoning && projected is MessagePart.Reasoning ->
                    stored.copy(id = projected.id) == projected
                stored is MessagePart.Image && projected is MessagePart.Image ->
                    stored.copy(id = projected.id) == projected
                stored is MessagePart.Document && projected is MessagePart.Document ->
                    stored.copy(id = projected.id) == projected
                stored is MessagePart.Tool && projected is MessagePart.Tool -> stored.copy(id = projected.id) == projected
                else -> false
            }
        }
    }
}
