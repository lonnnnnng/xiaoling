package com.longdev.xiaoling.ui.networksettings

interface NetworkRequestSettingsActions {
    fun updateUserAgent(value: String)

    fun resetUserAgent()
}

internal data class NetworkRequestSettingsUiState(
    val userAgent: String,
)
