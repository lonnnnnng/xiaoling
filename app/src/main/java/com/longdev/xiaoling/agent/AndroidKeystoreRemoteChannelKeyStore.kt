package com.longdev.xiaoling.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * long: Remote Channel 的签名密钥由 AndroidKeyStore 生成和使用，应用层只拿到不可导出的 SecretKey 引用，不把原文放进 SharedPreferences 或 Room。
 */
class AndroidKeystoreRemoteChannelKeyStore(
    private val aliasPrefix: String = DEFAULT_ALIAS_PREFIX,
) {
    fun authenticator(
        keyIds: Set<String>,
        maxAgeMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS,
        maxFutureSkewMillis: Long = RemoteChannelPolicy.DEFAULT_MAX_FUTURE_SKEW_MILLIS,
    ): HmacRemoteChannelAuthenticator {
        require(keyIds.isNotEmpty()) { "远程 Channel 至少需要一个 keyId" }
        val keys = keyIds.associateWith { keyId -> getOrCreateKey(keyId) }
        return HmacRemoteChannelAuthenticator.fromKeystore(keys, maxAgeMillis, maxFutureSkewMillis)
    }

    fun isNonExportable(keyId: String): Boolean = synchronized(lock) {
        val keyStore = keyStore()
        val key = keyStore.getKey(aliasFor(keyId), null) as? SecretKey ?: return@synchronized false
        key.encoded == null
    }

    fun delete(keyId: String): Boolean = synchronized(lock) {
        val keyStore = keyStore()
        val alias = aliasFor(keyId)
        if (!keyStore.containsAlias(alias)) return@synchronized false
        keyStore.deleteEntry(alias)
        true
    }

    private fun getOrCreateKey(keyId: String): SecretKey = synchronized(lock) {
        val keyStore = keyStore()
        val alias = aliasFor(keyId)
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun aliasFor(keyId: String): String {
        require(KEY_ID_PATTERN.matches(keyId)) { "远程 Channel keyId 格式无效" }
        return "$aliasPrefix$keyId"
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS_PREFIX = "xiaoling_remote_channel_"
        private val KEY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val lock = Any()
    }
}
