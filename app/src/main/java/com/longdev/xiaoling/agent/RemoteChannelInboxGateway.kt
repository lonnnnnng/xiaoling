package com.longdev.xiaoling.agent

import org.json.JSONObject

sealed interface RemoteChannelInboxStatus {
    data class Ready(val pending: List<RemoteChannelPendingDraft>) : RemoteChannelInboxStatus
    data object Unavailable : RemoteChannelInboxStatus
}

/**
 * long: 每次操作重建收件箱视图，避免设置页、后续接收器和进程重建分别持有过期的去重快照。
 * 单份加密快照跨 Channel 共用 8 条容量；实际接收仍必须先通过配对公钥验签。
 */
class RemoteChannelInboxGateway(
    private val store: RemoteChannelInboxStateStore,
    pairingRegistry: RemoteChannelPairingRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val authenticator = PublicKeyRemoteChannelAuthenticator(pairingRegistry)
    @Synchronized
    fun status(): RemoteChannelInboxStatus {
        val inbox = inbox("__inbox_list__", "__inbox_list__")
        return if (inbox.isStorageAvailable()) RemoteChannelInboxStatus.Ready(inbox.allPendingDrafts())
        else RemoteChannelInboxStatus.Unavailable
    }

    @Synchronized
    fun receive(envelope: RemoteChannelEnvelope): RemoteChannelReceiveResult =
        // long: 动态允许表只限定这次消息的视图；真正的渠道/发送方授权由固定的公钥配对验签器逐次读取账本决定。
        inbox(envelope.channelId.trim(), envelope.senderId.trim()).receive(envelope)

    @Synchronized
    fun discard(draft: RemoteChannelDraft): Boolean =
        inbox(draft.channelId, draft.senderId).discardPending(draft.channelId, draft.senderId, draft.messageId)

    private fun inbox(channelId: String, senderId: String): InMemoryRemoteChannelInbox =
        InMemoryRemoteChannelInbox(
            allowlist = RemoteChannelAllowlist(channelId.ifBlank { "__invalid_channel__" },
                setOf(senderId.ifBlank { "__invalid_sender__" })),
            authenticator = authenticator,
            clock = clock,
            inboxStateStore = store,
        )
}

/** long: 手动导入只接受固定九字段的签名 envelope；拒绝附件、隐式默认值和扩展字段。 */
object RemoteChannelSignedEnvelopeCodec {
    const val MAX_JSON_BYTES = 64 * 1024
    private val FIELDS = setOf(
        "channelId", "messageId", "senderId", "conversationKey", "text",
        "receivedAtMillis", "nonce", "keyId", "signature",
    )

    fun decode(raw: String): RemoteChannelEnvelope {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "签名消息过大" }
        val json = JSONObject(raw)
        val keys = json.keys().asSequence().toSet()
        require(keys == FIELDS) { "签名消息字段不完整或含有额外字段" }
        fun string(name: String): String = (json.opt(name) as? String)
            ?: throw IllegalArgumentException("$name 必须是字符串")
        val timestamp = json.opt("receivedAtMillis")
        require(timestamp is Int || timestamp is Long) { "receivedAtMillis 必须是整数" }
        return RemoteChannelEnvelope(
            channelId = string("channelId"),
            messageId = string("messageId"),
            senderId = string("senderId"),
            conversationKey = string("conversationKey"),
            text = string("text"),
            receivedAtMillis = (timestamp as Number).toLong(),
            nonce = string("nonce"),
            keyId = string("keyId"),
            signature = string("signature"),
        )
    }
}
