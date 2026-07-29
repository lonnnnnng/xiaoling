package com.longdev.xiaoling.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
        val packageName = json["package"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return null
        val nodes = json["nodes"]?.jsonArray ?: return null
        val redactedNodeCount = json["redacted_node_count"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it in 0..nodes.size.toLong() }
            ?.toInt()
            ?: return null
        val capturedAt = json["captured_at"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it >= 0L }
            ?: return null
        val truncated = json["truncated"]?.jsonPrimitive?.booleanOrNull ?: return null

        // long: Workflow 历史页只接收固定白名单字段；节点正文、窗口标题、ref 与坐标即使存在于账本 JSON，也不能穿透到 UI 状态。
        DeviceSnapshotSummary(
            packageName = packageName,
            nodeCount = nodes.size,
            redactedNodeCount = redactedNodeCount,
            truncated = truncated,
            capturedAt = capturedAt,
        )
    }.getOrNull()
}
