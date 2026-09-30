package com.longdev.xiaoling.agent

import com.longdev.xiaoling.share.SharedDraftPayload
import java.util.LinkedHashMap

/**
 * 远程 Channel 的第一版只定义受控入站协议，不直接启动 Agent Run。
 *
 * long: 外部消息先变成前台可审阅草稿，避免网络入口绕过会话选择、用户确认和现有工具权限。
 */
data class RemoteChannelEnvelope(
    val channelId: String,
    val messageId: String,
    val senderId: String,
    val conversationKey: String,
    val text: String,
    val receivedAtMillis: Long,
)

data class RemoteChannelDraft(
    val channelId: String,
    val messageId: String,
    val senderId: String,
    val conversationKey: String,
    val payload: SharedDraftPayload,
    val requiresForegroundConfirmation: Boolean = true,
)

enum class RemoteChannelRejectionReason {
    EMPTY_CHANNEL,
    EMPTY_MESSAGE_ID,
    EMPTY_SENDER,
    EMPTY_CONVERSATION,
    EMPTY_TEXT,
    TEXT_TOO_LONG,
    INVALID_TIMESTAMP,
    SENDER_NOT_ALLOWED,
    DUPLICATE_MESSAGE,
    DEDUPE_PERSISTENCE_FAILURE,
}

sealed interface RemoteChannelReceiveResult {
    data class Accepted(val draft: RemoteChannelDraft) : RemoteChannelReceiveResult
    data class Rejected(val reason: RemoteChannelRejectionReason) : RemoteChannelReceiveResult
}

data class RemoteChannelAllowlist(
    val channelId: String,
    val senderIds: Set<String>,
) {
    init {
        require(channelId.isNotBlank()) { "远程 Channel ID 不能为空" }
        require(senderIds.isNotEmpty()) { "远程 Channel 至少需要一个允许的 sender" }
        require(senderIds.none { it.isBlank() }) { "远程 Channel sender 不能为空" }
    }
}

object RemoteChannelPolicy {
    const val MAX_TEXT_CHARS = 20_000
    const val MAX_DEDUPE_ENTRIES = 256

    fun normalizeText(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    fun dedupeKey(envelope: RemoteChannelEnvelope): String =
        "${envelope.channelId.trim()}\u0000${envelope.senderId.trim()}\u0000${envelope.messageId.trim()}"

    fun validate(envelope: RemoteChannelEnvelope): RemoteChannelRejectionReason? {
        val channelId = envelope.channelId.trim()
        if (channelId.isBlank()) return RemoteChannelRejectionReason.EMPTY_CHANNEL
        if (envelope.messageId.trim().isBlank()) return RemoteChannelRejectionReason.EMPTY_MESSAGE_ID
        if (envelope.senderId.trim().isBlank()) return RemoteChannelRejectionReason.EMPTY_SENDER
        if (envelope.conversationKey.trim().isBlank()) return RemoteChannelRejectionReason.EMPTY_CONVERSATION
        val text = normalizeText(envelope.text)
        if (text.isBlank()) return RemoteChannelRejectionReason.EMPTY_TEXT
        if (text.length > MAX_TEXT_CHARS) return RemoteChannelRejectionReason.TEXT_TOO_LONG
        if (envelope.receivedAtMillis <= 0L) return RemoteChannelRejectionReason.INVALID_TIMESTAMP
        return null
    }
}

interface RemoteChannelDedupeStore {
    fun loadKeys(): List<String>

    fun saveKeys(keys: List<String>)
}

class InMemoryRemoteChannelInbox(
    private val allowlist: RemoteChannelAllowlist,
    private val maxDedupeEntries: Int = RemoteChannelPolicy.MAX_DEDUPE_ENTRIES,
    private val dedupeStore: RemoteChannelDedupeStore? = null,
) {
    private val seen = object : LinkedHashMap<String, Unit>(maxDedupeEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean =
            size > maxDedupeEntries
    }

    init {
        require(maxDedupeEntries in 1..RemoteChannelPolicy.MAX_DEDUPE_ENTRIES) {
            "远程 Channel 去重窗口必须在 1 到 ${RemoteChannelPolicy.MAX_DEDUPE_ENTRIES} 之间"
        }
        dedupeStore?.loadKeys()
            ?.asSequence()
            ?.filter(String::isNotBlank)
            ?.toList()
            ?.takeLast(maxDedupeEntries)
            ?.forEach { seen[it] = Unit }
    }

    @Synchronized
    fun receive(envelope: RemoteChannelEnvelope): RemoteChannelReceiveResult {
        RemoteChannelPolicy.validate(envelope)?.let { return RemoteChannelReceiveResult.Rejected(it) }
        if (envelope.channelId.trim() != allowlist.channelId) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.SENDER_NOT_ALLOWED)
        }
        if (envelope.senderId.trim() !in allowlist.senderIds) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.SENDER_NOT_ALLOWED)
        }
        val key = RemoteChannelPolicy.dedupeKey(envelope)
        if (seen.containsKey(key)) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.DUPLICATE_MESSAGE)
        }
        seen[key] = Unit
        val store = dedupeStore
        if (store != null) {
            val persisted = runCatching { store.saveKeys(seen.keys.toList()) }.isSuccess
            if (!persisted) {
                // long: 去重账本写失败时不能先把消息交给前台，否则进程重启后可能再次生成同一草稿；回滚本次内存占位并明确拒绝。
                seen.remove(key)
                return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE)
            }
        }
        return RemoteChannelReceiveResult.Accepted(
            RemoteChannelDraft(
                channelId = allowlist.channelId,
                messageId = envelope.messageId.trim(),
                senderId = envelope.senderId.trim(),
                conversationKey = envelope.conversationKey.trim(),
                // long: Channel 文本只能进入草稿；不携带附件、不附带工具授权，前台确认仍由会话 UI 负责。
                payload = SharedDraftPayload(
                    text = RemoteChannelPolicy.normalizeText(envelope.text),
                    imageUri = null,
                    documentUri = null,
                ),
            ),
        )
    }
}
