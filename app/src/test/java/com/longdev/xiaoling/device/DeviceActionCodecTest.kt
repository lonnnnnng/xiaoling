package com.longdev.xiaoling.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionCodecTest {
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
