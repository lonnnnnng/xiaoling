package com.longdev.xiaoling.shared.mcp

/** long: MCP schema 只约束远端参数，校验通过不产生审批、白名单或本地执行权限。 */
object SharedMcpJsonSchemaValidator {
    private const val MAX_DEPTH = SharedMcpSchemaContract.MAX_DEPTH
    private const val MAX_PROPERTIES = SharedMcpSchemaContract.MAX_PROPERTIES
    private const val MAX_ARRAY_ITEMS = SharedMcpSchemaContract.MAX_ARRAY_ITEMS

    fun validate(schemaJson: String, argumentsJson: String) = validate(
        SharedMcpSchemaCodec.decodeSchema(schemaJson), SharedMcpSchemaCodec.decodeArguments(argumentsJson),
    )

    fun validate(schema: SharedMcpJsonObject, value: SharedMcpJsonObject) {
        validateValue(schema, value, "arguments", 0)
    }

    private fun validateValue(schema: SharedMcpJsonObject, value: Any?, path: String, depth: Int) {
        require(depth <= MAX_DEPTH) { "MCP 参数 schema 嵌套过深：$path" }
        if (schema.has("enum")) {
            val enumValues = schema.optJSONArray("enum") ?: throw IllegalArgumentException("MCP schema enum 无效：$path")
            require((0 until enumValues.size).any { jsonEquals(enumValues.get(it), value) }) {
                "MCP 参数不在允许枚举中：$path"
            }
        }
        if (schema.has("allOf")) {
            val branches = schema.optJSONArray("allOf") ?: throw IllegalArgumentException("MCP schema allOf 无效：$path")
            for (index in 0 until branches.size) {
                validateValue(branches.getJSONObject(index), value, path, depth + 1)
            }
        }
        // long: 保留原 anyOf 优先及命中后返回；这不是完整 JSON Schema 的同层组合语义升级。
        if (schema.has("anyOf") || schema.has("oneOf")) {
            val key = if (schema.has("anyOf")) "anyOf" else "oneOf"
            val branches = schema.optJSONArray(key) ?: throw IllegalArgumentException("MCP schema $key 无效：$path")
            val matches = (0 until branches.size).count { index ->
                runCatching { validateValue(branches.getJSONObject(index), value, path, depth + 1) }.isSuccess
            }
            require(if (key == "oneOf") matches == 1 else matches >= 1) {
                "MCP 参数不符合 schema $key：$path"
            }
            return
        }

        when (schema.optString("type").takeIf(String::isNotBlank)) {
            "object" -> validateObject(schema, value, path, depth)
            "array" -> validateArray(schema, value, path, depth)
            "string" -> validateString(schema, value, path)
            "number" -> validateNumber(schema, value, path, integer = false)
            "integer" -> validateNumber(schema, value, path, integer = true)
            "boolean" -> require(value is Boolean) { "MCP 参数必须是 boolean：$path" }
            "null" -> require(value == null) { "MCP 参数必须是 null：$path" }
            null -> Unit
            else -> throw IllegalArgumentException("MCP schema type 无效：$path")
        }
    }

    private fun validateObject(schema: SharedMcpJsonObject, value: Any?, path: String, depth: Int) {
        require(value is SharedMcpJsonObject) { "MCP 参数必须是 object：$path" }
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.size) {
                val name = required.getString(index)
                require(value.has(name)) {
                    "MCP 参数缺少必填字段：$path.$name"
                }
            }
        }
        // long: 沿原有界子集，仅有 properties 对象时才限制 additionalProperties，不扩大校验声明。
        val properties = schema.optJSONObject("properties")
        if (properties != null) {
            require(properties.size <= MAX_PROPERTIES) { "MCP schema 属性过多：$path" }
            val names = properties.keys().asSequence().toSet()
            val keys = value.keys().asSequence().toList()
            if (schema.has("additionalProperties") && schema.optBoolean("additionalProperties", true).not()) {
                require(keys.all { it in names }) { "MCP 参数包含未声明字段：$path" }
            }
            for (name in names) {
                if (value.has(name)) {
                    validateValue(properties.getJSONObject(name), value.opt(name), "$path.$name", depth + 1)
                }
            }
        }
        checkNumericLimit(schema, "minProperties", value.size, path)
        checkNumericLimit(schema, "maxProperties", value.size, path, lowerBound = false)
    }

    private fun validateArray(schema: SharedMcpJsonObject, value: Any?, path: String, depth: Int) {
        require(value is SharedMcpJsonArray) { "MCP 参数必须是 array：$path" }
        require(value.size <= MAX_ARRAY_ITEMS) { "MCP 参数数组过长：$path" }
        checkNumericLimit(schema, "minItems", value.size, path)
        checkNumericLimit(schema, "maxItems", value.size, path, lowerBound = false)
        schema.optJSONObject("items")?.let { itemSchema ->
            for (index in 0 until value.size) {
                validateValue(itemSchema, value.get(index), "$path[$index]", depth + 1)
            }
        }
    }

    private fun validateString(schema: SharedMcpJsonObject, value: Any?, path: String) {
        require(value is String) { "MCP 参数必须是 string：$path" }
        checkNumericLimit(schema, "minLength", value.length, path)
        checkNumericLimit(schema, "maxLength", value.length, path, lowerBound = false)
        schema.optString("pattern").takeIf(String::isNotBlank)?.let { pattern ->
            val matches = runCatching { Regex(pattern).containsMatchIn(value) }.getOrElse {
                throw IllegalArgumentException("MCP schema pattern 无效：$path", it)
            }
            require(matches) { "MCP 参数不符合 pattern：$path" }
        }
    }

    private fun validateNumber(schema: SharedMcpJsonObject, value: Any?, path: String, integer: Boolean) {
        require(value is Number) { "MCP 参数必须是 number：$path" }
        val number = value.toDouble()
        require(number.isFinite()) { "MCP 参数不是有限数字：$path" }
        if (integer) require(number % 1.0 == 0.0) { "MCP 参数必须是 integer：$path" }
        schema.optDouble("minimum").takeUnless(Double::isNaN)?.let { require(number >= it) { "MCP 参数小于 minimum：$path" } }
        schema.optDouble("maximum").takeUnless(Double::isNaN)?.let { require(number <= it) { "MCP 参数大于 maximum：$path" } }
    }

    private fun checkNumericLimit(schema: SharedMcpJsonObject, key: String, actual: Int, path: String, lowerBound: Boolean = true) {
        if (!schema.has(key)) return
        val limit = schema.optInt(key, Int.MIN_VALUE)
        require(limit != Int.MIN_VALUE && if (lowerBound) actual >= limit else actual <= limit) {
            "MCP 参数违反 $key：$path"
        }
    }

    // long: 数字沿 Double 比较，复合 enum 沿各端已确定的 wire 比较，不能静默改成无序结构相等。
    private fun jsonEquals(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        left is SharedMcpJsonObject && right is SharedMcpJsonObject -> left.wireJson == right.wireJson
        left is SharedMcpJsonArray && right is SharedMcpJsonArray -> left.wireJson == right.wireJson
        else -> left == right
    }
}
