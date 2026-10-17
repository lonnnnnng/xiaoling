package com.longdev.xiaoling.agent

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** long: 接收公钥绑定发送方、地址与队列路由；只允许读取/ACK 该队列，不继承 Agent 执行或后台访问许可。 */
data class RemoteChannelReceiverAuthorization(
    val channelId: String,
    val senderId: String,
    val keyId: String,
    val senderFingerprintSha256: String,
    val endpointSha256: String,
    val receiverId: String,
    val publicKeySpkiBase64: String,
    val receiverFingerprintSha256: String,
)

fun interface RemoteChannelReceiverSigner {
    fun sign(content: ByteArray): ByteArray
}

/** long: 显式登记、导出和 HTTPS 请求共用同一接收身份，网络失败不得生成替代密钥。 */
interface RemoteChannelReceiverIdentity {
    fun create(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState): RemoteChannelReceiverAuthorization
    fun export(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState): RemoteChannelReceiverAuthorization
    fun sign(authorization: RemoteChannelReceiverAuthorization, request: JSONObject, now: Long): String
}

/** long: 每次网络请求重签完整逻辑请求；证明持有接收私钥，不表示用户批准执行正文。 */
object RemoteChannelReceiverAuthProtocol {
    const val VERSION = 1
    const val TTL_MILLIS = 60_000L
    private val HASH = Regex("[0-9a-f]{64}")
    private val NONCE = Regex("[0-9a-f]{32}")
    private val PEER_FIELDS = setOf("channelId", "senderId", "keyId")
    private val PULL_FIELDS = PEER_FIELDS + setOf("protocolVersion", "operation", "receiverId", "requestCursor")
    private val ACK_FIELDS = PEER_FIELDS + setOf("protocolVersion", "operation", "receiverId", "batchId", "batchDigestSha256", "nextCursor", "messageIds")
    private val PROOF_FIELDS = PEER_FIELDS + setOf("authVersion", "operation", "senderFingerprintSha256", "endpointSha256", "receiverId",
        "receiverFingerprintSha256", "issuedAtMillis", "expiresAtMillis", "nonce", "requestDigestSha256", "signature")

    fun authorization(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState,
                      publicKeySpkiBase64: String): RemoteChannelReceiverAuthorization {
        RemoteChannelBatchProtocol.validateState(state)
        require(source.channelId == peer.channelId && source.senderId == peer.senderId && source.keyId == peer.keyId &&
            source.fingerprintSha256 == peer.fingerprintSha256 &&
            state.sourceIdentity == RemoteChannelPullReliabilityPolicy.sourceIdentity(peer) &&
            state.endpointSha256 == RemoteChannelBatchProtocol.endpointHash(source)) { "接收身份的配对或地址绑定不匹配" }
        val key = RemoteChannelPairingPolicy.prepare(peer.channelId, peer.senderId, peer.keyId, "接收端公钥", publicKeySpkiBase64)
        return RemoteChannelReceiverAuthorization(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256,
            state.endpointSha256, state.receiverId, key.publicKeySpkiBase64, key.fingerprintSha256).also(::validate)
    }

    fun validate(value: RemoteChannelReceiverAuthorization) {
        require(HASH.matches(value.senderFingerprintSha256) && HASH.matches(value.endpointSha256) && NONCE.matches(value.receiverId)) {
            "接收授权的指纹、地址或队列身份无效"
        }
        val key = RemoteChannelPairingPolicy.prepare(value.channelId, value.senderId, value.keyId, "接收端公钥", value.publicKeySpkiBase64)
        require(key.channelId == value.channelId && key.senderId == value.senderId && key.keyId == value.keyId &&
            key.publicKeySpkiBase64 == value.publicKeySpkiBase64 && key.fingerprintSha256 == value.receiverFingerprintSha256) {
            "接收公钥或指纹无效"
        }
    }

