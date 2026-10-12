package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelPairingStateStore

/** long: 只保存用户确认的远端公钥、来源标签和启停状态；私钥从不进入本机配对账本。 */
class SharedPreferencesRemoteChannelPairingStateStore(
    context: Context,
    preferencesName: String = "xiaoling_remote_channel_pairings",
) : RemoteChannelPairingStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun loadState(): String? = preferences.getString(KEY_STATE, null)

    override fun saveState(state: String) {
        check(preferences.edit().putString(KEY_STATE, state).commit()) { "远端配对状态无法持久化" }
    }

    private companion object { const val KEY_STATE = "state" }
}
