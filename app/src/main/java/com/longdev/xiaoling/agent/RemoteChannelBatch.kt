package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.remote.*
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

typealias RemoteChannelSignedBatch = com.longdev.xiaoling.shared.remote.SharedRemoteChannelSignedBatch

data class RemoteChannelBatchReceipt(
    val batchId: String,
    val digestSha256: String,
    val nextCursor: String?,
    val messageIds: List<String>,
)

/** long: 游标与待 ACK 回执和正文共享加密快照；接收端 ID 只隔离送达队列，不赋予远程执行权限。 */
data class RemoteChannelBatchState(
    val sourceIdentity: String,
    val endpointSha256: String,
    val receiverId: String,
    val cursor: String? = null,
    val pendingAck: RemoteChannelBatchReceipt? = null,
)

data class RemoteChannelBatchReceiveResult(val acceptedCount: Int, val duplicateCount: Int)

sealed interface RemoteChannelPullSyncResult {
    data class Single(val result: RemoteChannelReceiveResult) : RemoteChannelPullSyncResult
    data class Batch(val acceptedCount: Int, val duplicateCount: Int, val ackPending: Boolean) : RemoteChannelPullSyncResult
}

sealed interface RemoteChannelPullResponse {
    data class Single(val envelope: RemoteChannelEnvelope) : RemoteChannelPullResponse
    data class Batch(val batch: RemoteChannelSignedBatch) : RemoteChannelPullResponse
}

/** long: 外层签名也覆盖游标和消息签名，发送方不能用已验签正文搭配未授权的游标跳过后续消息。 */
object RemoteChannelBatchProtocol {
    const val VERSION = SharedRemoteChannelWireCodec.VERSION
    const val MAX_MESSAGES = SharedRemoteChannelWireCodec.MAX_MESSAGES
    const val MAX_STATES = 32
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
    private val RECEIVER_ID = Regex("[0-9a-f]{32}")
    private val HASH = Regex("[0-9a-f]{64}")

    fun endpointHash(source: RemoteChannelPullSource): String = sha256(source.url.toByteArray(Charsets.UTF_8))

    fun validateState(state: RemoteChannelBatchState) {
        RemoteChannelPullReliabilityPolicy.validate(RemoteChannelPullCheckpoint(state.sourceIdentity))
        require(HASH.matches(state.endpointSha256) && RECEIVER_ID.matches(state.receiverId)) { "批次来源状态无效" }
        cursor(state.cursor)
        state.pendingAck?.let { receipt ->
            require(ID.matches(receipt.batchId) && HASH.matches(receipt.digestSha256)) { "批次回执无效" }
            cursor(receipt.nextCursor)
            require(receipt.nextCursor == state.cursor && receipt.messageIds.size <= MAX_MESSAGES &&
                receipt.messageIds.distinct().size == receipt.messageIds.size && receipt.messageIds.all {
                    it.length in 1..200 && it == it.trim() && '\u0000' !in it
                }) { "批次回执消息或游标无效" }
        }
    }

    fun decodeResponse(raw: String): RemoteChannelPullResponse = when (val value = SharedRemoteChannelWireCodec.decodeResponse(raw)) {
        is SharedRemoteChannelPullResponse.Single -> RemoteChannelPullResponse.Single(value.envelope)
        is SharedRemoteChannelPullResponse.Batch -> RemoteChannelPullResponse.Batch(value.batch)
    }

    fun validateBatch(batch: RemoteChannelSignedBatch) = SharedRemoteChannelWireCodec.validateBatch(batch)

    fun verifyBatch(batch: RemoteChannelSignedBatch, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState, now: Long) {
        validateBatch(batch)
        require(batch.channelId == peer.channelId && batch.senderId == peer.senderId && batch.keyId == peer.keyId &&
            batch.receiverId == state.receiverId && batch.requestCursor == state.cursor) { "批次身份或请求游标不匹配" }
        require(batch.issuedAtMillis >= now - RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS &&
            batch.issuedAtMillis <= now + RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS && now < batch.expiresAtMillis) {
            "批次已过期或尚未生效"
        }
        verifySignature(peer, canonicalBatchBytes(batch), batch.signature)
    }

    fun canonicalBatchBytes(batch: RemoteChannelSignedBatch): ByteArray = SharedRemoteChannelWireCodec.canonicalBatchBytes(batch)

    fun receipt(batch: RemoteChannelSignedBatch) = RemoteChannelBatchReceipt(
        batch.batchId, sha256(canonicalBatchBytes(batch)), batch.nextCursor, batch.messages.map { it.messageId.trim() },
    )

    fun ackRequest(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): String {
        validateState(state)
        val receipt = state.pendingAck ?: error("没有待 ACK 批次")
        return SharedRemoteChannelWireCodec.encodeAckRequest(ackBinding(peer, state), receipt.messageIds)
    }

    fun verifyAck(raw: String, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState) {
        val response = SharedRemoteChannelWireCodec.decodeAcknowledgement(raw)
        require(response.binding == ackBinding(peer, state)) { "ACK 回执绑定不匹配" }
        verifySignature(peer, canonicalAckBytes(peer, state), response.signature)
    }

