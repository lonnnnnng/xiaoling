package com.longdev.endpointtester.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: String,
    val prompt: String,
    val rememberApiKey: Boolean,
)

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("endpoint_tester", Context.MODE_PRIVATE)

    fun load(): StoredConfig {
        val rememberApiKey = preferences.getBoolean(KEY_REMEMBER_API_KEY, true)
        return StoredConfig(
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            apiKey = if (rememberApiKey) decryptApiKey() else "",
            model = preferences.getString(KEY_MODEL, "").orEmpty(),
            customHeaders = preferences.getString(KEY_CUSTOM_HEADERS, "").orEmpty(),
            prompt = preferences.getString(KEY_PROMPT, DEFAULT_PROMPT).orEmpty().ifBlank { DEFAULT_PROMPT },
            rememberApiKey = rememberApiKey,
        )
    }

    fun save(config: StoredConfig) {
        preferences.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_CUSTOM_HEADERS, config.customHeaders)
            .putString(KEY_PROMPT, config.prompt)
            .putBoolean(KEY_REMEMBER_API_KEY, config.rememberApiKey)
            .apply()

        if (config.rememberApiKey && config.apiKey.isNotBlank()) {
            encryptApiKey(config.apiKey)
        } else {
            clearApiKey()
        }
    }

    private fun encryptApiKey(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // long: IV 必须和密文一起保存；密钥只存在 Android Keystore，SharedPreferences 中没有可直接使用的 API Key。
        preferences.edit()
            .putString(KEY_API_KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_API_KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun decryptApiKey(): String {
        val iv = preferences.getString(KEY_API_KEY_IV, null) ?: return ""
        val ciphertext = preferences.getString(KEY_API_KEY_CIPHERTEXT, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            clearApiKey()
            ""
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun clearApiKey() {
        preferences.edit()
            .remove(KEY_API_KEY_IV)
            .remove(KEY_API_KEY_CIPHERTEXT)
            .apply()
    }

    companion object {
        const val DEFAULT_PROMPT = "请只回复 OK"
        private const val KEY_ALIAS = "endpoint_tester_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_CUSTOM_HEADERS = "custom_headers"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_REMEMBER_API_KEY = "remember_api_key"
        private const val KEY_API_KEY_IV = "api_key_iv"
        private const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"
    }
}
