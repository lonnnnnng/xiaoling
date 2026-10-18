package com.longdev.xiaoling.shared.mcp

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import kotlinx.serialization.json.*

/** long: 原文入口只接受有界标准 JSON object；平台已经解析的兼容对象不能借此追溯丢失的重复键。 */
object SharedMcpSchemaCodec {
    fun decodeSchema(raw: String): SharedMcpJsonObject = decode(raw, SharedMcpSchemaContract.MAX_SCHEMA_BYTES, "inputSchema")
    fun decodeArguments(raw: String): SharedMcpJsonObject = decode(raw, SharedMcpSchemaContract.MAX_ARGUMENT_BYTES, "arguments")

    fun encodeSchema(value: SharedMcpJsonObject): String = decodeSchema(value.wireJson).wireJson
    fun encodeArguments(value: SharedMcpJsonObject): String = decodeArguments(value.wireJson).wireJson

    private fun decode(raw: String, maxBytes: Int, label: String): SharedMcpJsonObject = try {
        require(raw.encodeToByteArray(throwOnInvalidSequence = true).size <= maxBytes)
        StrictJsonObjectSyntax.validate(raw, maxBytes)
        PortableMcpJsonObject(Json.parseToJsonElement(raw) as JsonObject)
    } catch (_: Exception) {
        // long: 无效原文不能通过 parser cause 回显 schema 或模型参数，格式错误只报告固定字段分类。
        throw IllegalArgumentException("MCP $label 不是有效 JSON object")
    }
}

private fun portableValue(value: JsonElement?): Any? = when (value) {
    null, JsonNull -> null
    is JsonObject -> PortableMcpJsonObject(value)
    is JsonArray -> PortableMcpJsonArray(value)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.booleanOrNull
        else -> value.longOrNull ?: value.doubleOrNull ?: error("MCP JSON 数字无效")
    }
}

private class PortableMcpJsonObject(private val json: JsonObject) : SharedMcpJsonObject {
    override val size: Int get() = json.size
    override val wireJson: String get() = json.toString()
    override fun has(key: String): Boolean = json.containsKey(key)
    override fun opt(key: String): Any? = portableValue(json[key])
    override fun optString(key: String): String = when (val value = json[key]) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
    override fun optBoolean(key: String, fallback: Boolean): Boolean = when (val value = opt(key)) {
        is Boolean -> value
        is String -> when { value.equals("true", true) -> true; value.equals("false", true) -> false; else -> fallback }
        else -> fallback
    }
    override fun optInt(key: String, fallback: Int): Int = when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.toDoubleOrNull()?.toInt() ?: fallback
        else -> fallback
    }
    override fun optDouble(key: String): Double = when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }
    override fun optJSONObject(key: String): SharedMcpJsonObject? = opt(key) as? SharedMcpJsonObject
    override fun optJSONArray(key: String): SharedMcpJsonArray? = opt(key) as? SharedMcpJsonArray
    override fun getJSONObject(key: String): SharedMcpJsonObject = requireNotNull(optJSONObject(key))
    override fun keys(): Iterator<String> = json.keys.iterator()
}

private class PortableMcpJsonArray(private val json: JsonArray) : SharedMcpJsonArray {
    override val size: Int get() = json.size
    override val wireJson: String get() = json.toString()
    override fun get(index: Int): Any? = portableValue(json[index])
    override fun getString(index: Int): String = get(index) as? String ?: throw IllegalArgumentException("MCP schema 字段必须是 string")
    override fun getJSONObject(index: Int): SharedMcpJsonObject = get(index) as? SharedMcpJsonObject ?: throw IllegalArgumentException("MCP schema 分支必须是 object")
}
