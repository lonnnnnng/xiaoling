package com.longdev.xiaoling.shared.mcp

/** long: hints 只是远端元数据；平台仍须独立执行白名单、审批、前台及会话门禁。 */
data class SharedMcpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
)

object SharedMcpSchemaContract {
    const val VERSION = 1
    const val MAX_DEPTH = 8
    const val MAX_PROPERTIES = 100
    const val MAX_ARRAY_ITEMS = 100
    const val MAX_SCHEMA_BYTES = 512 * 1024
    const val MAX_ARGUMENT_BYTES = 32 * 1024
}

/** long: 校验算法与 JSON 平台分离，Android 可保留原 JSONObject coercion 和对象 enum 的原序列化比较。 */
interface SharedMcpJsonObject {
    val size: Int
    val wireJson: String
    fun has(key: String): Boolean
    fun opt(key: String): Any?
    fun optString(key: String): String
    fun optBoolean(key: String, fallback: Boolean): Boolean
    fun optInt(key: String, fallback: Int): Int
    fun optDouble(key: String): Double
    fun optJSONObject(key: String): SharedMcpJsonObject?
    fun optJSONArray(key: String): SharedMcpJsonArray?
    fun getJSONObject(key: String): SharedMcpJsonObject
    fun keys(): Iterator<String>
}

interface SharedMcpJsonArray {
    val size: Int
    val wireJson: String
    fun get(index: Int): Any?
    fun getString(index: Int): String
    fun getJSONObject(index: Int): SharedMcpJsonObject
}
