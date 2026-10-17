package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelPullSourceStateStore

/** long: 来源账本只保存地址、认证模式与公开登记绑定；接收私钥留在 Keystore，正文仍由独立加密收件箱保存。 */
class SharedPreferencesRemoteChannelPullSourceStateStore(
    context: Context,
    preferencesName: String = "xiaoling_remote_channel_pull_sources",
) : RemoteChannelPullSourceStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun loadState(): String? = preferences.getString(KEY_STATE, null)

    override fun saveState(state: String) {
        check(preferences.edit().putString(KEY_STATE, state).commit()) { "HTTPS 收件来源无法持久化" }
    }

    private companion object { const val KEY_STATE = "state" }
}
