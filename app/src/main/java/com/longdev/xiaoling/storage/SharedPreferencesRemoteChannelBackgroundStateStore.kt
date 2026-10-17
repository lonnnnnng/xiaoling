package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelBackgroundStateStore

/** long: 这里只保存后台许可、地址指纹和有界状态摘要，正文与 ACK 仍在原加密快照。 */
class SharedPreferencesRemoteChannelBackgroundStateStore(context: Context, preferencesName: String = "xiaoling_remote_background") : RemoteChannelBackgroundStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    override fun loadState(): String? = preferences.getString("state", null)
    override fun saveState(state: String) { check(preferences.edit().putString("state", state).commit()) { "后台收件状态无法持久化" } }
}
