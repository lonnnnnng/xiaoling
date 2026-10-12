package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelPullSourceStateStore

/** long: 只保存 HTTPS 地址及公钥身份绑定，不保存独立认证头；响应正文始终进入独立 Keystore 加密收件箱。 */
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
