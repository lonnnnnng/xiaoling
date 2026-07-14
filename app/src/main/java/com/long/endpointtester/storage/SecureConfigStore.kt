package com.longdev.endpointtester.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.longdev.endpointtester.model.ProviderProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredProfiles(
    val profiles: List<ProviderProfile>,
    val selectedProfileId: String,
)

private data class EncryptedSecret(
    val iv: String,
    val ciphertext: String,
)

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("endpoint_tester", Context.MODE_PRIVATE)

    fun load(): StoredProfiles {
        val stored = preferences.getString(KEY_PROFILES_JSON, null)
        val profiles = stored
            ?.let(::decodeProfiles)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(ProviderProfile.blank())
        val selected = preferences.getString(KEY_SELECTED_PROFILE_ID, null)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        return StoredProfiles(profiles, selected)
    }

    fun save(profiles: List<ProviderProfile>, selectedProfileId: String) {
        val safeProfiles = profiles.ifEmpty { listOf(ProviderProfile.blank()) }
        preferences.edit()
            .putString(KEY_PROFILES_JSON, encodeProfiles(safeProfiles))
            .putString(KEY_SELECTED_PROFILE_ID, selectedProfileId.takeIf { id -> safeProfiles.any { it.id == id } } ?: safeProfiles.first().id)
            .apply()
    }

    private fun encodeProfiles(profiles: List<ProviderProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val secret = profile.apiKey.takeIf { it.isNotBlank() }?.let(::encrypt)
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("model", profile.model)
                    .put("availableModels", JSONArray(profile.availableModels))
                    .put("enabledModels", JSONArray(profile.enabledModels))
                    .put("apiKeyIv", secret?.iv.orEmpty())
                    .put("apiKeyCiphertext", secret?.ciphertext.orEmpty()),
            )
        }
        return array.toString()
    }

    private fun decodeProfiles(raw: String): List<ProviderProfile> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(
                    ProviderProfile(
                        id = json.optString("id").ifBlank { "profile-$index" },
                        name = json.optString("name").ifBlank { "配置 ${index + 1}" },
                        baseUrl = json.optString("baseUrl"),
                        apiKey = decrypt(json.optString("apiKeyIv"), json.optString("apiKeyCiphertext")),
                        model = json.optString("model"),
                        availableModels = json.optJSONArray("availableModels").toStringList(),
                        enabledModels = json.optJSONArray("enabledModels").toStringList()
                            .ifEmpty { json.optJSONArray("availableModels").toStringList() },
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun encrypt(value: String): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // long: 每个 Provider 配置单独保存 IV，密钥留在 Android Keystore，避免多配置场景把 API Key 明文写入偏好文件。
        return EncryptedSecret(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
        )
    }

    private fun decrypt(iv: String, ciphertext: String): String {
        if (iv.isBlank() || ciphertext.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
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

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        const val DEFAULT_PROMPT = "请只回复 OK"
        private const val KEY_ALIAS = "endpoint_tester_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_PROFILES_JSON = "provider_profiles_json"
        private const val KEY_SELECTED_PROFILE_ID = "selected_provider_profile_id"
    }
}