    fun manifest(value: RemoteChannelReceiverAuthorization): String {
        validate(value)
        return JSONObject().put("authVersion", VERSION).put("channelId", value.channelId).put("senderId", value.senderId)
            .put("keyId", value.keyId).put("senderFingerprintSha256", value.senderFingerprintSha256)
            .put("endpointSha256", value.endpointSha256).put("receiverId", value.receiverId)
            .put("publicKeySpkiBase64", value.publicKeySpkiBase64).put("receiverFingerprintSha256", value.receiverFingerprintSha256).toString()
    }

    fun decodeManifest(json: JSONObject): RemoteChannelReceiverAuthorization {
        RemoteChannelBatchProtocol.keys(json, PEER_FIELDS + setOf("authVersion", "senderFingerprintSha256", "endpointSha256",
            "receiverId", "publicKeySpkiBase64", "receiverFingerprintSha256"))
        require(integer(json, "authVersion") == VERSION.toLong()) { "接收登记版本无效" }
        return RemoteChannelReceiverAuthorization(string(json, "channelId"), string(json, "senderId"), string(json, "keyId"),
            string(json, "senderFingerprintSha256"), string(json, "endpointSha256"), string(json, "receiverId"),
            string(json, "publicKeySpkiBase64"), string(json, "receiverFingerprintSha256")).also(::validate)
    }

    fun pullRequest(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): JSONObject {
        RemoteChannelBatchProtocol.validateState(state)
        require(state.pendingAck == null) { "已有 outbox 时必须先确认原 ACK" }
        return JSONObject().put("protocolVersion", RemoteChannelBatchProtocol.VERSION).put("operation", "pull")
            .put("channelId", peer.channelId).put("senderId", peer.senderId).put("keyId", peer.keyId)
            .put("receiverId", state.receiverId).put("requestCursor", state.cursor ?: JSONObject.NULL)
    }

    fun ackRequest(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): JSONObject =
        JSONObject(RemoteChannelBatchProtocol.ackRequest(peer, state))

    fun signedRequest(authorization: RemoteChannelReceiverAuthorization, request: JSONObject, now: Long,
                      signer: RemoteChannelReceiverSigner, nonce: String = UUID.randomUUID().toString().replace("-", "")): String {
        validate(authorization)
        require(now in 1..Long.MAX_VALUE - TTL_MILLIS && NONCE.matches(nonce)) { "接收请求时间或 nonce 无效" }
        // long: 先冻结结构化请求，再摘要和签名；调用者修改原 JSONObject 不能使正文与已签摘要分离。
        val frozen = JSONObject(request.toString())
        val digest = sha256(canonicalRequestBytes(frozen))
        require(string(frozen, "channelId") == authorization.channelId && string(frozen, "senderId") == authorization.senderId &&
            string(frozen, "keyId") == authorization.keyId && string(frozen, "receiverId") == authorization.receiverId) {
            "请求不属于已绑定接收队列"
        }
        val proof = JSONObject().put("authVersion", VERSION).put("channelId", authorization.channelId).put("senderId", authorization.senderId)
            .put("keyId", authorization.keyId).put("operation", string(frozen, "operation"))
            .put("senderFingerprintSha256", authorization.senderFingerprintSha256).put("endpointSha256", authorization.endpointSha256)
            .put("receiverId", authorization.receiverId).put("receiverFingerprintSha256", authorization.receiverFingerprintSha256)
            .put("issuedAtMillis", now).put("expiresAtMillis", now + TTL_MILLIS).put("nonce", nonce)
            .put("requestDigestSha256", digest).put("signature", "")
        val signature = signer.sign(canonicalProofBytes(proof))
        require(signature.size in 64..80) { "接收端签名无效" }
        proof.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
        return JSONObject().put("request", frozen).put("proof", proof).toString()
    }

