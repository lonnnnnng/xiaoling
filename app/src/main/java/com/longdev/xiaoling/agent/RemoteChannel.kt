package com.longdev.xiaoling.agent

import com.longdev.xiaoling.share.SharedDraftPayload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKey
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
    INVALID_IDENTITY,
    TIMESTAMP_OUT_OF_WINDOW,
    INVALID_NONCE,
    MISSING_SIGNATURE,
    UNKNOWN_KEY_ID,
    INVALID_SIGNATURE,
    SENDER_NOT_ALLOWED,
    DUPLICATE_MESSAGE,
    DEDUPE_PERSISTENCE_FAILURE,
    PENDING_INBOX_FULL,
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
    const val MAX_IDENTITY_CHARS = 200
    const val MAX_DEDUPE_ENTRIES = 256
    const val MAX_PENDING_DRAFTS = 8
    const val MAX_PENDING_AGE_MILLIS = 24 * 60 * 60 * 1000L
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

    fun dedupeKey(draft: RemoteChannelDraft): String =
        "${draft.channelId}\u0000${draft.senderId}\u0000${draft.messageId}"

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
        // long: 复合去重键使用 NUL 分隔身份字段；拒绝内嵌 NUL 和无界 ID，防止不同发送方构造相同键或耗尽本地账本。
        if (listOf(envelope.channelId, envelope.messageId, envelope.senderId, envelope.conversationKey)
                .any { it.length > MAX_IDENTITY_CHARS || '\u0000' in it }
        ) return RemoteChannelRejectionReason.INVALID_IDENTITY
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
class HmacRemoteChannelAuthenticator private constructor(
    private val keys: Map<String, HmacSigningKey>,
    private val maxAgeMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS,
    private val maxFutureSkewMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : RemoteChannelAuthenticator {
    constructor(
        keys: Map<String, ByteArray>,
        maxAgeMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS,
        maxFutureSkewMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS,
    ) : this(
        keys = keys.mapValues { (_, secret) -> ByteArrayHmacSigningKey(secret) },
        maxAgeMillis = maxAgeMillis,
        maxFutureSkewMillis = maxFutureSkewMillis,
        marker = Unit,
    )

    init {
        require(keys.isNotEmpty()) { "远程 Channel 至少需要一个签名密钥" }
        require(keys.keys.none(String::isBlank)) { "远程 Channel keyId 不能为空" }
        require(keys.values.all(HmacSigningKey::meetsMinimumLength)) {
            "远程 Channel HMAC 密钥长度不足"
        }
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

    /**
     * long: AndroidKeyStore 的 HMAC SecretKey 不可导出，Authenticator 只持有 Key 接口并让 JCA Mac 直接使用它。
     */
    companion object {
        fun fromKeystore(
            keys: Map<String, SecretKey>,
            maxAgeMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS,
            maxFutureSkewMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS,
        ): HmacRemoteChannelAuthenticator = HmacRemoteChannelAuthenticator(
            keys = keys.mapValues { (_, secret) -> KeystoreHmacSigningKey(secret) },
            maxAgeMillis = maxAgeMillis,
            maxFutureSkewMillis = maxFutureSkewMillis,
            marker = Unit,
        )
    }

    fun sign(envelope: RemoteChannelEnvelope, keyId: String): String {
        val secret = keys[keyId] ?: error("未知远程 Channel keyId：$keyId")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(envelope.copy(keyId = keyId), secret))
    }

    private fun sign(envelope: RemoteChannelEnvelope, secret: HmacSigningKey): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        secret.init(mac)
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

    private interface HmacSigningKey {
        val meetsMinimumLength: Boolean

        fun init(mac: Mac)
    }

    private class ByteArrayHmacSigningKey(secret: ByteArray) : HmacSigningKey {
        private val secret = secret.copyOf()

        override val meetsMinimumLength: Boolean
            get() = secret.size >= RemoteChannelPolicy.MIN_HMAC_KEY_BYTES

        override fun init(mac: Mac) {
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
        }
    }

    private class KeystoreHmacSigningKey(private val secret: SecretKey) : HmacSigningKey {
        init {
            require(secret.algorithm.equals("HmacSHA256", ignoreCase = true)) {
                "远程 Channel Keystore 密钥必须使用 HmacSHA256"
            }
        }

        override val meetsMinimumLength: Boolean = true

        override fun init(mac: Mac) {
            mac.init(secret)
        }
    }
}

interface RemoteChannelDedupeStore {
    fun loadKeys(): List<String>

    fun saveKeys(keys: List<String>)
}

data class RemoteChannelPendingDraft(
    val draft: RemoteChannelDraft,
    val acceptedAtMillis: Long,
    val nonceDedupeKey: String? = null,
)

data class RemoteChannelInboxSnapshot(
    val dedupeKeys: List<String>,
    val pendingDrafts: List<RemoteChannelPendingDraft>,
)

interface RemoteChannelInboxStateStore {
    fun loadSnapshot(): RemoteChannelInboxSnapshot?

    fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot)
}

