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

    fun hidesBottomBar(providerEditorOpen: Boolean): Boolean = state.hidesBottomBar(providerEditorOpen)

    fun selectTab(tab: XiaoLingAppTab) {
        mutableState.value = coordinator.selectTab(state, tab)
    }

    fun openSettingsPane(
        pane: XiaoLingSettingsPane,
        requestedKnowledgeDocumentId: String? = state.requestedKnowledgeDocumentId,
        requestedWorkflowId: String? = null,
    ) {
        mutableState.value = coordinator.openSettingsPane(
            state = state,
            pane = pane,
            requestedKnowledgeDocumentId = requestedKnowledgeDocumentId,
            requestedWorkflowId = requestedWorkflowId,
        )
    }

    fun openKnowledgeDocument(documentId: String) {
        mutableState.value = coordinator.openKnowledgeDocument(state, documentId)
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
    // long: Activity 重建只保留仍可能指向内容的两个一次性目标；Tab、设置子页和返回时间继续回到初始值。
    save = { state ->
        listOf(
            state.requestedKnowledgeDocumentId.orEmpty(),
            state.requestedWorkflowId.orEmpty(),
        )
    },
    restore = { savedTargets ->
        XiaoLingNavigationState(
            requestedKnowledgeDocumentId = savedTargets.getOrNull(0).orEmpty().ifBlank { null },
            requestedWorkflowId = savedTargets.getOrNull(1).orEmpty().ifBlank { null },
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
