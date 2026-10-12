package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.RemoteChannelDedupeStore
import com.longdev.xiaoling.agent.RemoteChannelPolicy
import org.json.JSONArray

class SharedPreferencesRemoteChannelDedupeStore(
    context: Context,
    preferencesName: String = "xiaoling_remote_channel",
) : RemoteChannelDedupeStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override fun loadKeys(): List<String> {
        val raw = preferences.getString(KEY_DEDUPE_KEYS, null) ?: return emptyList()
        val array = JSONArray(raw)
        require(array.length() <= RemoteChannelPolicy.MAX_DEDUPE_ENTRIES) { "远程去重账本过大" }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val key = array.opt(index) as? String
                    ?: throw IllegalArgumentException("远程去重身份必须是字符串")
                require(key.isNotBlank() && key.length <= 1024) { "远程去重身份无效" }
                add(key)
            }
        }.also { require(it.distinct().size == it.size) { "远程去重身份重复" } }
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
