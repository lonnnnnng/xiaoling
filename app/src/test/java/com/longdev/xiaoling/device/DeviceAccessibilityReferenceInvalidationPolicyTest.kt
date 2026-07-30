package com.longdev.xiaoling.device

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessibilityReferenceInvalidationPolicyTest {
    @Test
    fun focusOnlyEventKeepsReferenceWhileStructuralEventsInvalidateIt() {
        assertFalse(
            DeviceAccessibilityReferenceInvalidationPolicy.invalidates(
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
            ),
        )
        assertFalse(
            DeviceAccessibilityReferenceInvalidationPolicy.invalidates(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            ),
        )
        listOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        ).forEach { eventType ->
            assertTrue(DeviceAccessibilityReferenceInvalidationPolicy.invalidates(eventType))
        }
    }

    @Test
    fun generationTracksTheCapturedWindowInsteadOfUnrelatedBackgroundWindows() {
        val tracker = DeviceWindowGenerationTracker()
        val capturedGeneration = tracker.markCapturedWindow(windowId = 101)

        tracker.onAccessibilityEvent(
            windowId = 202,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
        assertEquals(capturedGeneration, tracker.currentGeneration())

        tracker.onAccessibilityEvent(
            windowId = 202,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        )
        assertNotEquals(capturedGeneration, tracker.currentGeneration())

        tracker.onAccessibilityEvent(
            windowId = 101,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        )
        assertEquals(capturedGeneration, tracker.currentGeneration())

        tracker.onAccessibilityEvent(
            windowId = 101,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
        assertNotEquals(capturedGeneration, tracker.currentGeneration())
    }
}
