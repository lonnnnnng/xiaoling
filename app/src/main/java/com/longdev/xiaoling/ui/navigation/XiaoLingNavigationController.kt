package com.longdev.xiaoling.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.longdev.xiaoling.knowledge.KnowledgeDocumentNavigationTarget
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.ui.CalendarEventNavigationTarget

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

    val requestedKnowledgeTarget: KnowledgeDocumentNavigationTarget?
        get() = state.requestedKnowledgeTarget

    val requestedWorkflowId: String?
        get() = state.requestedWorkflowId

    val requestedScheduledTaskId: String?
        get() = state.requestedScheduledTaskId

    val requestedWorkflowRunId: String?
        get() = state.requestedWorkflowRunId

    val requestedLocalNoteId: String?
        get() = state.requestedLocalNoteId

    val requestedCalendarEventTarget: CalendarEventNavigationTarget?
        get() = state.requestedCalendarEventTarget

    fun hidesBottomBar(providerEditorOpen: Boolean): Boolean = state.hidesBottomBar(providerEditorOpen)

    fun selectTab(tab: XiaoLingAppTab) {
        mutableState.value = coordinator.selectTab(state, tab)
    }

    fun openSettingsPane(
        pane: XiaoLingSettingsPane,
        requestedKnowledgeTarget: KnowledgeDocumentNavigationTarget? = state.requestedKnowledgeTarget,
        requestedWorkflowId: String? = null,
        requestedScheduledTaskId: String? = null,
        requestedWorkflowRunId: String? = null,
        requestedLocalNoteId: String? = null,
        requestedCalendarEventTarget: CalendarEventNavigationTarget? = null,
    ) {
        mutableState.value = coordinator.openSettingsPane(
            state = state,
            pane = pane,
            requestedKnowledgeTarget = requestedKnowledgeTarget,
            requestedWorkflowId = requestedWorkflowId,
            requestedScheduledTaskId = requestedScheduledTaskId,
            requestedWorkflowRunId = requestedWorkflowRunId,
            requestedLocalNoteId = requestedLocalNoteId,
            requestedCalendarEventTarget = requestedCalendarEventTarget,
        )
    }

    fun openKnowledgeDocument(documentId: String) {
        mutableState.value = coordinator.openKnowledgeDocument(state, documentId)
    }

    fun openKnowledgeReference(reference: KnowledgeReference) {
        mutableState.value = coordinator.openKnowledgeReference(state, reference)
    }

    fun openLocalNote(noteId: String) {
        mutableState.value = coordinator.openLocalNote(state, noteId)
    }

    fun openCalendarEvent(target: CalendarEventNavigationTarget) {
        mutableState.value = coordinator.openCalendarEvent(state, target)
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

internal val XiaoLingNavigationStateSaver = Saver<XiaoLingNavigationState, List<String>>(
    // long: Activity 重建只保留仍可能指向内容的一次性目标；Tab、设置子页和返回时间继续回到初始值。
    save = { state ->
        listOf(
            state.requestedKnowledgeTarget?.documentId.orEmpty(),
            state.requestedWorkflowId.orEmpty(),
            state.requestedScheduledTaskId.orEmpty(),
            state.requestedWorkflowRunId.orEmpty(),
            state.requestedLocalNoteId.orEmpty(),
            state.requestedCalendarEventTarget?.eventId.orEmpty(),
            state.requestedKnowledgeTarget?.reference?.retrievalId.orEmpty(),
            state.requestedKnowledgeTarget?.reference?.documentName.orEmpty(),
            state.requestedKnowledgeTarget?.reference?.documentRevision?.toString().orEmpty(),
            state.requestedKnowledgeTarget?.reference?.chunkId.orEmpty(),
            state.requestedKnowledgeTarget?.reference?.chunkSequence?.toString().orEmpty(),
            state.requestedKnowledgeTarget?.reference?.startOffset?.toString().orEmpty(),
            state.requestedKnowledgeTarget?.reference?.endOffset?.toString().orEmpty(),
            state.requestedCalendarEventTarget?.occurrenceStartAtMillis?.toString().orEmpty(),
        )
    },
    restore = { savedTargets ->
        val documentId = savedTargets.getOrNull(0).orEmpty().ifBlank { null }
        val restoredReference = documentId?.let { id ->
            val retrievalId = savedTargets.getOrNull(6).orEmpty()
            val documentName = savedTargets.getOrNull(7).orEmpty()
            val revision = savedTargets.getOrNull(8)?.toIntOrNull()
            val chunkId = savedTargets.getOrNull(9).orEmpty()
            val sequence = savedTargets.getOrNull(10)?.toIntOrNull()
            val startOffset = savedTargets.getOrNull(11)?.toIntOrNull()
            val endOffset = savedTargets.getOrNull(12)?.toIntOrNull()
            if (
                retrievalId.isNotBlank() && documentName.isNotBlank() && revision != null &&
                revision > 0 && chunkId.isNotBlank() && sequence != null && sequence >= 0 &&
                startOffset != null && startOffset >= 0 && endOffset != null && endOffset > startOffset
            ) {
                KnowledgeReference(retrievalId, id, documentName, revision, chunkId, sequence, startOffset, endOffset)
            } else {
                null
            }
        }
        XiaoLingNavigationState(
            // long: 引用原文定位跨 Activity 重建仍必须携带完整 revision/chunk/offset；任一字段缺失时只降级到普通文档落点，不拼凑引用身份。
            requestedKnowledgeTarget = documentId?.let { KnowledgeDocumentNavigationTarget(it, restoredReference) },
            requestedWorkflowId = savedTargets.getOrNull(1).orEmpty().ifBlank { null },
            requestedScheduledTaskId = savedTargets.getOrNull(2).orEmpty().ifBlank { null },
            requestedWorkflowRunId = savedTargets.getOrNull(3).orEmpty().ifBlank { null },
            requestedLocalNoteId = savedTargets.getOrNull(4).orEmpty().ifBlank { null },
            requestedCalendarEventTarget = savedTargets.getOrNull(5).orEmpty().ifBlank { null }?.let { eventId ->
                val rawOccurrenceStart = savedTargets.getOrNull(13).orEmpty()
                val occurrenceStart = rawOccurrenceStart.toLongOrNull()?.takeIf { it > 0L }
                // long: 恢复数据可能来自旧版本或损坏 Bundle；非法 occurrence 只丢弃实例时间，保留稳定事件 ID 继续读取 master。
                runCatching { CalendarEventNavigationTarget(eventId, occurrenceStart) }.getOrNull()
            },
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