    fun canonicalAckBytes(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): ByteArray =
        SharedRemoteChannelWireCodec.canonicalAckBytes(ackBinding(peer, state))

    private fun ackBinding(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): SharedRemoteChannelAckBinding {
        val receipt = state.pendingAck ?: error("没有待 ACK 批次")
        return SharedRemoteChannelAckBinding(peer.channelId, peer.senderId, peer.keyId, state.receiverId,
            receipt.batchId, receipt.digestSha256, receipt.nextCursor)
    }

    fun ackFields(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState, operation: String): JSONObject {
        val receipt = state.pendingAck ?: error("没有待 ACK 批次")
        return JSONObject().put("protocolVersion", VERSION).put("operation", operation)
            .put("channelId", peer.channelId).put("senderId", peer.senderId).put("keyId", peer.keyId)
            .put("receiverId", state.receiverId).put("batchId", receipt.batchId)
            .put("batchDigestSha256", receipt.digestSha256).put("nextCursor", receipt.nextCursor ?: JSONObject.NULL)
    }

    private fun verifySignature(peer: RemoteChannelPeerCandidate, content: ByteArray, raw: String) {
        require(raw.length in 1..128) { "批次或 ACK 签名无效" }
        val signature = Base64.getUrlDecoder().decode(raw)
        require(signature.size in 64..80) { "批次或 ACK 签名无效" }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(peer.publicKeySpkiBase64)),
        )
        require(runCatching { Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey); update(content); verify(signature)
        } }.getOrDefault(false)) { "批次或 ACK 签名无效" }
    }

    private fun cursor(value: String?) {
        require(value == null || (value.isNotBlank() && value.length <= RemoteChannelPullReliabilityPolicy.MAX_CURSOR_CHARS && '\u0000' !in value)) {
            "批次游标无效"
        }
    }
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    internal fun keys(json: JSONObject, expected: Set<String>) { require(json.keys().asSequence().toSet() == expected) { "批次字段无效" } }
    internal fun string(json: JSONObject, key: String): String = json.opt(key) as? String ?: throw IllegalArgumentException("批次 $key 必须是字符串")
    internal fun nullableString(json: JSONObject, key: String): String? = if (json.isNull(key)) null else string(json, key)
    internal fun integer(json: JSONObject, key: String): Long {
        val value = json.opt(key); require(value is Int || value is Long) { "批次 $key 必须是整数" }; return (value as Number).toLong()
    }
}

/** long: v2 加密快照内的批次状态严格编解码，损坏 outbox 不能被当成无需 ACK 而继续前进。 */
object RemoteChannelBatchStateCodec {
    fun encode(states: List<RemoteChannelBatchState>): JSONArray {
        require(states.size <= RemoteChannelBatchProtocol.MAX_STATES && states.map { it.sourceIdentity to it.endpointSha256 }.distinct().size == states.size) {
            "批次来源过多或重复"
        }
        return JSONArray().apply { states.forEach { state ->
            RemoteChannelBatchProtocol.validateState(state)
            put(JSONObject().put("sourceIdentity", state.sourceIdentity).put("endpointSha256", state.endpointSha256)
                .put("receiverId", state.receiverId).put("cursor", state.cursor ?: JSONObject.NULL)
                .put("pendingAck", state.pendingAck?.let { receipt ->
                    JSONObject().put("batchId", receipt.batchId).put("digestSha256", receipt.digestSha256)
                        .put("nextCursor", receipt.nextCursor ?: JSONObject.NULL).put("messageIds", JSONArray(receipt.messageIds))
                } ?: JSONObject.NULL))
        } }
    }
    fun decode(array: JSONArray): List<RemoteChannelBatchState> {
        require(array.length() <= RemoteChannelBatchProtocol.MAX_STATES) { "批次来源过多" }
        val states = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RemoteChannelBatchProtocol.keys(item, setOf("sourceIdentity", "endpointSha256", "receiverId", "cursor", "pendingAck"))
            val receipt = if (item.isNull("pendingAck")) null else item.getJSONObject("pendingAck").let { ack ->
                RemoteChannelBatchProtocol.keys(ack, setOf("batchId", "digestSha256", "nextCursor", "messageIds"))
                val ids = ack.getJSONArray("messageIds")
                require(ids.length() <= RemoteChannelBatchProtocol.MAX_MESSAGES) { "ACK 消息过多" }
                RemoteChannelBatchReceipt(RemoteChannelBatchProtocol.string(ack, "batchId"), RemoteChannelBatchProtocol.string(ack, "digestSha256"),
                    RemoteChannelBatchProtocol.nullableString(ack, "nextCursor"), (0 until ids.length()).map { ids.opt(it) as? String ?: error("ACK 身份必须是字符串") })
            }
            RemoteChannelBatchState(RemoteChannelBatchProtocol.string(item, "sourceIdentity"), RemoteChannelBatchProtocol.string(item, "endpointSha256"),
                RemoteChannelBatchProtocol.string(item, "receiverId"), RemoteChannelBatchProtocol.nullableString(item, "cursor"), receipt)
        }
        encode(states)
        return states
    }
}
