package com.longdev.xiaoling.device

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceObservationControllerTest {
    @Test
    fun captureFailsClosedUntilAgentAuthorizationAndServiceAreReady() = runTest {
        val gateway = FakeGateway()
        var enabled = false
        val controller = DeviceObservationController(
            agentEnabled = { enabled },
            gateway = gateway,
            clock = { 1_000L },
            snapshotIdFactory = { "snapshot-health" },
        )

        assertEquals(DeviceAgentHealthState.AGENT_DISABLED, controller.health())
        assertEquals(DeviceSnapshotFailure.AGENT_DISABLED, controller.capture().failureOrNull())
        assertFalse(gateway.captureCalled)

        enabled = true
        assertEquals(DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED, controller.health())
        assertEquals(DeviceSnapshotFailure.ACCESSIBILITY_NOT_AUTHORIZED, controller.capture().failureOrNull())

        gateway.authorized = true
        assertEquals(DeviceAgentHealthState.SERVICE_DISCONNECTED, controller.health())
        assertEquals(DeviceSnapshotFailure.SERVICE_DISCONNECTED, controller.capture().failureOrNull())

        gateway.connected = true
        assertEquals(DeviceAgentHealthState.READY, controller.health())
        assertEquals(DeviceSnapshotFailure.NO_ACTIVE_WINDOW, controller.capture().failureOrNull())
    }

    @Test
    fun successfulCaptureRegistersSnapshotReferences() = runTest {
        val referenceStore = DeviceNodeReferenceStore()
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = RawDeviceWindow(
                packageName = "com.example.safe",
                windowTitle = "首页",
                windowId = 3,
                generation = 7L,
                root = RawDeviceNode(
                    className = "android.view.View",
                    text = null,
                    contentDescription = null,
                    hintText = null,
                    bounds = DeviceBounds(0, 0, 1080, 2200),
                    visibleToUser = true,
                    enabled = true,
                    password = false,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    checkable = false,
                    checked = false,
                    selected = false,
                    nodePath = emptyList(),
                    children = listOf(
                        RawDeviceNode(
                            className = "android.widget.Button",
                            text = "继续",
                            contentDescription = null,
                            hintText = null,
                            bounds = DeviceBounds(20, 100, 220, 180),
                            visibleToUser = true,
                            enabled = true,
                            password = false,
                            clickable = true,
                            editable = false,
                            scrollable = false,
                            checkable = false,
                            checked = false,
                            selected = false,
                            nodePath = listOf(0),
                            children = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            referenceStore = referenceStore,
            clock = { 2_000L },
            snapshotIdFactory = { "snapshot-ready" },
        )

        val result = controller.capture() as DeviceSnapshotCapture.Success

        assertEquals("snapshot-ready", result.snapshot.snapshotId)
        assertEquals("r1", result.snapshot.nodes.single().ref)
        assertTrue(DeviceSnapshotCodec.encode(result.snapshot).contains("继续"))
        assertEquals(
            DeviceNodeReferenceResolution.Current(
                listOf(0),
                result.references.single().fingerprint,
                setOf(DeviceNodeAction.TAP),
            ),
            referenceStore.resolve("snapshot-ready", "r1", currentWindowGeneration = 7L, nowMillis = 31_999L),
        )
    }

    @Test
    fun inspectReferenceReturnsCurrentEditableTargetEvidence() = runTest {
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = RawDeviceWindow(
                packageName = "com.example.safe",
                windowTitle = "搜索",
                windowId = 5,
                generation = 11L,
                root = RawDeviceNode(
                    className = "android.widget.EditText",
                    text = null,
                    contentDescription = null,
                    hintText = "请输入关键词",
                    bounds = DeviceBounds(20, 100, 900, 220),
                    visibleToUser = true,
                    enabled = true,
                    password = false,
                    clickable = false,
                    editable = true,
                    scrollable = false,
                    checkable = false,
                    checked = false,
                    selected = false,
                    nodePath = emptyList(),
                    children = emptyList(),
                ),
            ),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { 2_000L },
            snapshotIdFactory = { "snapshot-editable" },
        )
        controller.capture() as DeviceSnapshotCapture.Success

        val inspection = controller.inspectReference("snapshot-editable", "r1")

        assertTrue(inspection.matched)
        assertEquals(
            DeviceReferenceTargetInspection(
                enabled = true,
                editable = true,
                redacted = false,
                actions = setOf(DeviceNodeAction.TYPE_TEXT),
            ),
            inspection.target,
        )
    }

    @Test
    fun failedCaptureRevokesReferencesFromPreviousSuccessfulObservation() = runTest {
        val referenceStore = DeviceNodeReferenceStore()
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = actionableWindow(generation = 7L),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            referenceStore = referenceStore,
            clock = { 2_000L },
            snapshotIdFactory = { "snapshot-revoked" },
        )

        val success = controller.capture() as DeviceSnapshotCapture.Success
        assertEquals(
            DeviceNodeReferenceResolution.Current(
                listOf(0),
                success.references.single().fingerprint,
                setOf(DeviceNodeAction.TAP),
            ),
            referenceStore.resolve("snapshot-revoked", "r1", currentWindowGeneration = 7L, nowMillis = 2_001L),
        )

        gateway.window = null
        assertEquals(DeviceSnapshotFailure.NO_ACTIVE_WINDOW, controller.capture().failureOrNull())
        assertEquals(
            DeviceNodeReferenceResolution.SnapshotNotFound,
            referenceStore.resolve("snapshot-revoked", "r1", currentWindowGeneration = 7L, nowMillis = 2_002L),
        )
    }

    private fun actionableWindow(generation: Long): RawDeviceWindow = RawDeviceWindow(
        packageName = "com.example.safe",
        windowTitle = "首页",
        windowId = 3,
        generation = generation,
        root = RawDeviceNode(
            className = "android.view.View",
            text = null,
            contentDescription = null,
            hintText = null,
            bounds = DeviceBounds(0, 0, 1080, 2200),
            visibleToUser = true,
            enabled = true,
            password = false,
            clickable = false,
            editable = false,
            scrollable = false,
            checkable = false,
            checked = false,
            selected = false,
            nodePath = emptyList(),
            children = listOf(
                RawDeviceNode(
                    className = "android.widget.Button",
                    text = "继续",
                    contentDescription = null,
                    hintText = null,
                    bounds = DeviceBounds(20, 100, 220, 180),
                    visibleToUser = true,
                    enabled = true,
                    password = false,
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    checkable = false,
                    checked = false,
                    selected = false,
                    nodePath = listOf(0),
                    children = emptyList(),
                ),
            ),
        ),
    )

    private fun DeviceSnapshotCapture.failureOrNull(): DeviceSnapshotFailure? {
        return (this as DeviceSnapshotCapture.Failed).reason
    }

    private class FakeGateway(
        var authorized: Boolean = false,
        var connected: Boolean = false,
        var window: RawDeviceWindow? = null,
    ) : DeviceAccessibilityGateway {
        var captureCalled: Boolean = false

        override fun isServiceAuthorized(): Boolean = authorized

        override fun isServiceConnected(): Boolean = connected

        override fun currentWindowGeneration(): Long = window?.generation ?: 0L

        override suspend fun captureRawWindow(): RawDeviceWindow? {
            captureCalled = true
            return window
        }

        override suspend fun launchApp(packageName: String): Boolean = false

        override fun isHomePackage(packageName: String): Boolean = false

        override suspend fun performGlobalAction(action: DeviceGlobalAction): Boolean = false

        override suspend fun performNodeAction(
            expectedWindowGeneration: Long,
            nodePath: List<Int>,
            expectedFingerprint: String,
            action: DeviceNodeAction,
            text: String?,
            direction: DeviceScrollDirection?,
        ): RawDeviceActionResult = RawDeviceActionResult.Failed
    }
}
