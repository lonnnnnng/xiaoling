package com.longdev.xiaoling.device

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceObservationControllerTest {
    @Test
    fun openAppAcceptsObservedPackageFromSameOemAppFamily() = runTest {
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = simpleWindow(packageName = "com.longdev.xiaoling", generation = 10L),
            launchResult = true,
            windowAfterLaunch = simpleWindow(packageName = "com.google.android.deskclock", generation = 11L),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { 2_000L },
            snapshotIdFactory = { "snapshot-after-open" },
        )

        val result = controller.openApp("com.android.deskclock") as DeviceActionCapture.Success

        assertTrue(result.outcome.verified)
        assertEquals("com.google.android.deskclock", result.outcome.afterSnapshot.packageName)
        assertEquals(listOf("com.android.deskclock"), gateway.launchedPackages)
    }

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
    fun swipeVerifiesChangedContentMovingInRequestedDirectionWithOpaqueEvidence() = runTest {
        val beforeWindow = scrollableWindow(
            generation = 41L,
            labels = listOf(
                "stage123-before-A" to 100,
                "stage123-common-B" to 200,
                "stage123-common-C" to 300,
            ),
        )
        val afterWindow = scrollableWindow(
            generation = 42L,
            labels = listOf(
                "stage123-common-B" to 100,
                "stage123-common-C" to 200,
                "stage123-after-D" to 300,
            ),
        )
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = beforeWindow,
            nodeActionResult = RawDeviceActionResult.Performed,
            windowAfterNodeAction = afterWindow,
        )
        val snapshotIds = mutableListOf("snapshot-before-stage123", "snapshot-after-stage123")
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { if (gateway.window?.generation == 41L) 1_000L else 2_000L },
            snapshotIdFactory = { snapshotIds.removeAt(0) },
            swipeEvidenceKey = "stage123-fixed-hmac-key-material".toByteArray(),
        )
        val before = controller.capture() as DeviceSnapshotCapture.Success

        val result = controller.swipe(
            snapshotId = before.snapshot.snapshotId,
            ref = "r1",
            direction = DeviceScrollDirection.UP,
        ) as DeviceActionCapture.Success

        assertTrue(result.outcome.verified)
        val evidence = requireNotNull(result.outcome.swipeEvidence)
        assertEquals(41L, evidence.beforeViewport.windowGeneration)
        assertEquals(42L, evidence.afterViewport.windowGeneration)
        assertEquals(evidence.beforeViewport.targetFingerprint, evidence.afterViewport.targetFingerprint)
        assertTrue(evidence.beforeViewport.anchors.size >= 2)
        assertTrue(evidence.afterViewport.anchors.size >= 2)
        assertTrue(
            (evidence.beforeViewport.anchors + evidence.afterViewport.anchors)
                .all { it.fingerprint.matches(Regex("[0-9a-f]{64}")) },
        )
        val serializedEvidence = evidence.toString()
        listOf(
            "stage123-before-A",
            "stage123-common-B",
            "stage123-common-C",
            "stage123-after-D",
            "snapshot-before-stage123",
            "snapshot-after-stage123",
            "r1",
            "stage123-fixed-hmac-key-material",
        ).forEach { sensitiveValue ->
            assertFalse(serializedEvidence.contains(sensitiveValue))
        }
    }

    @Test
    fun swipeDoesNotVerifyWhenVisibleContentSetIsUnchanged() = runTest {
        val outcome = swipeOutcome(
            beforeLabels = listOf(
                "unchanged-A" to 200,
                "unchanged-B" to 300,
                "unchanged-C" to 400,
            ),
            afterLabels = listOf(
                "unchanged-A" to 100,
                "unchanged-B" to 200,
                "unchanged-C" to 300,
            ),
            direction = DeviceScrollDirection.UP,
        )

        assertFalse(outcome.verified)
        assertTrue(outcome.swipeEvidence != null)
    }

    @Test
    fun swipeDoesNotVerifyWhenCommonAnchorsMoveOppositeRequestedDirection() = runTest {
        val outcome = swipeOutcome(
            beforeLabels = listOf(
                "opposite-A" to 100,
                "opposite-B" to 200,
                "opposite-C" to 300,
            ),
            afterLabels = listOf(
                "opposite-B" to 300,
                "opposite-C" to 400,
                "opposite-D" to 500,
            ),
            direction = DeviceScrollDirection.UP,
        )

        assertFalse(outcome.verified)
        assertTrue(outcome.swipeEvidence != null)
    }

    @Test
    fun swipeDropsAllDuplicateSemanticAnchorsAndFailsClosed() = runTest {
        val outcome = swipeOutcome(
            beforeLabels = listOf(
                "duplicate" to 100,
                "duplicate" to 200,
                "before-unique" to 300,
            ),
            afterLabels = listOf(
                "duplicate" to 50,
                "duplicate" to 150,
                "after-unique" to 250,
            ),
            direction = DeviceScrollDirection.UP,
        )

        assertFalse(outcome.verified)
        assertEquals(1, outcome.swipeEvidence?.beforeViewport?.anchors?.size)
        assertEquals(1, outcome.swipeEvidence?.afterViewport?.anchors?.size)
    }

    @Test
    fun differentControllerKeysProduceDifferentOpaqueAnchorFingerprints() = runTest {
        suspend fun fingerprintFor(key: String): String {
            val gateway = FakeGateway(
                authorized = true,
                connected = true,
                window = scrollableWindow(
                    generation = 41L,
                    labels = listOf("stable-A" to 100, "stable-B" to 200),
                ),
            )
            val controller = DeviceObservationController(
                agentEnabled = { true },
                gateway = gateway,
                clock = { 1_000L },
                snapshotIdFactory = { "snapshot-keyed" },
                swipeEvidenceKey = key.toByteArray(),
            )
            controller.capture() as DeviceSnapshotCapture.Success
            return requireNotNull(controller.inspectReference("snapshot-keyed", "r1").swipeViewport)
                .anchors
                .first()
                .fingerprint
        }

        val first = fingerprintFor("stage123-first-controller-key-data")
        val second = fingerprintFor("stage123-second-controller-key-data")

        assertFalse(first == second)
    }

    @Test
    fun anchorFingerprintsAreScopedToTheirScrollableTarget() = runTest {
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = twoScrollableTargetsWindow(),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { 1_000L },
            snapshotIdFactory = { "snapshot-target-scoped" },
            swipeEvidenceKey = "stage123-target-scoped-hmac-key-data".toByteArray(),
        )
        val capture = controller.capture() as DeviceSnapshotCapture.Success
        assertEquals(listOf("r1", "r2"), capture.references.map(DeviceNodeReference::ref))

        val firstViewport = requireNotNull(
            controller.inspectReference("snapshot-target-scoped", "r1").swipeViewport,
        )
        val secondViewport = requireNotNull(
            controller.inspectReference("snapshot-target-scoped", "r2").swipeViewport,
        )

        assertFalse(firstViewport.targetFingerprint == secondViewport.targetFingerprint)
        assertEquals(1, firstViewport.anchors.size)
        assertEquals(1, secondViewport.anchors.size)
        assertFalse(firstViewport.anchors.single().fingerprint == secondViewport.anchors.single().fingerprint)
    }

    @Test
    fun inspectReferenceFailsClosedWhenWindowChangesWhileEvidenceIsBuilt() = runTest {
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = scrollableWindow(
                generation = 41L,
                labels = listOf("generation-A" to 100, "generation-B" to 200),
            ),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { 1_000L },
            snapshotIdFactory = { "snapshot-generation-change" },
            swipeEvidenceKey = "stage123-generation-change-key-data".toByteArray(),
        )
        controller.capture() as DeviceSnapshotCapture.Success
        gateway.reportedWindowGenerations += listOf(41L, 42L)

        val inspection = controller.inspectReference("snapshot-generation-change", "r1")

        assertEquals(42L, inspection.currentWindowGeneration)
        assertFalse(inspection.matched)
        assertEquals(null, inspection.target)
        assertEquals(null, inspection.swipeViewport)
    }

    @Test
    fun inspectReferenceReturnsOnlyCurrentSwipeViewportAndClearRevokesIt() = runTest {
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = scrollableWindow(
                generation = 41L,
                labels = listOf("inspect-A" to 100, "inspect-B" to 200),
            ),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { 1_000L },
            snapshotIdFactory = { "snapshot-inspect-swipe" },
            swipeEvidenceKey = "stage123-inspection-hmac-key-data".toByteArray(),
        )
        controller.capture() as DeviceSnapshotCapture.Success

        val inspection = controller.inspectReference("snapshot-inspect-swipe", "r1")

        assertTrue(inspection.matched)
        assertEquals(41L, inspection.swipeViewport?.windowGeneration)
        assertEquals(2, inspection.swipeViewport?.anchors?.size)
        assertTrue(inspection.swipeViewport?.targetFingerprint.orEmpty().matches(Regex("[0-9a-f]{64}")))
        assertFalse(inspection.swipeViewport.toString().contains("inspect-A"))
        assertFalse(inspection.swipeViewport.toString().contains("inspect-B"))

        controller.clearReferences()

        val revoked = controller.inspectReference("snapshot-inspect-swipe", "r1")
        assertFalse(revoked.matched)
        assertEquals(null, revoked.swipeViewport)
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

    private fun simpleWindow(packageName: String, generation: Long): RawDeviceWindow =
        actionableWindow(generation).copy(packageName = packageName)

    private fun scrollableWindow(
        generation: Long,
        labels: List<Pair<String, Int>>,
    ): RawDeviceWindow = RawDeviceWindow(
        packageName = "com.example.safe",
        windowTitle = "列表",
        windowId = 9,
        generation = generation,
        root = RawDeviceNode(
            className = "android.widget.ScrollView",
            text = null,
            contentDescription = null,
            hintText = null,
            bounds = DeviceBounds(0, 0, 1080, 1000),
            visibleToUser = true,
            enabled = true,
            password = false,
            clickable = false,
            editable = false,
            scrollable = true,
            checkable = false,
            checked = false,
            selected = false,
            nodePath = emptyList(),
            children = labels.mapIndexed { index, (label, top) ->
                RawDeviceNode(
                    className = "android.widget.TextView",
                    text = label,
                    contentDescription = null,
                    hintText = null,
                    bounds = DeviceBounds(20, top, 800, top + 60),
                    visibleToUser = true,
                    enabled = true,
                    password = false,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    checkable = false,
                    checked = false,
                    selected = false,
                    nodePath = listOf(index),
                    children = emptyList(),
                )
            },
        ),
    )

    private fun twoScrollableTargetsWindow(): RawDeviceWindow {
        fun scrollTarget(targetIndex: Int): RawDeviceNode = RawDeviceNode(
            className = "android.widget.ScrollView",
            text = null,
            contentDescription = null,
            hintText = null,
            bounds = DeviceBounds(targetIndex * 540, 0, (targetIndex + 1) * 540, 1000),
            visibleToUser = true,
            enabled = true,
            password = false,
            clickable = false,
            editable = false,
            scrollable = true,
            checkable = false,
            checked = false,
            selected = false,
            nodePath = listOf(targetIndex),
            children = listOf(
                RawDeviceNode(
                    className = "android.widget.TextView",
                    text = "shared-semantic-anchor",
                    contentDescription = null,
                    hintText = null,
                    bounds = DeviceBounds(targetIndex * 540 + 20, 100, targetIndex * 540 + 500, 160),
                    visibleToUser = true,
                    enabled = true,
                    password = false,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    checkable = false,
                    checked = false,
                    selected = false,
                    nodePath = listOf(targetIndex, 0),
                    children = emptyList(),
                ),
            ),
        )
        return RawDeviceWindow(
            packageName = "com.example.safe",
            windowTitle = "双列表",
            windowId = 9,
            generation = 41L,
            root = RawDeviceNode(
                className = "android.view.View",
                text = null,
                contentDescription = null,
                hintText = null,
                bounds = DeviceBounds(0, 0, 1080, 1000),
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
                children = listOf(scrollTarget(0), scrollTarget(1)),
            ),
        )
    }

    private suspend fun swipeOutcome(
        beforeLabels: List<Pair<String, Int>>,
        afterLabels: List<Pair<String, Int>>,
        direction: DeviceScrollDirection,
    ): DeviceActionOutcome {
        val gateway = FakeGateway(
            authorized = true,
            connected = true,
            window = scrollableWindow(generation = 41L, labels = beforeLabels),
            nodeActionResult = RawDeviceActionResult.Performed,
            windowAfterNodeAction = scrollableWindow(generation = 42L, labels = afterLabels),
        )
        val snapshotIds = mutableListOf("snapshot-before-swipe", "snapshot-after-swipe")
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
            clock = { if (gateway.window?.generation == 41L) 1_000L else 2_000L },
            snapshotIdFactory = { snapshotIds.removeAt(0) },
            swipeEvidenceKey = "stage123-controller-hmac-key-data".toByteArray(),
        )
        val before = controller.capture() as DeviceSnapshotCapture.Success
        return (controller.swipe(before.snapshot.snapshotId, "r1", direction) as DeviceActionCapture.Success).outcome
    }

    private fun DeviceSnapshotCapture.failureOrNull(): DeviceSnapshotFailure? {
        return (this as DeviceSnapshotCapture.Failed).reason
    }

    private class FakeGateway(
        var authorized: Boolean = false,
        var connected: Boolean = false,
        var window: RawDeviceWindow? = null,
        var nodeActionResult: RawDeviceActionResult = RawDeviceActionResult.Failed,
        var windowAfterNodeAction: RawDeviceWindow? = null,
        var launchResult: Boolean = false,
        var windowAfterLaunch: RawDeviceWindow? = null,
    ) : DeviceAccessibilityGateway {
        var captureCalled: Boolean = false
        val reportedWindowGenerations = mutableListOf<Long>()
        val launchedPackages = mutableListOf<String>()

        override fun isServiceAuthorized(): Boolean = authorized

        override fun isServiceConnected(): Boolean = connected

        override fun currentWindowGeneration(): Long = if (reportedWindowGenerations.isNotEmpty()) {
            reportedWindowGenerations.removeAt(0)
        } else {
            window?.generation ?: 0L
        }

        override suspend fun captureRawWindow(): RawDeviceWindow? {
            captureCalled = true
            return window
        }

        override suspend fun launchApp(packageName: String): Boolean {
            launchedPackages += packageName
            if (launchResult) window = windowAfterLaunch ?: window
            return launchResult
        }

        override fun isHomePackage(packageName: String): Boolean = false

        override suspend fun performGlobalAction(action: DeviceGlobalAction): Boolean = false

        override suspend fun performNodeAction(
            expectedWindowGeneration: Long,
            nodePath: List<Int>,
            expectedFingerprint: String,
            action: DeviceNodeAction,
            text: String?,
            direction: DeviceScrollDirection?,
        ): RawDeviceActionResult {
            window = windowAfterNodeAction ?: window
            return nodeActionResult
        }
    }
}
