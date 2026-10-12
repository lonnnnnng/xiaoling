package com.longdev.xiaoling.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.longdev.xiaoling.agent.RemoteChannelDraft
import com.longdev.xiaoling.agent.RemoteChannelEnvelope
import com.longdev.xiaoling.agent.RemoteChannelInboxSnapshot
import com.longdev.xiaoling.agent.RemoteChannelInboxStateStore
import com.longdev.xiaoling.agent.RemoteChannelPendingDraft
import com.longdev.xiaoling.agent.RemoteChannelPolicy
import com.longdev.xiaoling.share.SharedDraftPayload
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * long: 去重身份与待确认正文封成同一份 AES-GCM 快照，并用单个 SharedPreferences 键同步提交。
 * 该偏好文件不进入应用的数据库备份；密钥仅在本机 Android Keystore，重建后仍能恢复待确认消息。
 */
class KeystoreRemoteChannelInboxStateStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : RemoteChannelInboxStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun loadSnapshot(): RemoteChannelInboxSnapshot? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_ENCRYPTED_BYTES) { "远程收件箱密文过大" }
        val envelope = JSONObject(raw)
        envelope.requireOnlyKeys(setOf("version", "iv", "ciphertext"))
        require(envelope.requiredLong("version") == ENVELOPE_VERSION.toLong()) { "远程收件箱密文版本不受支持" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(envelope.requiredString("iv", 128), Base64.NO_WRAP)),
        )
        val plaintext = cipher.doFinal(Base64.decode(envelope.requiredString("ciphertext", MAX_ENCRYPTED_BYTES), Base64.NO_WRAP))
        return RemoteChannelInboxSnapshotCodec.decode(String(plaintext, Charsets.UTF_8))
    }

    override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) {
        val plaintext = RemoteChannelInboxSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = JSONObject()
            .put("version", ENVELOPE_VERSION)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
        check(preferences.edit().putString(KEY_SNAPSHOT, envelope).commit()) {
            "远程收件箱无法持久化"
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val DEFAULT_PREFERENCES = "xiaoling_remote_channel_inbox"
        const val DEFAULT_KEY_ALIAS = "xiaoling_remote_channel_inbox_aes"
        const val KEY_SNAPSHOT = "snapshot"
        const val ENVELOPE_VERSION = 1
        const val MAX_ENCRYPTED_BYTES = 2 * 1024 * 1024
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val KEY_LOCK = Any()
    }
}

internal object RemoteChannelInboxSnapshotCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_STATE_BYTES = 1024 * 1024
    private const val MAX_KEY_LENGTH = 1024

    fun encode(snapshot: RemoteChannelInboxSnapshot): String {
        require(snapshot.dedupeKeys.size <= RemoteChannelPolicy.MAX_DEDUPE_ENTRIES) { "远程去重记录过多" }
        require(snapshot.dedupeKeys.distinct().size == snapshot.dedupeKeys.size) { "远程去重记录重复" }
        require(snapshot.dedupeKeys.all { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }) { "远程去重身份无效" }
        require(snapshot.pendingDrafts.size <= RemoteChannelPolicy.MAX_PENDING_DRAFTS) { "远程待处理消息过多" }
        require(snapshot.pendingDrafts.map { identityKey(it.draft) }.distinct().size == snapshot.pendingDrafts.size) {
            "远程待处理身份重复"
        }
        require(snapshot.pendingDrafts.all { identityKey(it.draft) in snapshot.dedupeKeys }) {
            "远程待处理消息缺少去重身份"
        }
        require(snapshot.pendingDrafts.all { it.nonceDedupeKey != null && it.nonceDedupeKey in snapshot.dedupeKeys }) {
            "远程待处理消息缺少 nonce 去重身份"
        }
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("dedupeKeys", JSONArray(snapshot.dedupeKeys))
            .put("pendingDrafts", JSONArray().apply {
                snapshot.pendingDrafts.forEach { pending ->
                    val draft = pending.draft
                    require(draft.payload.imageUri == null && draft.payload.documentUri == null) {
                        "远程待处理消息不能携带附件"
                    }
                    validateDraft(draft, pending.acceptedAtMillis)
                    require(pending.acceptedAtMillis > 0L) { "远程待处理时间无效" }
                    put(JSONObject()
                        .put("channelId", draft.channelId)
                        .put("messageId", draft.messageId)
                        .put("senderId", draft.senderId)
                        .put("conversationKey", draft.conversationKey)
                        .put("text", draft.payload.text)
                        .put("nonceDedupeKey", pending.nonceDedupeKey)
                        .put("acceptedAtMillis", pending.acceptedAtMillis))
                }
            })
        return root.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "远程收件箱快照过大" }
        }
    }

    fun decode(raw: String): RemoteChannelInboxSnapshot {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "远程收件箱快照过大" }
        val root = JSONObject(raw)
        root.requireOnlyKeys(setOf("schemaVersion", "dedupeKeys", "pendingDrafts"))
        require(root.requiredLong("schemaVersion") == SCHEMA_VERSION.toLong()) { "远程收件箱 schemaVersion 不受支持" }
        val keys = root.getJSONArray("dedupeKeys")
        require(keys.length() <= RemoteChannelPolicy.MAX_DEDUPE_ENTRIES) { "远程去重记录过多" }
        val dedupeKeys = (0 until keys.length()).map { index ->
            (keys.opt(index) as? String) ?: throw IllegalArgumentException("远程去重身份必须是字符串")
        }
        require(dedupeKeys.distinct().size == dedupeKeys.size) { "远程去重记录重复" }
        require(dedupeKeys.all { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }) { "远程去重身份无效" }
        val entries = root.getJSONArray("pendingDrafts")
        require(entries.length() <= RemoteChannelPolicy.MAX_PENDING_DRAFTS) { "远程待处理消息过多" }
        val pending = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            item.requireOnlyKeys(setOf("channelId", "messageId", "senderId", "conversationKey", "text", "nonceDedupeKey", "acceptedAtMillis"))
            val text = item.requiredString("text", RemoteChannelPolicy.MAX_TEXT_CHARS)
            require(text.isNotBlank() && text.length <= RemoteChannelPolicy.MAX_TEXT_CHARS) { "远程待处理正文无效" }
            val acceptedAtMillis = item.requiredLong("acceptedAtMillis")
            require(acceptedAtMillis > 0L) { "远程待处理时间无效" }
            RemoteChannelPendingDraft(
                draft = RemoteChannelDraft(
                    channelId = item.requiredString("channelId", RemoteChannelPolicy.MAX_IDENTITY_CHARS),
                    messageId = item.requiredString("messageId", RemoteChannelPolicy.MAX_IDENTITY_CHARS),
                    senderId = item.requiredString("senderId", RemoteChannelPolicy.MAX_IDENTITY_CHARS),
                    conversationKey = item.requiredString("conversationKey", RemoteChannelPolicy.MAX_IDENTITY_CHARS),
                    payload = SharedDraftPayload(text, null),
                ),
                acceptedAtMillis = acceptedAtMillis,
                nonceDedupeKey = item.requiredString("nonceDedupeKey", MAX_KEY_LENGTH),
            )
        }
        require(pending.map { identityKey(it.draft) }.distinct().size == pending.size) { "远程待处理身份重复" }
        require(pending.all { identityKey(it.draft) in dedupeKeys }) { "远程待处理消息缺少去重身份" }
        require(pending.all { it.nonceDedupeKey in dedupeKeys }) { "远程待处理消息缺少 nonce 去重身份" }
        pending.forEach { validateDraft(it.draft, it.acceptedAtMillis) }
        return RemoteChannelInboxSnapshot(dedupeKeys, pending)
    }

    private fun identityKey(draft: RemoteChannelDraft): String = RemoteChannelPolicy.dedupeKey(draft)

    private fun validateDraft(draft: RemoteChannelDraft, acceptedAtMillis: Long) {
        val envelope = RemoteChannelEnvelope(
            channelId = draft.channelId,
            messageId = draft.messageId,
            senderId = draft.senderId,
            conversationKey = draft.conversationKey,
            text = draft.payload.text,
            receivedAtMillis = acceptedAtMillis,
        )
        require(RemoteChannelPolicy.validate(envelope) == null) { "远程待处理消息无效" }
        require(RemoteChannelPolicy.normalizeText(draft.payload.text) == draft.payload.text) {
            "远程待处理正文未规范化"
        }
    }
}

private fun JSONObject.requireOnlyKeys(allowed: Set<String>) {
    val unknown = buildList { keys().forEach { if (it !in allowed) add(it) } }
    require(unknown.isEmpty()) { "远程收件箱包含未知字段：${unknown.sorted().joinToString()}" }
}

private fun JSONObject.requiredString(key: String, maxLength: Int): String {
    val value = opt(key)
    require(value is String && value.isNotEmpty() && value.length <= maxLength) { "远程收件箱字段 $key 无效" }
    return value
}

private fun JSONObject.requiredLong(key: String): Long {
    val value = opt(key)
    require(value is Number) { "远程收件箱字段 $key 必须是整数" }
    val parsed = value.toLong()
    require(value.toDouble() == parsed.toDouble()) { "远程收件箱字段 $key 必须是整数" }
    return parsed
}
