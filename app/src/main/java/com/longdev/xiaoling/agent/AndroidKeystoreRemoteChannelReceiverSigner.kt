package com.longdev.xiaoling.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** long: 接收端 P-256 私钥只由 Keystore 使用；签名和导出公钥都禁止因密钥丢失而自动生成替代身份。 */
class AndroidKeystoreRemoteChannelReceiverSigner(private val aliasPrefix: String = "xiaoling_remote_receiver_v1_") {
    init { require(Regex("[A-Za-z0-9_-]{1,100}").matches(aliasPrefix)) }

    fun create(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState): RemoteChannelReceiverAuthorization =
        synchronized(LOCK) {
            val alias = alias(peer, source, state)
            require(!keyStore().containsAlias(alias)) { "接收签名身份已经存在，不能覆盖" }
            // long: 只有显式创建入口生成身份；认证请求失败、进程重建和密钥缺失不会进入这条分支。
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKeyPair()
            }
            export(peer, source, state)
        }

    fun export(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState): RemoteChannelReceiverAuthorization =
        synchronized(LOCK) {
            val entry = keyStore().getEntry(alias(peer, source, state), null) as? KeyStore.PrivateKeyEntry
                ?: error("接收签名身份不可用")
            require(entry.privateKey.encoded == null) { "接收私钥必须不可导出" }
            RemoteChannelReceiverAuthProtocol.authorization(peer, source, state, Base64.getEncoder().encodeToString(entry.certificate.publicKey.encoded))
        }

    fun sign(authorization: RemoteChannelReceiverAuthorization, request: org.json.JSONObject, now: Long,
             nonce: String = java.util.UUID.randomUUID().toString().replace("-", "")): String = synchronized(LOCK) {
        RemoteChannelReceiverAuthProtocol.validate(authorization)
        val entry = keyStore().getEntry(alias(authorization), null) as? KeyStore.PrivateKeyEntry ?: error("接收签名身份不可用")
        require(entry.privateKey.encoded == null && Base64.getEncoder().encodeToString(entry.certificate.publicKey.encoded) == authorization.publicKeySpkiBase64) {
            "接收签名公钥与原授权不匹配"
        }
        RemoteChannelReceiverAuthProtocol.signedRequest(authorization, request, now, RemoteChannelReceiverSigner { content ->
            Signature.getInstance("SHA256withECDSA").run { initSign(entry.privateKey); update(content); sign() }
        }, nonce)
    }

    private fun alias(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState): String {
        RemoteChannelBatchProtocol.validateState(state)
        require(state.sourceIdentity == RemoteChannelPullReliabilityPolicy.sourceIdentity(peer) &&
            state.endpointSha256 == RemoteChannelBatchProtocol.endpointHash(source) &&
            source.channelId == peer.channelId && source.senderId == peer.senderId && source.keyId == peer.keyId &&
            source.fingerprintSha256 == peer.fingerprintSha256) { "接收签名来源绑定不匹配" }
        return aliasPrefix + RemoteChannelReceiverAuthProtocol.sha256(
            "$VERSION_DOMAIN\u0000${state.sourceIdentity}\u0000${state.endpointSha256}\u0000${state.receiverId}".toByteArray(Charsets.UTF_8))
    }

    internal fun alias(authorization: RemoteChannelReceiverAuthorization): String {
        val identity = listOf(authorization.channelId, authorization.senderId, authorization.keyId, authorization.senderFingerprintSha256)
            .joinToString("\u0000")
        return aliasPrefix + RemoteChannelReceiverAuthProtocol.sha256(
            "$VERSION_DOMAIN\u0000$identity\u0000${authorization.endpointSha256}\u0000${authorization.receiverId}".toByteArray(Charsets.UTF_8))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object { val LOCK = Any(); const val VERSION_DOMAIN = "xiaoling-receiver-key-v1" }
}
