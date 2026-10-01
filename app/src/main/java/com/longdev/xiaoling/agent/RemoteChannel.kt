package com.longdev.xiaoling.agent

import com.longdev.xiaoling.share.SharedDraftPayload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    val nonce: String? = null,
    val keyId: String? = null,
    val signature: String? = null,
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
    TIMESTAMP_OUT_OF_WINDOW,
    INVALID_NONCE,
    MISSING_SIGNATURE,
    UNKNOWN_KEY_ID,
    INVALID_SIGNATURE,
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
    const val MIN_HMAC_KEY_BYTES = 32
    const val MIN_NONCE_CHARS = 16
    const val MAX_NONCE_CHARS = 256
    const val DEFAULT_MAX_AGE_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_MAX_FUTURE_SKEW_MILLIS = 30 * 1000L

    fun normalizeText(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    fun dedupeKey(envelope: RemoteChannelEnvelope): String =
        "${envelope.channelId.trim()}\u0000${envelope.senderId.trim()}\u0000${envelope.messageId.trim()}"

    fun nonceDedupeKey(envelope: RemoteChannelEnvelope): String? = envelope.nonce
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { nonce -> "nonce\u0000${envelope.channelId.trim()}\u0000${envelope.senderId.trim()}\u0000$nonce" }

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

interface RemoteChannelAuthenticator {
    fun verify(envelope: RemoteChannelEnvelope, nowMillis: Long): RemoteChannelRejectionReason?
}

/**
 * long: 入站签名只证明 envelope 来自配置的 key，不能替代前台确认、Agent 执行或外部业务成功回执；同时传入 current/previous key 即可完成轮换过渡。
 */
class HmacRemoteChannelAuthenticator(
    keys: Map<String, ByteArray>,
    private val maxAgeMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS,
    private val maxFutureSkewMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS,
) : RemoteChannelAuthenticator {
    private val keys = keys
        .mapValues { (_, secret) -> secret.copyOf() }
        .also { entries ->
            require(entries.isNotEmpty()) { "远程 Channel 至少需要一个签名密钥" }
            require(entries.keys.none(String::isBlank)) { "远程 Channel keyId 不能为空" }
            require(entries.values.all { it.size >= RemoteChannelPolicy.MIN_HMAC_KEY_BYTES }) {
                "远程 Channel HMAC 密钥长度不足"
            }
        }

    init {
        require(maxAgeMillis > 0L) { "远程 Channel 签名时间窗必须大于零" }
        require(maxFutureSkewMillis >= 0L) { "远程 Channel 未来时间容忍值不能小于零" }
    }

    override fun verify(envelope: RemoteChannelEnvelope, nowMillis: Long): RemoteChannelRejectionReason? {
        val nonce = envelope.nonce?.trim().orEmpty()
        if (nonce.length !in RemoteChannelPolicy.MIN_NONCE_CHARS..RemoteChannelPolicy.MAX_NONCE_CHARS) {
            return RemoteChannelRejectionReason.INVALID_NONCE
        }
        if (envelope.receivedAtMillis < nowMillis - maxAgeMillis ||
            envelope.receivedAtMillis > nowMillis + maxFutureSkewMillis
        ) {
            return RemoteChannelRejectionReason.TIMESTAMP_OUT_OF_WINDOW
        }
        val keyId = envelope.keyId?.trim().orEmpty()
        if (keyId.isBlank()) return RemoteChannelRejectionReason.UNKNOWN_KEY_ID
        val secret = keys[keyId] ?: return RemoteChannelRejectionReason.UNKNOWN_KEY_ID
        val signature = envelope.signature?.trim().orEmpty()
        if (signature.isBlank()) return RemoteChannelRejectionReason.MISSING_SIGNATURE
        val expected = sign(envelope.copy(keyId = keyId), secret)
        val actual = runCatching { Base64.getUrlDecoder().decode(signature) }.getOrNull()
            ?: return RemoteChannelRejectionReason.INVALID_SIGNATURE
        return if (MessageDigest.isEqual(expected, actual)) null else RemoteChannelRejectionReason.INVALID_SIGNATURE
    }

    fun sign(envelope: RemoteChannelEnvelope, keyId: String): String {
        val secret = keys[keyId] ?: error("未知远程 Channel keyId：$keyId")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(envelope.copy(keyId = keyId), secret))
    }

    private fun sign(envelope: RemoteChannelEnvelope, secret: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(canonicalBytes(envelope))
    }

    private fun canonicalBytes(envelope: RemoteChannelEnvelope): ByteArray {
        val fields = listOf(
            envelope.channelId.trim(),
            envelope.messageId.trim(),
            envelope.senderId.trim(),
            envelope.conversationKey.trim(),
            RemoteChannelPolicy.normalizeText(envelope.text),
            envelope.receivedAtMillis.toString(),
            envelope.nonce?.trim().orEmpty(),
            envelope.keyId?.trim().orEmpty(),
        )
        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(fields.size)
                fields.forEach { field ->
                    val bytes = field.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            buffer.toByteArray()
        }
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
    private val authenticator: RemoteChannelAuthenticator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
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
        authenticator?.verify(envelope, clock())?.let { return RemoteChannelReceiveResult.Rejected(it) }
        if (envelope.channelId.trim() != allowlist.channelId) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.SENDER_NOT_ALLOWED)
        }
        if (envelope.senderId.trim() !in allowlist.senderIds) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.SENDER_NOT_ALLOWED)
        }
        val key = RemoteChannelPolicy.dedupeKey(envelope)
        val nonceKey = if (authenticator != null) RemoteChannelPolicy.nonceDedupeKey(envelope) else null
        if (seen.containsKey(key) || nonceKey?.let(seen::containsKey) == true) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.DUPLICATE_MESSAGE)
        }
        seen[key] = Unit
        nonceKey?.let { seen[it] = Unit }
        val store = dedupeStore
        if (store != null) {
            val persisted = runCatching { store.saveKeys(seen.keys.toList()) }.isSuccess
            if (!persisted) {
                // long: 去重账本写失败时不能先把消息交给前台，否则进程重启后可能再次生成同一草稿；回滚本次内存占位并明确拒绝。
                seen.remove(key)
                nonceKey?.let(seen::remove)
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
