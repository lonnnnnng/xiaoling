package com.longdev.xiaoling.ui.navigation

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
        assertEquals("document-1", result.requestedKnowledgeDocumentId)
    }

    @Test
    fun openWorkflowManagementCarriesRequestedWorkflowId() {
        val result = coordinator.openSettingsPane(
            state = XiaoLingNavigationState(),
            pane = XiaoLingSettingsPane.WORKFLOW_MANAGEMENT,
            requestedWorkflowId = "workflow-1",
        )

        assertEquals(XiaoLingSettingsPane.WORKFLOW_MANAGEMENT, result.settingsPane)
        assertEquals("workflow-1", result.requestedWorkflowId)
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
    fun externalConversationTargetsReturnToConversationRoot() {
        val initial = XiaoLingNavigationState(
            tab = XiaoLingAppTab.SETTINGS,
            settingsPane = XiaoLingSettingsPane.KNOWLEDGE_MANAGEMENT,
            requestedKnowledgeDocumentId = "document-1",
        )

        listOf(
            XiaoLingExternalNavigationTarget.AGENT_RETRY,
            XiaoLingExternalNavigationTarget.WORKFLOW,
            XiaoLingExternalNavigationTarget.MEMORY_CONVERSATION,
            XiaoLingExternalNavigationTarget.SHARED_DRAFT,
        ).forEach { target ->
            val result = coordinator.routeExternal(initial, target)

            assertEquals(XiaoLingAppTab.CONVERSATION, result.tab)
            assertEquals(XiaoLingSettingsPane.ROOT, result.settingsPane)
            assertEquals("document-1", result.requestedKnowledgeDocumentId)
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
            requestedKnowledgeDocumentId = "document-1",
            requestedWorkflowId = "workflow-1",
            requestedLocalNoteId = "note-1",
        )

        val result = coordinator.back(
            state = initial,
            providerEditorOpen = false,
            nowMillis = 10_000L,
        )

        assertEquals(XiaoLingAppTab.SETTINGS, result.state.tab)
        assertEquals(XiaoLingSettingsPane.ROOT, result.state.settingsPane)
        assertNull(result.state.requestedKnowledgeDocumentId)
        assertNull(result.state.requestedWorkflowId)
        assertNull(result.state.requestedLocalNoteId)
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
}
