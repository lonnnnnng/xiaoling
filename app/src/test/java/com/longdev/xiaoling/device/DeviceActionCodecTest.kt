package com.longdev.xiaoling.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionCodecTest {
    @Test
    fun typeTextResultDoesNotSerializeReadBackTextOrNodeReference() {
        val inputText = "stage279_exact_readback"
        val encoded = DeviceActionCodec.encode(
            DeviceActionOutcome(
                action = "type_text",
                beforeSnapshotId = "snapshot-before",
                afterSnapshot = DeviceSnapshot(
                    snapshotId = "snapshot-after",
                    packageName = "com.android.settings.intelligence",
                    windowTitle = "搜索设置",
                    windowId = 7,
                    windowGeneration = 9L,
                    capturedAt = 2_000L,
                    expiresAt = 32_000L,
                    nodes = listOf(
                        DeviceSnapshotNode(
                            index = 0,
                            parentIndex = null,
                            depth = 0,
                            role = "edit_text",
                            text = inputText,
                            description = null,
                            hint = "搜索设置",
                            bounds = DeviceBounds(0, 0, 100, 100),
                            enabled = true,
                            checked = null,
                            selected = false,
                            redacted = false,
                            ref = "r1",
                            actions = setOf(DeviceNodeAction.TYPE_TEXT),
                        ),
                    ),
                    redactedNodeCount = 0,
                    truncated = false,
                ),
                verified = true,
                message = "节点动作已执行，并完成后置界面观察",
                typeTextReadBack = DeviceTypeTextReadBack(nodePath = listOf(0), text = inputText),
            ),
        )

        assertTrue(encoded.contains("\"action\":\"type_text\""))
        assertTrue(encoded.contains("\"verified\":true"))
        assertFalse(encoded.contains(inputText))
        assertFalse(encoded.contains("\"ref\""))
        assertFalse(encoded.contains("\"nodes\""))
    }

    @Test
    fun transientSwipeEvidenceIsNotSerializedIntoToolOutput() {
        val privateAnchor = "a".repeat(64)
        val viewport = DeviceSwipeViewportEvidence(
            packageName = "com.example.safe",
            windowId = 7,
            windowGeneration = 8L,
            targetFingerprint = "b".repeat(64),
            anchors = listOf(DeviceSwipeVisibleAnchor(privateAnchor, centerX = 100, centerY = 200)),
        )
        val encoded = DeviceActionCodec.encode(
            DeviceActionOutcome(
                action = "swipe",
                beforeSnapshotId = "snapshot-before",
                afterSnapshot = DeviceSnapshot(
                    snapshotId = "snapshot-after",
                    packageName = "com.example.safe",
                    windowTitle = "列表",
                    windowId = 7,
                    windowGeneration = 9L,
                    capturedAt = 2_000L,
                    expiresAt = 32_000L,
                    nodes = emptyList(),
                    redactedNodeCount = 0,
                    truncated = false,
                ),
                verified = true,
                message = "verified",
                swipeEvidence = DeviceSwipeVerificationEvidence(viewport, viewport.copy(windowGeneration = 9L)),
            ),
        )

        assertTrue(encoded.contains("\"action\":\"swipe\""))
        assertFalse(encoded.contains(privateAnchor))
        assertFalse(encoded.contains("targetFingerprint"))
        assertFalse(encoded.contains("swipeEvidence"))
    }
}
