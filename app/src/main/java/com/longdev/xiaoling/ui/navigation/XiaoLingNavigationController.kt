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

    fun hidesBottomBar(providerEditorOpen: Boolean): Boolean = state.hidesBottomBar(providerEditorOpen)

    fun selectTab(tab: XiaoLingAppTab) {
        mutableState.value = coordinator.selectTab(state, tab)
    }

    fun openSettingsPane(
        pane: XiaoLingSettingsPane,
        requestedKnowledgeDocumentId: String? = state.requestedKnowledgeDocumentId,
    ) {
        mutableState.value = coordinator.openSettingsPane(
            state = state,
            pane = pane,
            requestedKnowledgeDocumentId = requestedKnowledgeDocumentId,
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

private val XiaoLingNavigationStateSaver = Saver<XiaoLingNavigationState, String>(
    // long: 旧宿主只跨 Activity 重建保存知识文档目标；Tab、设置子页和返回时间仍按原行为回到初始值。
    save = { state -> state.requestedKnowledgeDocumentId.orEmpty() },
    restore = { savedDocumentId ->
        XiaoLingNavigationState(
            requestedKnowledgeDocumentId = savedDocumentId.ifBlank { null },
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
