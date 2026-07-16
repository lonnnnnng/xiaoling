package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.model.AppThemeMode

class UiPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("xiaoling_ui", Context.MODE_PRIVATE)

    fun loadThemeMode(): AppThemeMode {
        val stored = preferences.getString(KEY_THEME_MODE, null).orEmpty()
        return AppThemeMode.entries.firstOrNull { it.name == stored } ?: AppThemeMode.SYSTEM
    }

    fun saveThemeMode(mode: AppThemeMode) {
        // long: 主题属于用户使用环境偏好，和 Provider 密钥配置分开保存，避免后续导入/清空 Provider 时误改夜间模式选择。
        preferences.edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
