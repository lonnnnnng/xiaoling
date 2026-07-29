package com.longdev.xiaoling.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class DeviceSnapshotSummary(
    val packageName: String,
    val nodeCount: Int,
    val redactedNodeCount: Int,
    val truncated: Boolean,
    val capturedAt: Long,
)

object DeviceSnapshotCodec {
    fun encode(snapshot: DeviceSnapshot): String {
        return toJson(snapshot).toString()
    }

    internal fun toJson(snapshot: DeviceSnapshot) = buildJsonObject {
            put("snapshot_id", snapshot.snapshotId)
            put("package", snapshot.packageName)
            snapshot.windowTitle?.let { put("window_title", it) }
            put("window_id", snapshot.windowId)
            put("window_generation", snapshot.windowGeneration)
            put("captured_at", snapshot.capturedAt)
            put("expires_at", snapshot.expiresAt)
            put("redacted_node_count", snapshot.redactedNodeCount)
            put("truncated", snapshot.truncated)
            put(
                "nodes",
                buildJsonArray {
                    snapshot.nodes.forEach { node ->
                        add(
                            buildJsonObject {
                                put("index", node.index)
                                node.parentIndex?.let { put("parent_index", it) }
                                put("depth", node.depth)
                                put("role", node.role)
                                node.text?.let { put("text", it) }
                                node.description?.let { put("description", it) }
                                node.hint?.let { put("hint", it) }
                                put(
                                    "bounds",
                                    JsonArray(
                                        listOf(
                                            node.bounds.left,
                                            node.bounds.top,
                                            node.bounds.right,
                                            node.bounds.bottom,
                                        ).map { kotlinx.serialization.json.JsonPrimitive(it) },
                                    ),
                                )
                                put("enabled", node.enabled)
                                node.checked?.let { put("checked", it) }
                                put("selected", node.selected)
                                put("redacted", node.redacted)
                                node.ref?.let { put("ref", it) }
                                put(
                                    "actions",
                                    JsonArray(node.actions.map { kotlinx.serialization.json.JsonPrimitive(it.name.lowercase()) }),
                                )
                            },
                        )
                    }
                },
            )
        }

    fun decodeSummary(value: String): DeviceSnapshotSummary? = runCatching {
        val json = Json.parseToJsonElement(value).jsonObject
        json.stringValue("snapshot_id")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val packageName = json.stringValue("package")
            ?.takeIf(String::isNotBlank)
            ?: return null
        json["window_title"]?.let { title ->
            if (title !is JsonPrimitive || !title.isString) return null
        }
        json.intValue("window_id") ?: return null
        json.longValue("window_generation")?.takeIf { it >= 0L } ?: return null
        val nodes = json["nodes"]?.jsonArray ?: return null
        val redactedNodeCount = json.longValue("redacted_node_count")
            ?.takeIf { it in 0..nodes.size.toLong() }
            ?.toInt()
            ?: return null
        val capturedAt = json.longValue("captured_at")
            ?.takeIf { it >= 0L }
            ?: return null
        json.longValue("expires_at")?.takeIf { it >= capturedAt } ?: return null
        val truncated = json.booleanValue("truncated") ?: return null
        if (!nodes.withIndex().all { (index, node) ->
                (node as? JsonObject)?.isStructurallyValidNode(index) == true
            }
        ) {
            return null
        }
        if (nodes.count { it.jsonObject.booleanValue("redacted") == true } != redactedNodeCount) return null

        // long: Workflow 历史页只接收固定白名单字段；节点正文、窗口标题、ref 与坐标即使存在于账本 JSON，也不能穿透到 UI 状态。
        DeviceSnapshotSummary(
            packageName = packageName,
            nodeCount = nodes.size,
            redactedNodeCount = redactedNodeCount,
            truncated = truncated,
            capturedAt = capturedAt,
        )
    }.getOrNull()

    private fun JsonObject.isStructurallyValidNode(expectedIndex: Int): Boolean {
        if (intValue("index") != expectedIndex) return false
        this["parent_index"]?.let {
            val parentIndex = (it as? JsonPrimitive)?.intOrNull ?: return false
            if (parentIndex !in 0 until expectedIndex) return false
        }
        if (intValue("depth")?.takeIf { it >= 0 } == null) return false
        if (stringValue("role")?.takeIf(String::isNotBlank) == null) return false
        OPTIONAL_NODE_TEXT_FIELDS.forEach { key ->
            this[key]?.let { value ->
                if (value !is JsonPrimitive || !value.isString) return false
            }
        }
        val bounds = this["bounds"] as? JsonArray ?: return false
        if (bounds.size != 4 || bounds.any { (it as? JsonPrimitive)?.intOrNull == null }) return false
        if (booleanValue("enabled") == null || booleanValue("selected") == null || booleanValue("redacted") == null) {
            return false
        }
        this["checked"]?.let {
            if ((it as? JsonPrimitive)?.booleanOrNull == null) return false
        }
        val actions = this["actions"] as? JsonArray ?: return false
        return actions.all { action ->
            val primitive = action as? JsonPrimitive
            primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull in DEVICE_NODE_ACTION_NAMES
        }
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.takeIf(JsonPrimitive::isString)?.contentOrNull
    }

    private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private val OPTIONAL_NODE_TEXT_FIELDS = setOf("text", "description", "hint", "ref")
    private val DEVICE_NODE_ACTION_NAMES = DeviceNodeAction.values().mapTo(mutableSetOf()) { it.name.lowercase() }
}
