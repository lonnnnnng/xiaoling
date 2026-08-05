package com.longdev.xiaoling.device

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionApprovalOverlayCoordinatorTest {
    @Test
    fun approvalBaselineWaitsForTwoConsecutiveStableSamples() {
        val stabilizer = DeviceActionApprovalBaselineStabilizer()
        val initial = targetSnapshot(generation = 1)
        val composeUpdated = targetSnapshot(generation = 2)

        assertNull(stabilizer.sample(initial))
        assertNull(stabilizer.sample(composeUpdated))
        assertEquals(composeUpdated, stabilizer.sample(composeUpdated))
    }

    @Test
    fun approvalBaselineDoesNotAcceptContinuouslyChangingPage() {
        val stabilizer = DeviceActionApprovalBaselineStabilizer()

        (1L..4L).forEach { generation ->
            assertNull(stabilizer.sample(targetSnapshot(generation)))
        }
    }

    @Test
    fun repeatedDetachEventsStaySuppressedUntilBaselineSettlement() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val baseline = baselineWindows()
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started
        val withOverlay = baseline + DeviceAccessibilityWindowSnapshot(
            id = OVERLAY_WINDOW_ID,
            ownedApprovalOverlay = true,
        )
        coordinator.observeWindows(
            eventWindowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = withOverlay,
        )
        assertTrue(coordinator.recordUserDecision(started.token, approved = true))

        val firstDetach = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )
        val trailingDetach = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )

        assertTrue(firstDetach.suppressGenerationInvalidation)
        assertNotNull(firstDetach.settleToken)
        assertNull(firstDetach.completion)
        assertTrue(trailingDetach.suppressGenerationInvalidation)
        assertNull(trailingDetach.completion)
        assertNull(trailingDetach.settleToken)
        assertEquals(
            DeviceActionApprovalOverlayDecisionKind.APPROVED,
            coordinator.settleDetachedOverlay(
                token = started.token,
                activeRootWindowId = TARGET_WINDOW_ID,
                windows = baseline,
            )?.kind,
        )
    }

    @Test
    fun settlementRejectsChangedActiveRootAfterOwnedOverlayDetach() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val baseline = baselineWindows()
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started
        val withOverlay = baseline + DeviceAccessibilityWindowSnapshot(
            id = OVERLAY_WINDOW_ID,
            ownedApprovalOverlay = true,
        )
        coordinator.observeWindows(
            eventWindowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = withOverlay,
        )
        assertTrue(coordinator.recordUserDecision(started.token, approved = true))
        val detached = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )

        assertEquals(started.token, detached.settleToken)
        assertEquals(
            DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED,
            coordinator.settleDetachedOverlay(
                token = started.token,
                activeRootWindowId = FOREIGN_WINDOW_ID,
                windows = baseline,
            )?.kind,
        )
        assertNull(coordinator.disconnect())
    }

    @Test
    fun settlementRejectsChangedWindowSetWhenNoTrailingEventArrives() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val baseline = baselineWindows()
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started
        val withOverlay = baseline + DeviceAccessibilityWindowSnapshot(
            id = OVERLAY_WINDOW_ID,
            ownedApprovalOverlay = true,
        )
        coordinator.observeWindows(
            eventWindowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = withOverlay,
        )
        assertTrue(coordinator.recordUserDecision(started.token, approved = true))
        val detached = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )

        assertEquals(started.token, detached.settleToken)
        assertEquals(
            DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED,
            coordinator.settleDetachedOverlay(
                token = started.token,
                activeRootWindowId = TARGET_WINDOW_ID,
                windows = baseline + DeviceAccessibilityWindowSnapshot(
                    id = FOREIGN_WINDOW_ID,
                    ownedApprovalOverlay = false,
                ),
            )?.kind,
        )
        assertNull(coordinator.disconnect())
    }

    @Test
    fun foreignWindowDuringDetachSettlementFailsClosedInsteadOfBeingSuppressed() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val baseline = baselineWindows()
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started
        val withOverlay = baseline + DeviceAccessibilityWindowSnapshot(
            id = OVERLAY_WINDOW_ID,
            ownedApprovalOverlay = true,
        )
        coordinator.observeWindows(
            eventWindowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = withOverlay,
        )
        assertTrue(coordinator.recordUserDecision(started.token, approved = true))
        val detached = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )
        assertEquals(started.token, detached.settleToken)

        val changed = coordinator.observeWindows(
            eventWindowId = FOREIGN_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline + DeviceAccessibilityWindowSnapshot(
                id = FOREIGN_WINDOW_ID,
                ownedApprovalOverlay = false,
            ),
        )

        assertFalse(changed.suppressGenerationInvalidation)
        assertTrue(changed.removeOverlay)
        assertEquals(DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED, changed.completion?.kind)
        assertNull(coordinator.settleDetachedOverlay(started.token, TARGET_WINDOW_ID, baseline))
    }

    @Test
    fun ownedOverlayKeepsTargetGenerationUntilVerifiedDetach() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val tracker = DeviceWindowGenerationTracker()
        val baseline = baselineWindows()
        val capturedGeneration = tracker.markCapturedWindow(TARGET_WINDOW_ID)
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started
        val withOverlay = baseline + DeviceAccessibilityWindowSnapshot(
            id = OVERLAY_WINDOW_ID,
            ownedApprovalOverlay = true,
        )

        val attached = coordinator.observeWindows(
            eventWindowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = withOverlay,
        )
        tracker.onAccessibilityEvent(
            windowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            suppressInvalidation = attached.suppressGenerationInvalidation,
        )
        assertEquals(capturedGeneration, tracker.currentGeneration())

        val targetWindowSetChanged = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = withOverlay,
        )
        assertTrue(targetWindowSetChanged.suppressGenerationInvalidation)
        tracker.onAccessibilityEvent(
            windowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            suppressInvalidation = targetWindowSetChanged.suppressGenerationInvalidation,
        )
        assertEquals(capturedGeneration, tracker.currentGeneration())

        assertTrue(coordinator.recordUserDecision(started.token, approved = true))
        val detached = coordinator.observeWindows(
            eventWindowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )
        tracker.onAccessibilityEvent(
            windowId = OVERLAY_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            suppressInvalidation = detached.suppressGenerationInvalidation,
        )

        assertNull(detached.completion)
        assertEquals(started.token, detached.settleToken)
        assertEquals(
            DeviceActionApprovalOverlayDecisionKind.APPROVED,
            coordinator.settleDetachedOverlay(started.token, TARGET_WINDOW_ID, baseline)?.kind,
        )
        assertEquals(capturedGeneration, tracker.currentGeneration())
    }

    @Test
    fun immediateDecisionBeforeOverlayWindowIdentityStillPreservesTargetGeneration() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val tracker = DeviceWindowGenerationTracker()
        val baseline = baselineWindows()
        val capturedGeneration = tracker.markCapturedWindow(TARGET_WINDOW_ID)
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started

        assertTrue(coordinator.recordOverlayAdded(started.token))
        assertTrue(coordinator.recordUserDecision(started.token, approved = true))
        val detached = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline,
        )
        tracker.onAccessibilityEvent(
            windowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            suppressInvalidation = detached.suppressGenerationInvalidation,
        )

        assertTrue(detached.suppressGenerationInvalidation)
        assertEquals(started.token, detached.settleToken)
        assertEquals(
            DeviceActionApprovalOverlayDecisionKind.APPROVED,
            coordinator.settleDetachedOverlay(started.token, TARGET_WINDOW_ID, baseline)?.kind,
        )
        assertEquals(capturedGeneration, tracker.currentGeneration())
    }

    @Test
    fun foreignWindowDuringApprovalFailsClosedAndInvalidatesTarget() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val tracker = DeviceWindowGenerationTracker()
        val baseline = baselineWindows()
        val capturedGeneration = tracker.markCapturedWindow(TARGET_WINDOW_ID)
        coordinator.begin(TARGET_WINDOW_ID, baseline)
        val windows = baseline + setOf(
            DeviceAccessibilityWindowSnapshot(OVERLAY_WINDOW_ID, ownedApprovalOverlay = true),
            DeviceAccessibilityWindowSnapshot(FOREIGN_WINDOW_ID, ownedApprovalOverlay = false),
        )

        val observation = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = windows,
        )
        tracker.onAccessibilityEvent(
            windowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            suppressInvalidation = observation.suppressGenerationInvalidation,
        )

        assertFalse(observation.suppressGenerationInvalidation)
        assertTrue(observation.removeOverlay)
        assertEquals(DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED, observation.completion?.kind)
        assertNotEquals(capturedGeneration, tracker.currentGeneration())
    }

    @Test
    fun targetContentChangeCancelsApprovalAndDuplicateRequestIsRejected() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val baseline = baselineWindows()
        val started = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Started
        val duplicate = coordinator.begin(TARGET_WINDOW_ID, baseline)
            as DeviceActionApprovalOverlayStart.Rejected

        assertEquals(DeviceActionApprovalOverlayDecisionKind.BUSY, duplicate.decision.kind)
        assertFalse(coordinator.recordUserDecision(started.token + 1, approved = true))

        val changed = coordinator.observeWindows(
            eventWindowId = TARGET_WINDOW_ID,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            activeRootWindowId = TARGET_WINDOW_ID,
            windows = baseline + DeviceAccessibilityWindowSnapshot(
                OVERLAY_WINDOW_ID,
                ownedApprovalOverlay = true,
            ),
        )

        assertEquals(DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED, changed.completion?.kind)
        assertTrue(changed.removeOverlay)
        assertNull(coordinator.disconnect())
    }

    @Test
    fun disconnectAndDetachTimeoutNeverApproveRequest() {
        val coordinator = DeviceActionApprovalOverlayCoordinator()
        val started = coordinator.begin(TARGET_WINDOW_ID, baselineWindows())
            as DeviceActionApprovalOverlayStart.Started

        val disconnected = coordinator.disconnect()
        assertEquals(DeviceActionApprovalOverlayDecisionKind.SERVICE_DISCONNECTED, disconnected?.kind)
        assertFalse(coordinator.recordUserDecision(started.token, approved = true))

        val second = coordinator.begin(TARGET_WINDOW_ID, baselineWindows())
            as DeviceActionApprovalOverlayStart.Started
        assertTrue(coordinator.recordUserDecision(second.token, approved = true))
        val timedOut = coordinator.detachTimedOut(second.token)
        assertEquals(DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE, timedOut?.kind)
    }

    private fun baselineWindows() = setOf(
        DeviceAccessibilityWindowSnapshot(TARGET_WINDOW_ID, ownedApprovalOverlay = false),
        DeviceAccessibilityWindowSnapshot(SYSTEM_WINDOW_ID, ownedApprovalOverlay = false),
    )

    private fun targetSnapshot(generation: Long) = DeviceActionApprovalTargetSnapshot(
        targetWindowId = TARGET_WINDOW_ID,
        generation = generation,
        windows = baselineWindows(),
    )

    private companion object {
        const val TARGET_WINDOW_ID = 101
        const val SYSTEM_WINDOW_ID = 102
        const val OVERLAY_WINDOW_ID = 103
        const val FOREIGN_WINDOW_ID = 104
    }
}
