package com.longdev.xiaoling.agent

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * long: 一个配对身份固定到 Channel、发送方和 keyId；来源名称仅供用户辨认，真正的信任锚是已核对的公钥指纹。
 */
data class RemoteChannelPeerCandidate(
    val channelId: String,
    val senderId: String,
    val keyId: String,
    val sourceLabel: String,
    val publicKeySpkiBase64: String,
    val fingerprintSha256: String,
)

data class RemoteChannelPairedPeer(
    val candidate: RemoteChannelPeerCandidate,
    val enabled: Boolean = false,
)

interface RemoteChannelPairingStateStore {
    fun loadState(): String?
    fun saveState(state: String)
}

object RemoteChannelPairingPolicy {
    const val MAX_PEERS = 32
    private const val MAX_PUBLIC_KEY_INPUT_CHARS = 2_048
    private val KEY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    fun prepare(
        channelId: String,
        senderId: String,
        keyId: String,
        sourceLabel: String,
        publicKeyInput: String,
    ): RemoteChannelPeerCandidate {
        val channel = validateIdentity(channelId, "Channel ID")
        val sender = validateIdentity(senderId, "发送方 ID")
        val key = keyId.trim()
        require(KEY_ID.matches(key)) { "远端 keyId 格式无效" }
        val source = sourceLabel.trim()
        require(source.isNotEmpty() && source.length <= 100 && '\u0000' !in source) { "远端来源名称无效" }
        require(publicKeyInput.length <= MAX_PUBLIC_KEY_INPUT_CHARS) { "远端公钥输入过长" }
        val compact = publicKeyInput
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        val encoded = runCatching { Base64.getDecoder().decode(compact) }
            .getOrElse { throw IllegalArgumentException("远端公钥必须是 X.509 SPKI Base64 或 PEM", it) }
        require(encoded.size in 80..512) { "远端公钥长度无效" }
        val parsed = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) }
            .getOrElse { throw IllegalArgumentException("远端公钥无法解析", it) }
        require(parsed is ECPublicKey && isP256(parsed)) { "远端公钥必须使用 P-256 曲线" }
        val canonical = parsed.encoded
        require(canonical != null && canonical.size in 80..512) { "远端公钥编码无效" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return RemoteChannelPeerCandidate(
            channelId = channel,
            senderId = sender,
            keyId = key,
            sourceLabel = source,
            publicKeySpkiBase64 = Base64.getEncoder().encodeToString(canonical),
            fingerprintSha256 = fingerprint,
        )
    }

    fun matchesFingerprint(candidate: RemoteChannelPeerCandidate, typedFingerprint: String): Boolean {
        val normalized = typedFingerprint.trim().lowercase().replace(":", "").replace(" ", "")
        return FINGERPRINT.matches(normalized) && normalized == candidate.fingerprintSha256
    }

    private fun validateIdentity(raw: String, label: String): String = raw.trim().also { value ->
        require(value.isNotEmpty() && value.length <= RemoteChannelPolicy.MAX_IDENTITY_CHARS && '\u0000' !in value) {
            "$label 无效"
        }
    }

    private fun isP256(key: ECPublicKey): Boolean {
        val expected = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val actual = key.params
        return actual.curve == expected.curve && actual.generator == expected.generator &&
            actual.order == expected.order && actual.cofactor == expected.cofactor
    }
}

/** long: 每次验签重新读入配对账本，停用、撤销和损坏状态立即作用于入站，不依赖旧的内存快照。 */
class RemoteChannelPairingRegistry(private val store: RemoteChannelPairingStateStore) {
    fun list(): List<RemoteChannelPairedPeer> = synchronized(STATE_LOCK) { load() }

