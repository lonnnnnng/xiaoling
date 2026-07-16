package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.data.ApiKeyCipher
import com.longdev.xiaoling.model.ProviderProfile
import org.json.JSONArray
import org.json.JSONObject

data class StoredProfiles(
    val profiles: List<ProviderProfile>,
    val selectedProfileId: String,
)

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("xiaoling", Context.MODE_PRIVATE)
    private val apiKeyCipher = ApiKeyCipher()

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
            val secret = apiKeyCipher.encrypt(profile.apiKey)
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("model", profile.model)
                    .put("availableModels", JSONArray(profile.availableModels))
                    .put("enabledModels", JSONArray(profile.enabledModels))
                    .put("lastSyncedAt", profile.lastSyncedAt)
                    .put("apiKeyIv", secret.iv)
                    .put("apiKeyCiphertext", secret.ciphertext),
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
                        apiKey = apiKeyCipher.decrypt(json.optString("apiKeyIv"), json.optString("apiKeyCiphertext")),
                        model = json.optString("model"),
                        availableModels = json.optJSONArray("availableModels").toStringList(),
                        enabledModels = json.optJSONArray("enabledModels").toStringList()
                            .ifEmpty { json.optJSONArray("availableModels").toStringList() },
                        lastSyncedAt = json.optString("lastSyncedAt"),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        const val DEFAULT_PROMPT = ""
        private const val KEY_PROFILES_JSON = "provider_profiles_json"
        private const val KEY_SELECTED_PROFILE_ID = "selected_provider_profile_id"
    }
}
