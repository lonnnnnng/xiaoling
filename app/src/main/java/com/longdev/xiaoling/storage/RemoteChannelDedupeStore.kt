package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelDedupeStore
import org.json.JSONArray

class SharedPreferencesRemoteChannelDedupeStore(context: Context) : RemoteChannelDedupeStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "xiaoling_remote_channel",
        Context.MODE_PRIVATE,
    )

    override fun loadKeys(): List<String> {
        val raw = preferences.getString(KEY_DEDUPE_KEYS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveKeys(keys: List<String>) {
        // long: 使用有序 JSON 数组保留 LRU 顺序；SharedPreferences 只保存去重身份，不保存消息正文、sender 资料或工具权限。
        val committed = preferences.edit()
            .putString(KEY_DEDUPE_KEYS, JSONArray(keys).toString())
            .commit()
        check(committed) { "远程 Channel 去重账本无法持久化" }
    }

    private companion object {
        const val KEY_DEDUPE_KEYS = "dedupe_keys"
    }
}
