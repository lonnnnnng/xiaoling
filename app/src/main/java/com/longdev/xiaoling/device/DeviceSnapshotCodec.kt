package com.longdev.xiaoling.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
}