    fun confirm(candidate: RemoteChannelPeerCandidate, typedFingerprint: String): RemoteChannelPairedPeer =
        synchronized(STATE_LOCK) {
            val validated = RemoteChannelPairingPolicy.prepare(
                candidate.channelId, candidate.senderId, candidate.keyId,
                candidate.sourceLabel, candidate.publicKeySpkiBase64,
            )
            require(validated == candidate && RemoteChannelPairingPolicy.matchesFingerprint(validated, typedFingerprint)) {
                "远端公钥指纹未核对一致"
            }
            val current = load()
            val existing = current.firstOrNull { it.candidate.identity() == candidate.identity() }
            if (existing != null) {
                require(existing.candidate.publicKeySpkiBase64 == candidate.publicKeySpkiBase64) {
                    "该身份的 keyId 已绑定其他公钥；请使用新的 keyId 轮换"
                }
                return@synchronized existing
            }
            require(current.size < RemoteChannelPairingPolicy.MAX_PEERS) { "远端配对身份已达到上限" }
            val added = RemoteChannelPairedPeer(validated, enabled = false)
            store.saveState(RemoteChannelPairingJsonCodec.encode(current + added))
            added
        }

    fun setEnabled(channelId: String, senderId: String, keyId: String, enabled: Boolean): Boolean =
        synchronized(STATE_LOCK) {
            val current = load()
            val index = current.indexOfFirst { it.candidate.identity() == Triple(channelId, senderId, keyId) }
            if (index < 0) return@synchronized false
            if (current[index].enabled == enabled) return@synchronized true
            val next = current.toMutableList().apply { this[index] = this[index].copy(enabled = enabled) }
            store.saveState(RemoteChannelPairingJsonCodec.encode(next))
            true
        }

    fun revoke(channelId: String, senderId: String, keyId: String): Boolean = synchronized(STATE_LOCK) {
        val current = load()
        val next = current.filterNot { it.candidate.identity() == Triple(channelId, senderId, keyId) }
        if (next.size == current.size) return@synchronized false
        store.saveState(RemoteChannelPairingJsonCodec.encode(next))
        true
    }

    fun active(channelId: String, senderId: String, keyId: String): RemoteChannelPeerCandidate? =
        list().firstOrNull { it.enabled && it.candidate.identity() == Triple(channelId, senderId, keyId) }?.candidate

    private fun load(): List<RemoteChannelPairedPeer> = store.loadState()?.let(RemoteChannelPairingJsonCodec::decode).orEmpty()

    private companion object { val STATE_LOCK = Any() }
}

class PublicKeyRemoteChannelAuthenticator(
    private val registry: RemoteChannelPairingRegistry,
    private val maxAgeMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS,
    private val maxFutureSkewMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS,
) : RemoteChannelAuthenticator {
    init {
        require(maxAgeMillis > 0L && maxFutureSkewMillis >= 0L) { "远端签名时间窗无效" }
    }

    override fun verify(envelope: RemoteChannelEnvelope, nowMillis: Long): RemoteChannelRejectionReason? {
        RemoteChannelPolicy.validate(envelope)?.let { return it }
        val nonce = envelope.nonce?.trim().orEmpty()
        if (nonce.length !in RemoteChannelPolicy.MIN_NONCE_CHARS..RemoteChannelPolicy.MAX_NONCE_CHARS) {
            return RemoteChannelRejectionReason.INVALID_NONCE
        }
        if (envelope.receivedAtMillis < nowMillis - maxAgeMillis ||
            envelope.receivedAtMillis > nowMillis + maxFutureSkewMillis
        ) return RemoteChannelRejectionReason.TIMESTAMP_OUT_OF_WINDOW
        val keyId = envelope.keyId?.trim().orEmpty()
        if (keyId.isEmpty()) return RemoteChannelRejectionReason.UNKNOWN_KEY_ID
        val peer = runCatching {
            registry.active(envelope.channelId.trim(), envelope.senderId.trim(), keyId)
        }.getOrElse { return RemoteChannelRejectionReason.PAIRING_STATE_UNAVAILABLE }
            ?: return RemoteChannelRejectionReason.UNKNOWN_KEY_ID
        val rawSignature = envelope.signature?.trim().orEmpty()
        if (rawSignature.isEmpty()) return RemoteChannelRejectionReason.MISSING_SIGNATURE
        if (rawSignature.length > 128) return RemoteChannelRejectionReason.INVALID_SIGNATURE
        val signature = runCatching { Base64.getUrlDecoder().decode(rawSignature) }.getOrNull()
            ?: return RemoteChannelRejectionReason.INVALID_SIGNATURE
        if (signature.size !in 64..80) return RemoteChannelRejectionReason.INVALID_SIGNATURE
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(peer.publicKeySpkiBase64)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(RemoteChannelPolicy.canonicalBytes(envelope))
            if (verifier.verify(signature)) null else RemoteChannelRejectionReason.INVALID_SIGNATURE
        }.getOrElse { RemoteChannelRejectionReason.INVALID_SIGNATURE }
    }
}