    fun canonicalRequestBytes(request: JSONObject): ByteArray {
        require(integer(request, "protocolVersion") == RemoteChannelBatchProtocol.VERSION.toLong()) { "接收请求版本无效" }
        val operation = string(request, "operation")
        require(operation == "pull" || operation == "ack") { "接收请求操作无效" }
        RemoteChannelBatchProtocol.keys(request, if (operation == "pull") PULL_FIELDS else ACK_FIELDS)
        PEER_FIELDS.forEach { identity(string(request, it), if (it == "keyId") 64 else 200) }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(string(request, "keyId")) && NONCE.matches(string(request, "receiverId"))) {
            "接收请求身份无效"
        }
        return bytes("xiaoling-receiver-request-v1") {
            listOf("channelId", "senderId", "keyId", "operation", "receiverId").forEach { field(string(request, it)) }
            if (operation == "pull") nullableField(cursor(request, "requestCursor")) else {
                require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}").matches(string(request, "batchId")) &&
                    HASH.matches(string(request, "batchDigestSha256"))) { "接收 ACK 批次身份无效" }
                field(string(request, "batchId")); field(string(request, "batchDigestSha256")); nullableField(cursor(request, "nextCursor"))
                val ids = request.getJSONArray("messageIds")
                require(ids.length() <= RemoteChannelBatchProtocol.MAX_MESSAGES) { "接收 ACK 消息过多" }
                val values = (0 until ids.length()).map { ids.opt(it) as? String ?: error("接收 ACK 身份必须是字符串") }
                values.forEach { identity(it, 200) }
                require(values.distinct().size == values.size) { "接收 ACK 消息身份重复" }
                writeInt(values.size); values.forEach { field(it) }
            }
        }
    }

    fun canonicalProofBytes(proof: JSONObject): ByteArray {
        RemoteChannelBatchProtocol.keys(proof, PROOF_FIELDS)
        require(integer(proof, "authVersion") == VERSION.toLong() && string(proof, "operation") in setOf("pull", "ack")) {
            "接收认证版本或操作无效"
        }
        PEER_FIELDS.forEach { identity(string(proof, it), 200) }
        listOf("senderFingerprintSha256", "endpointSha256", "receiverFingerprintSha256", "requestDigestSha256").forEach {
            require(HASH.matches(string(proof, it))) { "接收认证摘要无效" }
        }
        require(NONCE.matches(string(proof, "receiverId")) && NONCE.matches(string(proof, "nonce"))) { "接收认证路由或 nonce 无效" }
        val issued = integer(proof, "issuedAtMillis"); val expires = integer(proof, "expiresAtMillis")
        require(issued > 0 && expires > issued && expires - issued <= TTL_MILLIS) { "接收认证有效期无效" }
        return bytes("xiaoling-receiver-auth-v1") {
            listOf("channelId", "senderId", "keyId", "operation", "senderFingerprintSha256", "endpointSha256", "receiverId",
                "receiverFingerprintSha256", "nonce", "requestDigestSha256").forEach { field(string(proof, it)) }
            writeLong(issued); writeLong(expires)
        }
    }

    internal fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun identity(value: String, max: Int) { require(value.isNotEmpty() && value == value.trim() && value.length <= max && '\u0000' !in value) { "接收请求标识无效" } }
    private fun string(value: JSONObject, name: String) = RemoteChannelBatchProtocol.string(value, name)
    private fun integer(value: JSONObject, name: String) = RemoteChannelBatchProtocol.integer(value, name)
    private fun cursor(value: JSONObject, name: String) = RemoteChannelBatchProtocol.nullableString(value, name).also {
        if (it != null) identity(it, RemoteChannelPullReliabilityPolicy.MAX_CURSOR_CHARS)
    }
    private fun bytes(domain: String, body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { it.field(domain); it.writeInt(VERSION); body.invoke(it) }
    }.toByteArray()
    private fun DataOutputStream.field(value: String) { val raw = value.toByteArray(Charsets.UTF_8); writeInt(raw.size); write(raw) }
    private fun DataOutputStream.nullableField(value: String?) { if (value == null) writeInt(-1) else field(value) }
}
