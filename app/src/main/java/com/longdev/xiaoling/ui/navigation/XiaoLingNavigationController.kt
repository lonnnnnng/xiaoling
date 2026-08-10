package com.longdev.xiaoling.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

@Stable
internal class XiaoLingNavigationController(
    private val mutableState: MutableState<XiaoLingNavigationState>,
    private val coordinator: XiaoLingNavigationCoordinator,
) {
    val state: XiaoLingNavigationState
        get() = mutableState.value

    val tab: XiaoLingAppTab
        get() = state.tab

    val settingsPane: XiaoLingSettingsPane
        get() = state.settingsPane

    val requestedKnowledgeDocumentId: String?
        get() = state.requestedKnowledgeDocumentId

    val requestedWorkflowId: String?
        get() = state.requestedWorkflowId

    val requestedScheduledTaskId: String?
        get() = state.requestedScheduledTaskId

    val requestedWorkflowRunId: String?
        get() = state.requestedWorkflowRunId

    val requestedLocalNoteId: String?
        get() = state.requestedLocalNoteId

    val requestedCalendarEventId: String?
        get() = state.requestedCalendarEventId

    fun hidesBottomBar(providerEditorOpen: Boolean): Boolean = state.hidesBottomBar(providerEditorOpen)

    fun selectTab(tab: XiaoLingAppTab) {
        mutableState.value = coordinator.selectTab(state, tab)
    }

    fun openSettingsPane(
        pane: XiaoLingSettingsPane,
        requestedKnowledgeDocumentId: String? = state.requestedKnowledgeDocumentId,
        requestedWorkflowId: String? = null,
        requestedScheduledTaskId: String? = null,
        requestedWorkflowRunId: String? = null,
        requestedLocalNoteId: String? = null,
        requestedCalendarEventId: String? = null,
    ) {
        mutableState.value = coordinator.openSettingsPane(
            state = state,
            pane = pane,
            requestedKnowledgeDocumentId = requestedKnowledgeDocumentId,
            requestedWorkflowId = requestedWorkflowId,
            requestedScheduledTaskId = requestedScheduledTaskId,
            requestedWorkflowRunId = requestedWorkflowRunId,
            requestedLocalNoteId = requestedLocalNoteId,
            requestedCalendarEventId = requestedCalendarEventId,
        )
    }

    fun openKnowledgeDocument(documentId: String) {
        mutableState.value = coordinator.openKnowledgeDocument(state, documentId)
    }

    fun openLocalNote(noteId: String) {
        mutableState.value = coordinator.openLocalNote(state, noteId)
    }

    fun openCalendarEvent(eventId: String) {
        mutableState.value = coordinator.openCalendarEvent(state, eventId)
    }

    fun routeExternal(target: XiaoLingExternalNavigationTarget) {
        mutableState.value = coordinator.routeExternal(state, target)
    }

    fun back(
        providerEditorOpen: Boolean,
        nowMillis: Long,
    ): XiaoLingNavigationEffect? {
        val result = coordinator.back(
            state = state,
            providerEditorOpen = providerEditorOpen,
            nowMillis = nowMillis,
        )
        mutableState.value = result.state
        return result.effect
    }
}

private val XiaoLingNavigationStateSaver = Saver<XiaoLingNavigationState, List<String>>(
    // long: Activity 重建只保留仍可能指向内容的一次性目标；Tab、设置子页和返回时间继续回到初始值。
    save = { state ->
        listOf(
            state.requestedKnowledgeDocumentId.orEmpty(),
            state.requestedWorkflowId.orEmpty(),
            state.requestedScheduledTaskId.orEmpty(),
            state.requestedWorkflowRunId.orEmpty(),
            state.requestedLocalNoteId.orEmpty(),
            state.requestedCalendarEventId.orEmpty(),
        )
    },
    restore = { savedTargets ->
        XiaoLingNavigationState(
            requestedKnowledgeDocumentId = savedTargets.getOrNull(0).orEmpty().ifBlank { null },
            requestedWorkflowId = savedTargets.getOrNull(1).orEmpty().ifBlank { null },
            requestedScheduledTaskId = savedTargets.getOrNull(2).orEmpty().ifBlank { null },
            requestedWorkflowRunId = savedTargets.getOrNull(3).orEmpty().ifBlank { null },
            requestedLocalNoteId = savedTargets.getOrNull(4).orEmpty().ifBlank { null },
            requestedCalendarEventId = savedTargets.getOrNull(5).orEmpty().ifBlank { null },
        )
    },
)

@Composable
internal fun rememberXiaoLingNavigationController(): XiaoLingNavigationController {
    val state = rememberSaveable(stateSaver = XiaoLingNavigationStateSaver) {
        mutableStateOf(XiaoLingNavigationState())
    }
    return remember(state) {
        XiaoLingNavigationController(
            mutableState = state,
            coordinator = XiaoLingNavigationCoordinator(),
        )
    }
}
