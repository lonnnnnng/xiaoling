package com.longdev.xiaoling.agent

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class RemoteChannelSignedBatch(
    val channelId: String,
    val senderId: String,
    val keyId: String,
    val receiverId: String,
    val batchId: String,
    val requestCursor: String?,
    val nextCursor: String?,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val messages: List<RemoteChannelEnvelope>,
    val signature: String,
)

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
    const val VERSION = 1
    const val MAX_MESSAGES = 8
    const val MAX_STATES = 32
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
    private val RECEIVER_ID = Regex("[0-9a-f]{32}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val BATCH_FIELDS = setOf("protocolVersion", "channelId", "senderId", "keyId", "receiverId",
        "batchId", "requestCursor", "nextCursor", "issuedAtMillis", "expiresAtMillis", "messages", "signature")

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

    fun decodeResponse(raw: String): RemoteChannelPullResponse {
        require(raw.toByteArray(Charsets.UTF_8).size <= RemoteChannelSignedEnvelopeCodec.MAX_JSON_BYTES) { "批次响应过大" }
        val json = JSONObject(raw)
        if (!json.has("protocolVersion")) return RemoteChannelPullResponse.Single(RemoteChannelSignedEnvelopeCodec.decode(raw))
        keys(json, BATCH_FIELDS)
        require(json.opt("protocolVersion") == VERSION) { "批次协议版本无效" }
        val messages = json.getJSONArray("messages")
        require(messages.length() <= MAX_MESSAGES) { "批次消息过多" }
        val batch = RemoteChannelSignedBatch(
            string(json, "channelId"), string(json, "senderId"), string(json, "keyId"),
            string(json, "receiverId"), string(json, "batchId"), nullableString(json, "requestCursor"),
            nullableString(json, "nextCursor"), integer(json, "issuedAtMillis"), integer(json, "expiresAtMillis"),
            (0 until messages.length()).map { RemoteChannelSignedEnvelopeCodec.decode(messages.getJSONObject(it).toString()) },
            string(json, "signature"),
        )
        validateBatch(batch)
        return RemoteChannelPullResponse.Batch(batch)
    }

    fun validateBatch(batch: RemoteChannelSignedBatch) {
        require(batch.channelId.length in 1..200 && batch.channelId == batch.channelId.trim() && '\u0000' !in batch.channelId &&
            batch.senderId.length in 1..200 && batch.senderId == batch.senderId.trim() && '\u0000' !in batch.senderId &&
            batch.keyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) &&
            RECEIVER_ID.matches(batch.receiverId) && ID.matches(batch.batchId)) { "批次身份无效" }
        cursor(batch.requestCursor)
        cursor(batch.nextCursor)
        require(batch.nextCursor != null) { "批次下一游标不能为空" }
        require(batch.issuedAtMillis > 0 && batch.expiresAtMillis > batch.issuedAtMillis &&
            batch.expiresAtMillis - batch.issuedAtMillis <= RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS) { "批次有效期无效" }
        require(batch.messages.size <= MAX_MESSAGES && batch.messages.map { it.messageId.trim() }.distinct().size == batch.messages.size &&
            batch.messages.map { it.nonce?.trim() }.distinct().size == batch.messages.size) { "批次消息身份重复或超限" }
        require(batch.messages.isEmpty() || batch.nextCursor != batch.requestCursor) { "非空批次必须推进游标" }
        require(batch.signature.length in 1..128) { "批次签名无效" }
    }

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

    fun canonicalBatchBytes(batch: RemoteChannelSignedBatch): ByteArray = bytes("xiaoling-batch-v1") {
        field(batch.channelId); field(batch.senderId); field(batch.keyId); field(batch.receiverId); field(batch.batchId)
        nullableField(batch.requestCursor); nullableField(batch.nextCursor)
        writeLong(batch.issuedAtMillis); writeLong(batch.expiresAtMillis); writeInt(batch.messages.size)
        batch.messages.forEach { envelope ->
            val content = RemoteChannelPolicy.canonicalBytes(envelope)
            writeInt(content.size); write(content); field(envelope.signature.orEmpty())
        }
    }

    fun receipt(batch: RemoteChannelSignedBatch) = RemoteChannelBatchReceipt(
        batch.batchId, sha256(canonicalBatchBytes(batch)), batch.nextCursor, batch.messages.map { it.messageId.trim() },
    )

    fun ackRequest(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): String {
        validateState(state)
        val receipt = state.pendingAck ?: error("没有待 ACK 批次")
        return ackFields(peer, state, "ack").put("messageIds", JSONArray(receipt.messageIds)).toString()
    }

    fun verifyAck(raw: String, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState) {
        val response = JSONObject(raw)
        val expected = ackFields(peer, state, "acknowledged")
        keys(response, expected.keys().asSequence().toSet() + "signature")
        expected.keys().forEach { key -> require(response.opt(key) == expected.opt(key)) { "ACK 回执绑定不匹配" } }
        verifySignature(peer, canonicalAckBytes(peer, state), string(response, "signature"))
    }

    fun canonicalAckBytes(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): ByteArray = bytes("xiaoling-ack-v1") {
        val receipt = state.pendingAck ?: error("没有待 ACK 批次")
        field(peer.channelId); field(peer.senderId); field(peer.keyId); field(state.receiverId)
        field(receipt.batchId); field(receipt.digestSha256); nullableField(receipt.nextCursor)
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
    private fun bytes(domain: String, write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { it.field(domain); it.writeInt(VERSION); write.invoke(it) }
    }.toByteArray()
    private fun DataOutputStream.field(value: String) { val b = value.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataOutputStream.nullableField(value: String?) { if (value == null) writeInt(-1) else field(value) }
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
