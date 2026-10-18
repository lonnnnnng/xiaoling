package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.remote.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** long: 接收公钥绑定发送方、地址与队列路由；只允许读取/ACK 该队列，不继承 Agent 执行或后台访问许可。 */
typealias RemoteChannelReceiverAuthorization = com.longdev.xiaoling.shared.remote.SharedRemoteChannelReceiverAuthorization

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
    const val VERSION = SharedRemoteChannelWireCodec.VERSION
    const val TTL_MILLIS = SharedRemoteChannelWireCodec.PROOF_TTL_MILLIS
    private val HASH = Regex("[0-9a-f]{64}")
    private val NONCE = Regex("[0-9a-f]{32}")

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
        require(RemoteChannelBatchProtocol.integer(json, "authVersion") == VERSION.toLong()) { "接收登记版本无效" }
        return SharedRemoteChannelWireCodec.decodeManifest(json.toString()).also(::validate)
    }

    fun pullRequest(peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState): JSONObject {
        RemoteChannelBatchProtocol.validateState(state)
        require(state.pendingAck == null) { "已有 outbox 时必须先确认原 ACK" }
        return JSONObject(SharedRemoteChannelWireCodec.encodeRequest(SharedRemoteChannelRequest.Pull(
            peer.channelId, peer.senderId, peer.keyId, state.receiverId, state.cursor)))
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

    // long: 已解析 JSONObject 只能校验当前对象；网络原文回复直接走 shared raw 门禁，不能追溯对象解析前的重复键。
    fun canonicalRequestBytes(request: JSONObject): ByteArray {
        require(RemoteChannelBatchProtocol.integer(request, "protocolVersion") == VERSION.toLong()) { "接收请求版本无效" }
        return SharedRemoteChannelWireCodec.canonicalRequestBytes(SharedRemoteChannelWireCodec.decodeRequest(request.toString()))
    }

    fun canonicalProofBytes(proof: JSONObject): ByteArray {
        // long: JSONObject 会把 1.0 序列化为 1，必须在转成 raw wire 前保留原类型门禁，避免浮点版本/时间被静默接受。
        require(RemoteChannelBatchProtocol.integer(proof, "authVersion") == VERSION.toLong()) { "接收认证版本无效" }
        RemoteChannelBatchProtocol.integer(proof, "issuedAtMillis")
        RemoteChannelBatchProtocol.integer(proof, "expiresAtMillis")
        return SharedRemoteChannelWireCodec.canonicalProofBytes(SharedRemoteChannelWireCodec.decodeProof(proof.toString()))
    }

    internal fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun string(value: JSONObject, name: String) = RemoteChannelBatchProtocol.string(value, name)
}
