package com.longdev.xiaoling.device

class DeviceNodeReferenceStore {
    private var current: ReferenceSnapshot? = null

    @Synchronized
    fun replace(
        snapshotId: String,
        windowGeneration: Long,
        expiresAt: Long,
        references: List<DeviceNodeReference>,
    ) {
        current = ReferenceSnapshot(
            snapshotId = snapshotId,
            windowGeneration = windowGeneration,
            expiresAt = expiresAt,
            references = references.associateBy(DeviceNodeReference::ref),
        )
    }

    @Synchronized
    fun clear() {
        current = null
    }

    @Synchronized
    fun resolve(
        snapshotId: String,
        ref: String,
        currentWindowGeneration: Long,
        nowMillis: Long,
    ): DeviceNodeReferenceResolution {
        val snapshot = current?.takeIf { it.snapshotId == snapshotId }
            ?: return DeviceNodeReferenceResolution.SnapshotNotFound
        if (nowMillis >= snapshot.expiresAt) return DeviceNodeReferenceResolution.Expired
        if (currentWindowGeneration != snapshot.windowGeneration) return DeviceNodeReferenceResolution.WindowChanged
        val reference = snapshot.references[ref] ?: return DeviceNodeReferenceResolution.ReferenceNotFound
        return DeviceNodeReferenceResolution.Current(
            nodePath = reference.nodePath,
            fingerprint = reference.fingerprint,
            actions = reference.actions,
            enabled = reference.enabled,
            editable = reference.editable,
            redacted = reference.redacted,
        )
    }

    private data class ReferenceSnapshot(
        val snapshotId: String,
        val windowGeneration: Long,
        val expiresAt: Long,
        val references: Map<String, DeviceNodeReference>,
    )
}

sealed interface DeviceNodeReferenceResolution {
    data class Current(
        val nodePath: List<Int>,
        val fingerprint: String,
        val actions: Set<DeviceNodeAction>,
        val enabled: Boolean = true,
        val editable: Boolean = DeviceNodeAction.TYPE_TEXT in actions,
        val redacted: Boolean = false,
    ) : DeviceNodeReferenceResolution

    data object SnapshotNotFound : DeviceNodeReferenceResolution
    data object ReferenceNotFound : DeviceNodeReferenceResolution
    data object Expired : DeviceNodeReferenceResolution
    data object WindowChanged : DeviceNodeReferenceResolution
}
