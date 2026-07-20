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
            put("after_snapshot", DeviceSnapshotCodec.toJson(outcome.afterSnapshot))
        }.toString()
    }
}
