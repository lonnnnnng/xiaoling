package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentPluginStateStore

class SharedPreferencesAgentPluginStateStore(context: Context) : AgentPluginStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "xiaoling_agent_plugins",
        Context.MODE_PRIVATE,
    )

    override fun loadState(): String? = preferences.getString(KEY_STATE, null)

    override fun saveState(state: String) {
        // long: 使用同步提交，只有磁盘快照成功后注册表才会提交内存变更，避免进程被杀时丢失启停状态。
        check(preferences.edit().putString(KEY_STATE, state).commit()) {
            "插件状态无法持久化"
        }
    }

    private companion object {
        const val KEY_STATE = "state"
    }
}
