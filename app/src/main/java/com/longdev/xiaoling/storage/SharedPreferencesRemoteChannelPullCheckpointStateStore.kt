package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelPullCheckpointStateStore

/** long: 只保存收件游标、ACK 待处理身份和退避时间，不保存正文或签名私密材料。 */
class SharedPreferencesRemoteChannelPullCheckpointStateStore(
    context: Context,
    preferencesName: String = "xiaoling_remote_channel_pull_checkpoints",
) : RemoteChannelPullCheckpointStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun loadState(): String? = preferences.getString(KEY_STATE, null)

    override fun saveState(state: String) {
        check(preferences.edit().putString(KEY_STATE, state).commit()) { "HTTPS 收件可靠性账本无法持久化" }
    }

    private companion object { const val KEY_STATE = "state" }
}
