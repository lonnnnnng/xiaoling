package com.longdev.xiaoling.device

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

internal class DeviceSwipeEvidencePolicy(
    secretKey: ByteArray,
) {
    private val secretKey = secretKey.copyOf()

    init {
        require(this.secretKey.size >= MIN_SECRET_KEY_BYTES) {
            "设备滚动证据 HMAC 密钥不得少于 256 bit"
        }
    }

    fun viewport(
        capture: DeviceSnapshotCapture.Success,
        targetNodePath: List<Int>,
    ): DeviceSwipeViewportEvidence? {
        val targetReference = capture.references.singleOrNull { reference ->
            reference.nodePath == targetNodePath && DeviceNodeAction.SWIPE in reference.actions
        } ?: return null
        val targetNode = capture.snapshot.nodes.singleOrNull { node -> node.ref == targetReference.ref }
            ?.takeUnless(DeviceSnapshotNode::redacted)
            ?: return null
        val targetFingerprint = hmac(
            listOf(
                TARGET_DOMAIN,
                capture.snapshot.packageName,
                capture.snapshot.windowId.toString(),
                targetNode.role,
                targetNodePath.size.toString(),
            ) + targetNodePath.map(Int::toString),
        )
        val nodesByIndex = capture.snapshot.nodes.associateBy(DeviceSnapshotNode::index)
        val candidates = capture.snapshot.nodes.mapNotNull { node ->
            if (
                node.redacted ||
                !node.isDescendantOf(targetNode.index, nodesByIndex) ||
                node.bounds.right <= node.bounds.left ||
                node.bounds.bottom <= node.bounds.top
            ) {
                return@mapNotNull null
            }
            val semanticValues = listOf(node.text, node.description, node.hint)
                .map { value -> value.orEmpty().trim() }
            if (semanticValues.all(String::isBlank)) return@mapNotNull null
            val fingerprint = hmac(
                listOf(
                    ANCHOR_DOMAIN,
                    targetFingerprint,
                    capture.snapshot.packageName,
                    capture.snapshot.windowId.toString(),
                    node.role,
                    semanticValues[0],
                    semanticValues[1],
                    semanticValues[2],
                    node.checked?.toString().orEmpty(),
                    node.selected.toString(),
                ),
            )
            fingerprint to DeviceSwipeVisibleAnchor(
                fingerprint = fingerprint,
                centerX = node.bounds.centerX(),
                centerY = node.bounds.centerY(),
            )
        }
        val uniqueAnchors = candidates
            .groupBy(Pair<String, DeviceSwipeVisibleAnchor>::first)
            .values
            // long: 同一 viewport 中重复文案无法稳定对应动作前后节点；全部移除而不是任取一个，避免把错误位移包装成成功滚动。
            .mapNotNull { matches -> matches.singleOrNull()?.second }
            .sortedBy(DeviceSwipeVisibleAnchor::fingerprint)
        return DeviceSwipeViewportEvidence(
            packageName = capture.snapshot.packageName,
            windowId = capture.snapshot.windowId,
            windowGeneration = capture.snapshot.windowGeneration,
            targetFingerprint = targetFingerprint,
            anchors = uniqueAnchors,
        )
    }

    fun isVerified(
        direction: DeviceScrollDirection,
        evidence: DeviceSwipeVerificationEvidence,
    ): Boolean {
        val before = evidence.beforeViewport
        val after = evidence.afterViewport
        if (
            before.packageName != after.packageName ||
            before.windowId != after.windowId ||
            after.windowGeneration <= before.windowGeneration ||
            before.targetFingerprint != after.targetFingerprint ||
            before.anchors.size < MIN_VISIBLE_ANCHORS ||
            after.anchors.size < MIN_VISIBLE_ANCHORS
        ) {
            return false
        }
        val beforeContent = before.anchors.mapTo(linkedSetOf(), DeviceSwipeVisibleAnchor::fingerprint)
        val afterContent = after.anchors.mapTo(linkedSetOf(), DeviceSwipeVisibleAnchor::fingerprint)
        if (beforeContent == afterContent) return false
        return DeviceSwipeDirectionVerifier.isVerified(direction, before.anchors, after.anchors)
    }

    private fun DeviceSnapshotNode.isDescendantOf(
        ancestorIndex: Int,
        nodesByIndex: Map<Int, DeviceSnapshotNode>,
    ): Boolean {
        var currentParentIndex = parentIndex
        while (currentParentIndex != null) {
            if (currentParentIndex == ancestorIndex) return true
            currentParentIndex = nodesByIndex[currentParentIndex]?.parentIndex
        }
        return false
    }

    private fun DeviceBounds.centerX(): Int = ((left.toLong() + right.toLong()) / 2L).toInt()

    private fun DeviceBounds.centerY(): Int = ((top.toLong() + bottom.toLong()) / 2L).toInt()

    private fun hmac(parts: List<String>): String {
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(parts.size)
                parts.forEach { part ->
                    val encoded = part.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            bytes.toByteArray()
        }
        return Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(secretKey, HMAC_ALGORITHM))
            doFinal(canonical)
        }.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val MIN_SECRET_KEY_BYTES = 32
        const val MIN_VISIBLE_ANCHORS = 2
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TARGET_DOMAIN = "device-swipe-target-v1"
        const val ANCHOR_DOMAIN = "device-swipe-anchor-v1"
    }
}

internal object DeviceSwipeDirectionVerifier {
    fun isVerified(
        direction: DeviceScrollDirection,
        beforeAnchors: List<DeviceSwipeVisibleAnchor>,
        afterAnchors: List<DeviceSwipeVisibleAnchor>,
    ): Boolean {
        val beforeByFingerprint = beforeAnchors.associateBy(DeviceSwipeVisibleAnchor::fingerprint)
        var directionObserved = false
        for (afterAnchor in afterAnchors) {
            val beforeAnchor = beforeByFingerprint[afterAnchor.fingerprint] ?: continue
            val deltaX = afterAnchor.centerX - beforeAnchor.centerX
            val deltaY = afterAnchor.centerY - beforeAnchor.centerY
            if (abs(deltaX) < MIN_DIRECTIONAL_DISPLACEMENT_PX && abs(deltaY) < MIN_DIRECTIONAL_DISPLACEMENT_PX) {
                continue
            }
            val (primary, cross) = when (direction) {
                DeviceScrollDirection.UP -> -deltaY to deltaX
                DeviceScrollDirection.DOWN -> deltaY to deltaX
                DeviceScrollDirection.LEFT -> -deltaX to deltaY
                DeviceScrollDirection.RIGHT -> deltaX to deltaY
            }
            // long: 任一显著共同锚点反向或横向占优都代表证据互相矛盾，不能由另一个正确锚点掩盖。
            if (primary < MIN_DIRECTIONAL_DISPLACEMENT_PX || primary <= abs(cross)) return false
            directionObserved = true
        }
        return directionObserved
    }

    private const val MIN_DIRECTIONAL_DISPLACEMENT_PX = 8
}
