package com.longdev.xiaoling.device

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DeviceActionCodec {
    fun encode(outcome: DeviceActionOutcome): String {
        return buildJsonObject {
            put("action", outcome.action)
            outcome.beforeSnapshotId?.let { put("before_snapshot_id", it) }
            put("verified", outcome.verified)
            put("message", outcome.message)
            // long: type_text 的动作结果会进入 ToolResult、答案摘要和后续上下文；只保留后置窗口事实，避免把输入框回读原文再次持久化。
            if (outcome.action == "type_text") {
                put(
                    "after_snapshot",
                    buildJsonObject {
                        put("package", outcome.afterSnapshot.packageName)
                        put("window_id", outcome.afterSnapshot.windowId)
                        put("window_generation", outcome.afterSnapshot.windowGeneration)
                        put("captured_at", outcome.afterSnapshot.capturedAt)
                        put("redacted_node_count", outcome.afterSnapshot.redactedNodeCount)
                        put("node_count", outcome.afterSnapshot.nodes.size)
                        put("truncated", outcome.afterSnapshot.truncated)
                    },
                )
            } else {
                put("after_snapshot", DeviceSnapshotCodec.toJson(outcome.afterSnapshot))
            }
        }.toString()
    }
}
