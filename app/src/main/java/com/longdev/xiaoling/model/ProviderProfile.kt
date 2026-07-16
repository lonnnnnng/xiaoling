package com.longdev.xiaoling.model

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val availableModels: List<String>,
    val enabledModels: List<String>,
    val lastSyncedAt: String = "",
) {
    companion object {
        const val FIXED_MAX_TOKENS = 32768

        fun blank(id: String = System.currentTimeMillis().toString()) = ProviderProfile(
            id = id,
            name = "默认配置",
            baseUrl = "",
            apiKey = "",
            model = "",
            availableModels = emptyList(),
            enabledModels = emptyList(),
            lastSyncedAt = "",
        )
    }
}
