package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.mcp.SharedMcpJsonArray
import com.longdev.xiaoling.shared.mcp.SharedMcpJsonObject
import com.longdev.xiaoling.shared.mcp.SharedMcpJsonSchemaValidator
import org.json.JSONArray
import org.json.JSONObject

/** long: 只迁移校验算法，沿原 JSONObject 解析与取值保持目录、数值 coercion 和对象 enum 的兼容边界。 */
internal object McpJsonSchemaValidator {
    fun validate(schemaJson: String, value: JSONObject) {
        val schema = runCatching { JSONObject(schemaJson) }
            .getOrElse { throw IllegalArgumentException("MCP 工具 inputSchema 不是有效 JSON object", it) }
        SharedMcpJsonSchemaValidator.validate(AndroidMcpJsonObject(schema), AndroidMcpJsonObject(value))
    }
}

private fun androidMcpValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> AndroidMcpJsonObject(value)
    is JSONArray -> AndroidMcpJsonArray(value)
    else -> value
}

private class AndroidMcpJsonObject(private val json: JSONObject) : SharedMcpJsonObject {
    override val size: Int get() = json.length()
    override val wireJson: String get() = json.toString()
    override fun has(key: String): Boolean = json.has(key)
    override fun opt(key: String): Any? = androidMcpValue(json.opt(key))
    override fun optString(key: String): String = json.optString(key)
    override fun optBoolean(key: String, fallback: Boolean): Boolean = json.optBoolean(key, fallback)
    override fun optInt(key: String, fallback: Int): Int = json.optInt(key, fallback)
    override fun optDouble(key: String): Double = json.optDouble(key)
    override fun optJSONObject(key: String): SharedMcpJsonObject? = json.optJSONObject(key)?.let(::AndroidMcpJsonObject)
    override fun optJSONArray(key: String): SharedMcpJsonArray? = json.optJSONArray(key)?.let(::AndroidMcpJsonArray)
    override fun getJSONObject(key: String): SharedMcpJsonObject = AndroidMcpJsonObject(json.getJSONObject(key))
    override fun keys(): Iterator<String> = json.keys()
}

private class AndroidMcpJsonArray(private val json: JSONArray) : SharedMcpJsonArray {
    override val size: Int get() = json.length()
    override val wireJson: String get() = json.toString()
    override fun get(index: Int): Any? = androidMcpValue(json.get(index))
    override fun getString(index: Int): String = json.getString(index)
    override fun getJSONObject(index: Int): SharedMcpJsonObject = AndroidMcpJsonObject(json.getJSONObject(index))
}
