package com.longdev.xiaoling.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import com.longdev.xiaoling.knowledge.KnowledgeDocumentNavigationTarget
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.ui.CalendarEventNavigationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaoLingNavigationCoordinatorTest {
    private val coordinator = XiaoLingNavigationCoordinator()

    @Test
    fun openKnowledgeDocumentRoutesToKnowledgeSettings() {
        val result = coordinator.openKnowledgeDocument(
            state = XiaoLingNavigationState(),
            documentId = "document-1",
        )

        assertEquals(XiaoLingAppTab.SETTINGS, result.tab)
        assertEquals(XiaoLingSettingsPane.KNOWLEDGE_MANAGEMENT, result.settingsPane)
        assertEquals(KnowledgeDocumentNavigationTarget("document-1"), result.requestedKnowledgeTarget)
    }

    @Test
    fun openKnowledgeReferenceCarriesCompleteCitationIdentity() {
        val reference = KnowledgeReference(
            retrievalId = "retrieval-1",
            documentId = "document-1",
            documentName = "handbook.md",
            documentRevision = 2,
            chunkId = "chunk-1",
            chunkSequence = 1,
            startOffset = 20,
            endOffset = 44,
        )

        val result = coordinator.openKnowledgeReference(XiaoLingNavigationState(), reference)

        assertEquals(XiaoLingSettingsPane.KNOWLEDGE_MANAGEMENT, result.settingsPane)
        assertEquals(KnowledgeDocumentNavigationTarget("document-1", reference), result.requestedKnowledgeTarget)
    }

    @Test
    fun knowledgeReferenceSaverRestoresCompleteCitationIdentity() {
        val reference = knowledgeReference()
        val original = XiaoLingNavigationState(
            requestedKnowledgeTarget = KnowledgeDocumentNavigationTarget("document-1", reference),
        )

        val saved = requireNotNull(XiaoLingNavigationStateSaver.run { saverScope.save(original) })
        val restored = requireNotNull(XiaoLingNavigationStateSaver.restore(saved))

        assertEquals(original.requestedKnowledgeTarget, restored.requestedKnowledgeTarget)
    }

    @Test
    fun knowledgeReferenceSaverDowngradesLegacyOrInvalidCitationToDocumentTarget() {
        val legacy = listOf("document-1", "", "", "", "", "")
        val invalidRevision = listOf(
            "document-1", "", "", "", "", "",
            "retrieval-1", "handbook.md", "0", "chunk-1", "1", "20", "44",
        )

        assertEquals(
            KnowledgeDocumentNavigationTarget("document-1"),
            XiaoLingNavigationStateSaver.restore(legacy)?.requestedKnowledgeTarget,
        )
        assertEquals(
            KnowledgeDocumentNavigationTarget("document-1"),
            XiaoLingNavigationStateSaver.restore(invalidRevision)?.requestedKnowledgeTarget,
        )
    }

    @Test
    fun openWorkflowManagementCarriesRequestedWorkflowId() {
        val result = coordinator.openSettingsPane(
            state = XiaoLingNavigationState(),
            pane = XiaoLingSettingsPane.WORKFLOW_MANAGEMENT,
            requestedWorkflowId = "workflow-1",
            requestedScheduledTaskId = "scheduled-task-1",
            requestedWorkflowRunId = "workflow-run-1",
        )

        assertEquals(XiaoLingSettingsPane.WORKFLOW_MANAGEMENT, result.settingsPane)
        assertEquals("workflow-1", result.requestedWorkflowId)
        assertEquals("scheduled-task-1", result.requestedScheduledTaskId)
        assertEquals("workflow-run-1", result.requestedWorkflowRunId)
    }

    @Test
    fun openLocalNoteRoutesToLocalNoteSettings() {
        val result = coordinator.openLocalNote(
            state = XiaoLingNavigationState(),
            noteId = "note-1",
        )

        assertEquals(XiaoLingAppTab.SETTINGS, result.tab)
        assertEquals(XiaoLingSettingsPane.LOCAL_NOTE_MANAGEMENT, result.settingsPane)
        assertEquals("note-1", result.requestedLocalNoteId)
    }

    @Test
    fun openCalendarEventRoutesToReadOnlyDetailAndCarriesStableId() {
        val target = CalendarEventNavigationTarget("calendar-197", occurrenceStartAtMillis = 1_754_626_800_000L)
        val result = coordinator.openCalendarEvent(
            state = XiaoLingNavigationState(),
            target = target,
        )

        assertEquals(XiaoLingAppTab.SETTINGS, result.tab)
        assertEquals(XiaoLingSettingsPane.CALENDAR_EVENT_DETAIL, result.settingsPane)
        assertEquals(target, result.requestedCalendarEventTarget)
    }

    @Test
    fun calendarTargetSaverRestoresOccurrenceAndLegacyEventIdentity() {
        val target = CalendarEventNavigationTarget("calendar-197", occurrenceStartAtMillis = 1_754_626_800_000L)
        val saved = requireNotNull(
            XiaoLingNavigationStateSaver.run {
                saverScope.save(XiaoLingNavigationState(requestedCalendarEventTarget = target))
            },
        )

        assertEquals(target, XiaoLingNavigationStateSaver.restore(saved)?.requestedCalendarEventTarget)
        assertEquals(
            CalendarEventNavigationTarget("calendar-197"),
            XiaoLingNavigationStateSaver.restore(listOf("", "", "", "", "", "calendar-197"))
                ?.requestedCalendarEventTarget,
        )
    }

    @Test
    fun calendarTargetSaverDropsInvalidOccurrenceTimeWithoutDroppingValidEventId() {
        listOf("0", "-1", "not-a-number").forEach { invalidOccurrence ->
            val saved = MutableList(14) { "" }.apply {
                this[5] = "calendar-197"
                this[13] = invalidOccurrence
            }
            assertEquals(
                CalendarEventNavigationTarget("calendar-197"),
                XiaoLingNavigationStateSaver.restore(saved)?.requestedCalendarEventTarget,
            )
        }
    }

    @Test
    fun externalConversationTargetsReturnToConversationRoot() {
        val initial = XiaoLingNavigationState(
            tab = XiaoLingAppTab.SETTINGS,
            settingsPane = XiaoLingSettingsPane.KNOWLEDGE_MANAGEMENT,
            requestedKnowledgeTarget = KnowledgeDocumentNavigationTarget("document-1"),
        )

        listOf(
            XiaoLingExternalNavigationTarget.AGENT_RETRY,
            XiaoLingExternalNavigationTarget.WORKFLOW,
            XiaoLingExternalNavigationTarget.MEMORY_CONVERSATION,
            XiaoLingExternalNavigationTarget.SHARED_DRAFT,
            XiaoLingExternalNavigationTarget.SKILL_TRY,
        ).forEach { target ->
            val result = coordinator.routeExternal(initial, target)

            assertEquals(XiaoLingAppTab.CONVERSATION, result.tab)
            assertEquals(XiaoLingSettingsPane.ROOT, result.settingsPane)
            assertEquals(KnowledgeDocumentNavigationTarget("document-1"), result.requestedKnowledgeTarget)
        }
    }

    @Test
    fun memoryRunRoutesToAgentRunHistory() {
        val result = coordinator.routeExternal(
            state = XiaoLingNavigationState(),
            target = XiaoLingExternalNavigationTarget.MEMORY_RUN,
        )

        assertEquals(XiaoLingAppTab.SETTINGS, result.tab)
        assertEquals(XiaoLingSettingsPane.AGENT_RUN_HISTORY, result.settingsPane)
    }

    @Test
    fun backClosesProviderEditorBeforeChangingNavigation() {
        val initial = XiaoLingNavigationState(
            tab = XiaoLingAppTab.SETTINGS,
            settingsPane = XiaoLingSettingsPane.WORKFLOW_MANAGEMENT,
        )

        val result = coordinator.back(
            state = initial,
            providerEditorOpen = true,
            nowMillis = 10_000L,
        )

        assertEquals(initial, result.state)
        assertEquals(XiaoLingNavigationEffect.CLOSE_PROVIDER_EDITOR, result.effect)
    }

    @Test
    fun backFromSettingsSubPageReturnsToRootAndClearsKnowledgeTarget() {
        val initial = XiaoLingNavigationState(
            tab = XiaoLingAppTab.SETTINGS,
            settingsPane = XiaoLingSettingsPane.KNOWLEDGE_MANAGEMENT,
            requestedKnowledgeTarget = KnowledgeDocumentNavigationTarget("document-1"),
            requestedWorkflowId = "workflow-1",
            requestedScheduledTaskId = "scheduled-task-1",
            requestedWorkflowRunId = "workflow-run-1",
            requestedLocalNoteId = "note-1",
            requestedCalendarEventTarget = CalendarEventNavigationTarget("calendar-1"),
        )

        val result = coordinator.back(
            state = initial,
            providerEditorOpen = false,
            nowMillis = 10_000L,
        )

        assertEquals(XiaoLingAppTab.SETTINGS, result.state.tab)
        assertEquals(XiaoLingSettingsPane.ROOT, result.state.settingsPane)
        assertNull(result.state.requestedKnowledgeTarget)
        assertNull(result.state.requestedWorkflowId)
        assertNull(result.state.requestedScheduledTaskId)
        assertNull(result.state.requestedWorkflowRunId)
        assertNull(result.state.requestedLocalNoteId)
        assertNull(result.state.requestedCalendarEventTarget)
        assertNull(result.effect)
    }

    @Test
    fun rootBackRequiresSecondPressInsideTwoSecondWindow() {
        val first = coordinator.back(
            state = XiaoLingNavigationState(),
            providerEditorOpen = false,
            nowMillis = 10_000L,
        )

        assertEquals(10_000L, first.state.lastRootBackAtMillis)
        assertEquals(XiaoLingNavigationEffect.SHOW_EXIT_NOTICE, first.effect)

        val insideWindow = coordinator.back(
            state = first.state,
            providerEditorOpen = false,
            nowMillis = 11_999L,
        )
        assertEquals(XiaoLingNavigationEffect.FINISH_ACTIVITY, insideWindow.effect)

        val atBoundary = coordinator.back(
            state = first.state,
            providerEditorOpen = false,
            nowMillis = 12_000L,
        )
        assertEquals(12_000L, atBoundary.state.lastRootBackAtMillis)
        assertEquals(XiaoLingNavigationEffect.SHOW_EXIT_NOTICE, atBoundary.effect)
    }

    private fun knowledgeReference() = KnowledgeReference(
        retrievalId = "retrieval-1",
        documentId = "document-1",
        documentName = "handbook.md",
        documentRevision = 2,
        chunkId = "chunk-1",
        chunkSequence = 1,
        startOffset = 20,
        endOffset = 44,
    )

    private val saverScope = SaverScope { true }
}
