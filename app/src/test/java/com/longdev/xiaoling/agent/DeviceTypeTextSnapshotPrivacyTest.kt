package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceBounds
import com.longdev.xiaoling.device.DeviceNodeAction
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotNode
import org.junit.Assert.*
import org.junit.Test

class DeviceTypeTextSnapshotPrivacyTest {
    private fun snapshot(text: String) = DeviceSnapshot("s", "com.android.settings", text, 1, 2, 100, 200,
        listOf(DeviceSnapshotNode(0, null, 0, "text_field", text, text, text, DeviceBounds(0, 0, 10, 10),
            true, null, false, false, "r1", setOf(DeviceNodeAction.TYPE_TEXT))), 0, false)

    @Test fun matchingDisplayFieldsAreRemovedWithoutChangingControllerIdentity() {
        val original = snapshot("public-input")
        val result = DeviceTypeTextSnapshotPrivacyProjection.redact(original)
        assertNull(result.windowTitle)
        assertNull(result.nodes.single().text)
        assertNull(result.nodes.single().description)
        assertNull(result.nodes.single().hint)
        assertEquals(original.nodes.single().copy(text = null, description = null, hint = null), result.nodes.single())
        assertEquals("public-input", original.nodes.single().text)
    }

    @Test fun repeatedConcatenatedNormalizedAndPartialDisplayCannotEchoInput() {
        for (display in listOf("前缀 public-input 后缀", "public input", "public-in", "搜索", "另一次输入")) {
            val original = snapshot(display)
            repeat(3) {
                val projected = DeviceTypeTextSnapshotPrivacyProjection.redact(original)
                assertNull(projected.windowTitle)
                assertNull(projected.nodes.single().text)
                assertNull(projected.nodes.single().description)
                assertNull(projected.nodes.single().hint)
                assertEquals(original.nodes.single().ref, projected.nodes.single().ref)
                assertEquals(display, original.nodes.single().text)
            }
        }
    }
}
