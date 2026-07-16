package com.longdev.xiaoling.data

import org.json.JSONArray

object RoomJson {
    fun encodeStringList(values: List<String>): String {
        return JSONArray(values).toString()
    }

    fun decodeStringList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }
}
