package com.longdev.xiaoling.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNodeReferenceStoreTest {
    @Test
    fun referenceExpiresAndCannotCrossWindowGeneration() {
        val store = DeviceNodeReferenceStore()
        store.replace(
            snapshotId = "snapshot-1",
            windowGeneration = 4L,
            expiresAt = 31_000L,
            references = listOf(
                DeviceNodeReference(
                    ref = "r1",
                    nodePath = listOf(0, 2),
                    fingerprint = "button:confirm",
                    actions = setOf(DeviceNodeAction.TAP),
                ),
            ),
        )

        assertEquals(
            DeviceNodeReferenceResolution.Current(listOf(0, 2), "button:confirm", setOf(DeviceNodeAction.TAP)),
            store.resolve("snapshot-1", "r1", currentWindowGeneration = 4L, nowMillis = 30_999L),
        )
        assertEquals(
            DeviceNodeReferenceResolution.Expired,
            store.resolve("snapshot-1", "r1", currentWindowGeneration = 4L, nowMillis = 31_000L),
        )

        store.replace(
            snapshotId = "snapshot-2",
            windowGeneration = 8L,
            expiresAt = 60_000L,
            references = listOf(DeviceNodeReference("r1", listOf(1), "field:query", setOf(DeviceNodeAction.TYPE_TEXT))),
        )
        assertEquals(
            DeviceNodeReferenceResolution.WindowChanged,
            store.resolve("snapshot-2", "r1", currentWindowGeneration = 9L, nowMillis = 40_000L),
        )
        assertEquals(
            DeviceNodeReferenceResolution.SnapshotNotFound,
            store.resolve("snapshot-1", "r1", currentWindowGeneration = 8L, nowMillis = 40_000L),
        )
    }

    @Test
    fun missingReferenceFailsWithoutFallingBackToCoordinates() {
        val store = DeviceNodeReferenceStore()
        store.replace(
            snapshotId = "snapshot-1",
            windowGeneration = 1L,
            expiresAt = 10_000L,
            references = listOf(DeviceNodeReference("r1", listOf(0), "button:save", setOf(DeviceNodeAction.TAP))),
        )

        assertEquals(
            DeviceNodeReferenceResolution.ReferenceNotFound,
            store.resolve("snapshot-1", "r999", currentWindowGeneration = 1L, nowMillis = 1_000L),
        )
    }
}
