package com.longdev.xiaoling.device

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionControllerTest {
    @Test
    fun openAppIsRestrictedToAllowlistAndVerifiedByForegroundPackage() = runTest {
        val gateway = ActionGateway(window = window(packageName = "com.longdev.xiaoling", generation = 1L))
        val controller = controller(gateway)

        val blocked = controller.openApp("com.example.untrusted") as DeviceActionCapture.Failed
        assertEquals(DeviceActionFailure.APP_NOT_ALLOWED, blocked.reason)
        assertTrue(gateway.launchedPackages.isEmpty())

        gateway.onLaunch = { packageName ->
            gateway.window = window(packageName = packageName, generation = 2L)
        }
        val result = controller.openApp("com.android.calculator2") as DeviceActionCapture.Success

        assertEquals(listOf("com.android.calculator2"), gateway.launchedPackages)
        assertTrue(result.outcome.verified)
        assertEquals("com.android.calculator2", result.outcome.afterSnapshot.packageName)
    }

    @Test
    fun openAppRetriesTransientEmptyWindowBeforePostActionVerification() = runTest {
        val gateway = ActionGateway(window = window(packageName = "com.longdev.xiaoling", generation = 1L))
        val controller = controller(gateway)
        gateway.onLaunch = { packageName ->
            gateway.window = window(packageName = packageName, generation = 2L)
            gateway.transientEmptyCaptures = 2
        }

        val result = controller.openApp("com.android.deskclock") as DeviceActionCapture.Success

        assertTrue(result.outcome.verified)
        assertEquals("com.android.deskclock", result.outcome.afterSnapshot.packageName)
        assertEquals(3, gateway.captureCount)
    }

    @Test
    fun homeIsVerifiedOnlyWhenPostActionWindowBelongsToResolvedLauncher() = runTest {
        val gateway = ActionGateway(window = window(packageName = "com.android.settings", generation = 1L))
        val controller = controller(gateway)
        gateway.onGlobalAction = {
            gateway.window = window(packageName = "com.android.launcher3", generation = 2L)
        }

        val verified = controller.home() as DeviceActionCapture.Success

        assertTrue(verified.outcome.verified)
        assertEquals("com.android.launcher3", verified.outcome.afterSnapshot.packageName)

        gateway.window = window(packageName = "com.android.settings", generation = 3L)
        gateway.onGlobalAction = {
            gateway.window = window(packageName = "com.example.not.launcher", generation = 4L)
        }
        val rejected = controller.home() as DeviceActionCapture.Success

        assertFalse(rejected.outcome.verified)
        assertEquals("com.example.not.launcher", rejected.outcome.afterSnapshot.packageName)
    }

    @Test
    fun tapRequiresCurrentRefAndVerifiesWindowChange() = runTest {
        val gateway = ActionGateway(window = window(generation = 4L, node = node(text = "继续", clickable = true)))
        val controller = controller(gateway)
        val snapshot = (controller.capture() as DeviceSnapshotCapture.Success).snapshot

        gateway.nodeActionResult = RawDeviceActionResult.Performed
        gateway.onNodeAction = {
            gateway.window = window(generation = 5L, node = node(text = "完成", clickable = true))
        }
        val result = controller.tap(snapshot.snapshotId, "r1") as DeviceActionCapture.Success

        assertTrue(result.outcome.verified)
        assertEquals("tap", result.outcome.action)
        assertEquals(snapshot.snapshotId, result.outcome.beforeSnapshotId)
        assertEquals(1, gateway.nodeActionCount)
    }

    @Test
    fun staleOrUnsupportedReferenceNeverReachesAccessibilityAction() = runTest {
        val gateway = ActionGateway(window = window(generation = 7L, node = node(text = "标题")))
        val controller = controller(gateway)
        val snapshot = (controller.capture() as DeviceSnapshotCapture.Success).snapshot

        val missingAction = controller.tap(snapshot.snapshotId, "r1") as DeviceActionCapture.Failed
        assertEquals(DeviceActionFailure.REFERENCE_NOT_FOUND, missingAction.reason)
        assertEquals(0, gateway.nodeActionCount)

        val actionableGateway = ActionGateway(window = window(generation = 8L, node = node(text = "继续", clickable = true)))
        val actionableController = controller(actionableGateway)
        val actionableSnapshot = (actionableController.capture() as DeviceSnapshotCapture.Success).snapshot
        actionableGateway.window = window(generation = 9L, node = node(text = "继续", clickable = true))

        val stale = actionableController.tap(actionableSnapshot.snapshotId, "r1") as DeviceActionCapture.Failed
        assertEquals(DeviceActionFailure.WINDOW_CHANGED, stale.reason)
        assertEquals(0, actionableGateway.nodeActionCount)
    }

    @Test
    fun typeTextRejectsSensitiveInputAndVerifiesSafeTextByReadBack() = runTest {
        val gateway = ActionGateway(window = window(generation = 10L, node = node(editable = true)))
        val controller = controller(gateway)
        val first = (controller.capture() as DeviceSnapshotCapture.Success).snapshot

        val blocked = controller.typeText(first.snapshotId, "r1", "sk-abcdefghijklmnop123456") as DeviceActionCapture.Failed
        assertEquals(DeviceActionFailure.SENSITIVE_INPUT, blocked.reason)
        assertEquals(0, gateway.nodeActionCount)

        val second = (controller.capture() as DeviceSnapshotCapture.Success).snapshot
        gateway.nodeActionResult = RawDeviceActionResult.Performed
        gateway.onNodeAction = {
            gateway.window = window(generation = 11L, node = node(text = "hello stage3", editable = true))
        }
        val typed = controller.typeText(second.snapshotId, "r1", "hello stage3") as DeviceActionCapture.Success

        assertTrue(typed.outcome.verified)
        assertEquals(1, gateway.nodeActionCount)
        assertTrue(typed.outcome.afterSnapshot.nodes.any { it.text == "hello stage3" })
        assertEquals("hello stage3", typed.outcome.typeTextReadBack?.text)
        assertEquals(listOf(0), typed.outcome.typeTextReadBack?.nodePath)
    }

    @Test
    fun typeTextReadBackDoesNotAcceptExpectedTextFromAnotherNode() = runTest {
        val gateway = ActionGateway(window = window(generation = 20L, node = node(editable = true)))
        val controller = controller(gateway)
        val snapshot = (controller.capture() as DeviceSnapshotCapture.Success).snapshot
        gateway.nodeActionResult = RawDeviceActionResult.Performed
        gateway.onNodeAction = {
            gateway.window = window(
                generation = 21L,
                nodes = listOf(
                    node(text = "wrong target", editable = true),
                    node(text = "expected text"),
                ),
            )
        }

        val result = controller.typeText(snapshot.snapshotId, "r1", "expected text") as DeviceActionCapture.Success

        assertFalse(result.outcome.verified)
        assertEquals("wrong target", result.outcome.typeTextReadBack?.text)
        assertEquals(listOf(0), result.outcome.typeTextReadBack?.nodePath)
        assertTrue(result.outcome.afterSnapshot.nodes.any { it.text == "expected text" })
    }

    @Test
    fun performedTapWithoutObservableChangeRemainsUnverified() = runTest {
        val gateway = ActionGateway(window = window(generation = 12L, node = node(text = "无变化", clickable = true)))
        val controller = controller(gateway)
        val snapshot = (controller.capture() as DeviceSnapshotCapture.Success).snapshot
        gateway.nodeActionResult = RawDeviceActionResult.Performed

        val result = controller.tap(snapshot.snapshotId, "r1") as DeviceActionCapture.Success

        assertFalse(result.outcome.verified)
        assertTrue(result.outcome.message.contains("不足以证明"))
    }

    private fun controller(gateway: ActionGateway): DeviceObservationController {
        var snapshotIndex = 0
        return DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { 1_000L },
            snapshotIdFactory = { "snapshot-${++snapshotIndex}" },
        )
    }

    private fun window(
        packageName: String = "com.longdev.xiaoling",
        generation: Long,
        node: RawDeviceNode = node(text = "页面"),
    ): RawDeviceWindow = window(packageName, generation, listOf(node))

    private fun window(
        packageName: String = "com.longdev.xiaoling",
        generation: Long,
        nodes: List<RawDeviceNode>,
    ): RawDeviceWindow = RawDeviceWindow(
        packageName = packageName,
        windowTitle = "测试窗口",
        windowId = generation.toInt(),
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
            children = nodes.mapIndexed { index, child -> child.copy(nodePath = listOf(index)) },
        ),
    )

    private fun node(
        text: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ): RawDeviceNode = RawDeviceNode(
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        text = text,
        contentDescription = null,
        hintText = null,
        bounds = DeviceBounds(20, 100, 400, 180),
        visibleToUser = true,
        enabled = true,
        password = false,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        checkable = false,
        checked = false,
        selected = false,
        nodePath = listOf(0),
        children = emptyList(),
    )

    private class ActionGateway(
        var window: RawDeviceWindow,
    ) : DeviceAccessibilityGateway {
        val launchedPackages = mutableListOf<String>()
        var onLaunch: (String) -> Unit = {}
        var nodeActionResult: RawDeviceActionResult = RawDeviceActionResult.Failed
        var onNodeAction: () -> Unit = {}
        var onGlobalAction: (DeviceGlobalAction) -> Unit = {}
        var nodeActionCount: Int = 0
        var transientEmptyCaptures: Int = 0
        var captureCount: Int = 0

        override fun isServiceAuthorized(): Boolean = true

        override fun isServiceConnected(): Boolean = true

        override fun currentWindowGeneration(): Long = window.generation

        override suspend fun captureRawWindow(): RawDeviceWindow? {
            captureCount += 1
            if (transientEmptyCaptures > 0) {
                transientEmptyCaptures -= 1
                return null
            }
            return window
        }

        override suspend fun launchApp(packageName: String): Boolean {
            launchedPackages += packageName
            onLaunch(packageName)
            return true
        }

        override fun isHomePackage(packageName: String): Boolean = packageName == "com.android.launcher3"

        override suspend fun performGlobalAction(action: DeviceGlobalAction): Boolean {
            onGlobalAction(action)
            return true
        }

        override suspend fun performNodeAction(
            expectedWindowGeneration: Long,
            nodePath: List<Int>,
            expectedFingerprint: String,
            action: DeviceNodeAction,
            text: String?,
            direction: DeviceScrollDirection?,
        ): RawDeviceActionResult {
            nodeActionCount += 1
            onNodeAction()
            return nodeActionResult
        }
    }
}
