package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject

internal fun ToolDefinition.toInputJsonSchema(): String {
    val properties = JSONObject()
    inputSchema.forEach { field ->
        val property = JSONObject()
            .put("type", field.type.toJsonType())
            .put("description", field.description)
        field.minLength?.let { property.put("minLength", it) }
        field.maxLength?.let { property.put("maxLength", it) }
        field.minimum?.let { property.put("minimum", it) }
        field.maximum?.let { property.put("maximum", it) }
        if (field.enumValues.isNotEmpty()) {
            property.put("enum", JSONArray(field.enumValues.sorted()))
        }
        properties.put(field.name, property)
    }
    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(inputSchema.filter { it.required }.map { it.name }))
        .put("additionalProperties", false)
        .toString()
}

internal fun ToolDefinition.toModelPromptLine(): String {
    val permissions = permissionPolicy.requiredAndroidPermissions.sorted().joinToString(",").ifBlank { "none" }
    return "- $name: $description; inputSchema=${toInputJsonSchema()}; risk=${risk.name}; " +
        "approval=${approvalPolicy.name}; androidPermissions=$permissions; " +
        "supportsBackground=${permissionPolicy.supportsBackground}; verification=${verificationPolicy.name}"
}

private fun ToolInputType.toJsonType(): String = when (this) {
    ToolInputType.STRING -> "string"
    ToolInputType.INTEGER -> "integer"
    ToolInputType.NUMBER -> "number"
    ToolInputType.BOOLEAN -> "boolean"
}