private fun RemoteChannelPeerCandidate.identity(): Triple<String, String, String> = Triple(channelId, senderId, keyId)

/** long: 持久配对记录只含公钥和本机启停状态，严格解码避免损坏账本被当成空白信任表。 */
object RemoteChannelPairingJsonCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_STATE_BYTES = 64 * 1024

    fun encode(peers: List<RemoteChannelPairedPeer>): String {
        require(peers.size <= RemoteChannelPairingPolicy.MAX_PEERS) { "远端配对身份过多" }
        require(peers.map { it.candidate.identity() }.distinct().size == peers.size) { "远端配对身份重复" }
        val root = JSONObject().put("schemaVersion", SCHEMA_VERSION).put("peers", JSONArray().apply {
            peers.forEach { peer ->
                val c = peer.candidate
                require(RemoteChannelPairingPolicy.prepare(
                    c.channelId, c.senderId, c.keyId, c.sourceLabel, c.publicKeySpkiBase64,
                ) == c) { "远端配对公钥或指纹无效" }
                put(JSONObject().put("channelId", c.channelId).put("senderId", c.senderId)
                    .put("keyId", c.keyId).put("sourceLabel", c.sourceLabel)
                    .put("publicKeySpkiBase64", c.publicKeySpkiBase64).put("enabled", peer.enabled))
            }
        })
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "远端配对状态过大" } }
    }

    fun decode(raw: String): List<RemoteChannelPairedPeer> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "远端配对状态过大" }
        val root = JSONObject(raw)
        root.requireKeys(setOf("schemaVersion", "peers"))
        require(root.opt("schemaVersion") == SCHEMA_VERSION) { "远端配对状态版本不受支持" }
        val values = root.getJSONArray("peers")
        require(values.length() <= RemoteChannelPairingPolicy.MAX_PEERS) { "远端配对身份过多" }
        val peers = (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            item.requireKeys(setOf("channelId", "senderId", "keyId", "sourceLabel", "publicKeySpkiBase64", "enabled"))
            val channelId = item.requireString("channelId", 200)
            val senderId = item.requireString("senderId", 200)
            val keyId = item.requireString("keyId", 64)
            val sourceLabel = item.requireString("sourceLabel", 100)
            val publicKeySpkiBase64 = item.requireString("publicKeySpkiBase64", 2_048)
            val candidate = RemoteChannelPairingPolicy.prepare(
                channelId, senderId, keyId, sourceLabel, publicKeySpkiBase64,
            )
            // long: 磁盘快照必须已是规范身份，不能在读取时悄悄 trim 或重编码后改变公钥绑定对象。
            require(candidate.channelId == channelId && candidate.senderId == senderId &&
                candidate.keyId == keyId && candidate.sourceLabel == sourceLabel &&
                candidate.publicKeySpkiBase64 == publicKeySpkiBase64) { "远端配对身份未规范化" }
            RemoteChannelPairedPeer(candidate, item.opt("enabled") as? Boolean
                ?: throw IllegalArgumentException("远端配对启停状态无效"))
        }
        require(peers.map { it.candidate.identity() }.distinct().size == peers.size) { "远端配对身份重复" }
        return peers
    }

    private fun JSONObject.requireKeys(allowed: Set<String>) {
        require(keys().asSequence().toSet() == allowed) { "远端配对状态字段无效" }
    }

    private fun JSONObject.requireString(name: String, maxLength: Int): String {
        val value = opt(name)
        require(value is String && value.isNotEmpty() && value.length <= maxLength) { "远端配对字段 $name 无效" }
        return value
    }
}
