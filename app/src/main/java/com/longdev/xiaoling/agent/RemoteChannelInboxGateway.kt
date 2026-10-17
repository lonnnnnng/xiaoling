package com.longdev.xiaoling.agent

import org.json.JSONObject
import java.util.UUID

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
    private val pairingRegistry: RemoteChannelPairingRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val authenticator = PublicKeyRemoteChannelAuthenticator(pairingRegistry)
    fun status(): RemoteChannelInboxStatus = synchronized(STATE_LOCK) {
        val inbox = inbox("__inbox_list__", "__inbox_list__")
        if (inbox.isStorageAvailable()) RemoteChannelInboxStatus.Ready(inbox.allPendingDrafts())
        else RemoteChannelInboxStatus.Unavailable
    }

    fun receive(envelope: RemoteChannelEnvelope): RemoteChannelReceiveResult = synchronized(STATE_LOCK) {
        // long: 动态允许表只限定这次消息的视图；真正的渠道/发送方授权由固定的公钥配对验签器逐次读取账本决定。
        inbox(envelope.channelId.trim(), envelope.senderId.trim()).receive(envelope)
    }

    fun discard(draft: RemoteChannelDraft): Boolean = synchronized(STATE_LOCK) {
        inbox(draft.channelId, draft.senderId).discardPending(draft.channelId, draft.senderId, draft.messageId)
    }

    fun batchState(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate): RemoteChannelBatchState = synchronized(STATE_LOCK) {
        val snapshot = loadSnapshot()
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        val endpoint = RemoteChannelBatchProtocol.endpointHash(source)
        snapshot.batchStates.firstOrNull { it.sourceIdentity == identity && it.endpointSha256 == endpoint }?.let { return@synchronized it }
        check(snapshot.batchStates.size < RemoteChannelBatchProtocol.MAX_STATES) { "批次来源已达到上限" }
        val state = RemoteChannelBatchState(identity, endpoint, UUID.randomUUID().toString().replace("-", ""))
        saveSnapshot(snapshot.copy(batchStates = snapshot.batchStates + state))
        state
    }

    fun existingBatchState(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate): RemoteChannelBatchState? = synchronized(STATE_LOCK) {
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        val endpoint = RemoteChannelBatchProtocol.endpointHash(source)
        loadSnapshot().batchStates.firstOrNull { it.sourceIdentity == identity && it.endpointSha256 == endpoint }
    }

    fun receiveBatch(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate, batch: RemoteChannelSignedBatch): RemoteChannelBatchReceiveResult =
        synchronized(STATE_LOCK) {
            val original = loadSnapshot()
            val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
            val endpoint = RemoteChannelBatchProtocol.endpointHash(source)
            val state = original.batchStates.singleOrNull { it.sourceIdentity == identity && it.endpointSha256 == endpoint }
                ?: error("批次来源尚未初始化")
            require(state.pendingAck == null) { "旧批次 ACK 尚未确认" }
            require(pairingRegistry.active(peer.channelId, peer.senderId, peer.keyId) == peer) { "配对身份已停用或变化" }
            RemoteChannelBatchProtocol.verifyBatch(batch, peer, state, clock())

            // long: 用隔离的内存事务演算整批入箱；任一验签、nonce 或容量拒绝时不写正文，也不推进游标。
            var staged = original
            val stagingStore = object : RemoteChannelInboxStateStore {
                override fun loadSnapshot() = staged
                override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { staged = snapshot }
            }
            val stagingInbox = InMemoryRemoteChannelInbox(RemoteChannelAllowlist(peer.channelId, setOf(peer.senderId)),
                authenticator = authenticator, clock = clock, inboxStateStore = stagingStore)
            var accepted = 0
            var duplicates = 0
            batch.messages.forEach { envelope ->
                require(envelope.channelId == peer.channelId && envelope.senderId == peer.senderId && envelope.keyId == peer.keyId) {
                    "批次包含其他身份消息"
                }
                when (val result = stagingInbox.receive(envelope)) {
                    is RemoteChannelReceiveResult.Accepted -> accepted += 1
                    is RemoteChannelReceiveResult.Rejected -> {
                        // long: nonce 碰撞不能证明新 messageId 曾经入箱，只有持久消息去重身份存在才计为可 ACK 的重复。
                        require(result.reason == RemoteChannelRejectionReason.DUPLICATE_MESSAGE &&
                            RemoteChannelPolicy.dedupeKey(envelope) in staged.dedupeKeys) { "批次消息拒绝：${result.reason}" }
                        duplicates += 1
                    }
                }
            }
            val nextState = state.copy(cursor = batch.nextCursor, pendingAck = RemoteChannelBatchProtocol.receipt(batch))
            require(pairingRegistry.active(peer.channelId, peer.senderId, peer.keyId) == peer) { "配对身份已停用或变化" }
            saveSnapshot(staged.copy(batchStates = staged.batchStates.map { if (it == state) nextState else it }))
            RemoteChannelBatchReceiveResult(accepted, duplicates)
        }

    fun acknowledgeBatch(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate, receipt: RemoteChannelBatchReceipt) =
        synchronized(STATE_LOCK) {
            val snapshot = loadSnapshot()
            val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
            val endpoint = RemoteChannelBatchProtocol.endpointHash(source)
            val state = snapshot.batchStates.single { it.sourceIdentity == identity && it.endpointSha256 == endpoint }
            // long: 迟到回执只能清除它对应的 outbox，不能清除新批次或另一 URL 的待确认回执。
            require(state.pendingAck == receipt) { "待 ACK 批次已变化" }
            saveSnapshot(snapshot.copy(batchStates = snapshot.batchStates.map { if (it == state) state.copy(pendingAck = null) else it }))
        }

    private fun loadSnapshot(): RemoteChannelInboxSnapshot = try {
        store.loadSnapshot() ?: RemoteChannelInboxSnapshot(emptyList(), emptyList())
    } catch (error: Exception) { throw IllegalStateException("加密收件箱不可用", error) }

    private fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) {
        try { store.saveSnapshot(snapshot) } catch (error: Exception) { throw IllegalStateException("加密收件箱无法提交", error) }
    }

    private companion object {
        // long: 同进程重建 Gateway 也共用存储锁，防止手动导入、过期清理和批次提交互相覆盖快照。
        val STATE_LOCK = Any()
    }

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