class InMemoryRemoteChannelInbox(
    private val allowlist: RemoteChannelAllowlist,
    private val maxDedupeEntries: Int = RemoteChannelPolicy.MAX_DEDUPE_ENTRIES,
    private val dedupeStore: RemoteChannelDedupeStore? = null,
    private val authenticator: RemoteChannelAuthenticator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val inboxStateStore: RemoteChannelInboxStateStore? = null,
) {
    private val seen = linkedSetOf<String>()
    private val pending = mutableListOf<RemoteChannelPendingDraft>()
    private var storageUnavailable = false

    init {
        require(maxDedupeEntries in 1..RemoteChannelPolicy.MAX_DEDUPE_ENTRIES) {
            "远程 Channel 去重窗口必须在 1 到 ${RemoteChannelPolicy.MAX_DEDUPE_ENTRIES} 之间"
        }
        require(inboxStateStore == null || (dedupeStore == null && authenticator != null)) {
            "持久待处理收件箱必须单独使用，并启用签名验证"
        }
        require(inboxStateStore == null || maxDedupeEntries == RemoteChannelPolicy.MAX_DEDUPE_ENTRIES) {
            "持久待处理收件箱使用固定去重窗口"
        }
        require(authenticator == null || maxDedupeEntries >= 2) { "签名消息至少需要两个去重槽位" }
        val durableStore = inboxStateStore
        if (durableStore != null) {
            // long: 加密快照损坏或密钥不可用时冻结整个入站，不能丢掉旧去重身份后重新接收相同消息。
            val loaded = runCatching { durableStore.loadSnapshot() }
                .onFailure { storageUnavailable = true }
                .getOrNull()
            loaded?.let { snapshot ->
                seen.addAll(snapshot.dedupeKeys)
                pending.addAll(snapshot.pendingDrafts)
                val active = pending.filter { it.acceptedAtMillis > clock() - RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS }
                if (active.size != pending.size) {
                    val purged = RemoteChannelInboxSnapshot(seen.toList(), active)
                    if (runCatching { durableStore.saveSnapshot(purged) }.isSuccess) {
                        pending.clear()
                        pending.addAll(active)
                    } else {
                        storageUnavailable = true
                        pending.clear()
                    }
                }
            }
        } else {
            val restoredKeys = runCatching { dedupeStore?.loadKeys() }
                .onFailure { storageUnavailable = true }
                .getOrNull()
            restoredKeys
                ?.asSequence()
                ?.filter(String::isNotBlank)
                ?.toList()
                ?.takeLast(maxDedupeEntries)
                ?.let(seen::addAll)
        }
    }

    @Synchronized
    fun pendingDrafts(): List<RemoteChannelPendingDraft> = if (storageUnavailable) {
        emptyList()
    } else {
        pending.filter { it.acceptedAtMillis > clock() - RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS &&
            it.draft.channelId == allowlist.channelId && it.draft.senderId in allowlist.senderIds }
    }

    @Synchronized
    fun discardPending(channelId: String, senderId: String, messageId: String): Boolean {
        val durableStore = inboxStateStore ?: return false
        if (storageUnavailable) return false
        val next = pending.filterNot { it.draft.channelId == channelId &&
            it.draft.senderId == senderId && it.draft.messageId == messageId }
        if (next.size == pending.size) return false
        // long: 打开或忽略只移除待确认正文，保留去重身份，避免外部发送方用同一消息 ID 再次弹出草稿。
        if (runCatching { durableStore.saveSnapshot(RemoteChannelInboxSnapshot(seen.toList(), next)) }.isFailure) {
            return false
        }
        pending.clear()
        pending.addAll(next)
        return true
    }

    @Synchronized
    fun receive(envelope: RemoteChannelEnvelope): RemoteChannelReceiveResult {
        if (storageUnavailable) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE)
        }
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
        if (key in seen || nonceKey?.let(seen::contains) == true) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.DUPLICATE_MESSAGE)
        }
        val draft = RemoteChannelDraft(
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
            )
        val now = clock()
        val nextPending = pending.filter { it.acceptedAtMillis > now - RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS } +
            RemoteChannelPendingDraft(draft, now, nonceKey)
        if (inboxStateStore != null && nextPending.size > RemoteChannelPolicy.MAX_PENDING_DRAFTS) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.PENDING_INBOX_FULL)
        }
        val nextKeys = (seen.toList() + key + listOfNotNull(nonceKey)).toMutableList()
        if (inboxStateStore != null) {
            // long: 未读正文存在时保留其消息 ID 与 nonce 身份；窗口满时先淘汰已处理消息，不能让未读消息被再次投递。
            val protectedKeys = nextPending.flatMap { entry ->
                listOf(RemoteChannelPolicy.dedupeKey(entry.draft)) + listOfNotNull(entry.nonceDedupeKey)
            }.toSet()
            while (nextKeys.size > maxDedupeEntries) {
                val evictIndex = nextKeys.indexOfFirst { it !in protectedKeys }
                if (evictIndex < 0) {
                    return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.PENDING_INBOX_FULL)
                }
                nextKeys.removeAt(evictIndex)
            }
        } else {
            while (nextKeys.size > maxDedupeEntries) nextKeys.removeAt(0)
        }
        // long: 先构造下一份完整快照，只有去重身份与待确认正文同次写盘成功后才更新内存和交付草稿。
        val persisted = runCatching {
            when {
                inboxStateStore != null -> inboxStateStore.saveSnapshot(RemoteChannelInboxSnapshot(nextKeys, nextPending))
                dedupeStore != null -> dedupeStore.saveKeys(nextKeys)
            }
        }.isSuccess
        if (!persisted) {
            return RemoteChannelReceiveResult.Rejected(RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE)
        }
        seen.clear()
        seen.addAll(nextKeys)
        if (inboxStateStore != null) {
            pending.clear()
            pending.addAll(nextPending)
        }
        return RemoteChannelReceiveResult.Accepted(draft)
    }
}
