package com.longdev.xiaoling.ui.navigation

internal enum class XiaoLingAppTab {
    CONVERSATION,
    SETTINGS,
}

internal enum class XiaoLingSettingsPane {
    ROOT,
    PROVIDER_MANAGEMENT,
    NETWORK_REQUEST,
    PROMPT_SETTINGS,
    AGENT_PROFILE_MANAGEMENT,
    DEVICE_AGENT,
    CALENDAR_ACCESS,
    ANSWERABILITY_SHADOW,
    MEMORY_MANAGEMENT,
    LOCAL_NOTE_MANAGEMENT,
    KNOWLEDGE_MANAGEMENT,
    KNOWLEDGE_RELEVANCE_ROLLOUT,
    SKILL_MANAGEMENT,
    WORKFLOW_MANAGEMENT,
    AGENT_RUN_HISTORY,
    PROCESS_EXIT_OBSERVATIONS,
}

internal enum class XiaoLingExternalNavigationTarget {
    AGENT_RETRY,
    WORKFLOW,
    MEMORY_CONVERSATION,
    MEMORY_RUN,
    SHARED_DRAFT,
}

internal enum class XiaoLingNavigationEffect {
    CLOSE_PROVIDER_EDITOR,
    SHOW_EXIT_NOTICE,
    FINISH_ACTIVITY,
}

internal data class XiaoLingNavigationState(
    val tab: XiaoLingAppTab = XiaoLingAppTab.CONVERSATION,
    val settingsPane: XiaoLingSettingsPane = XiaoLingSettingsPane.ROOT,
    val requestedKnowledgeDocumentId: String? = null,
    val requestedWorkflowId: String? = null,
    val requestedLocalNoteId: String? = null,
    val lastRootBackAtMillis: Long = 0L,
) {
    val isSettingsSubPage: Boolean
        get() = tab == XiaoLingAppTab.SETTINGS && settingsPane != XiaoLingSettingsPane.ROOT

    fun hidesBottomBar(providerEditorOpen: Boolean): Boolean = providerEditorOpen || isSettingsSubPage
}

internal data class XiaoLingNavigationResult(
    val state: XiaoLingNavigationState,
    val effect: XiaoLingNavigationEffect? = null,
)

internal class XiaoLingNavigationCoordinator(
    private val rootExitWindowMillis: Long = 2_000L,
) {
    fun selectTab(
        state: XiaoLingNavigationState,
        tab: XiaoLingAppTab,
    ): XiaoLingNavigationState = state.copy(tab = tab)

    fun openSettingsPane(
        state: XiaoLingNavigationState,
        pane: XiaoLingSettingsPane,
        requestedKnowledgeDocumentId: String? = state.requestedKnowledgeDocumentId,
        requestedWorkflowId: String? = null,
        requestedLocalNoteId: String? = null,
    ): XiaoLingNavigationState = state.copy(
        tab = XiaoLingAppTab.SETTINGS,
        settingsPane = pane,
        requestedKnowledgeDocumentId = requestedKnowledgeDocumentId,
        requestedWorkflowId = requestedWorkflowId,
        requestedLocalNoteId = requestedLocalNoteId,
    )

    fun openKnowledgeDocument(
        state: XiaoLingNavigationState,
        documentId: String,
    ): XiaoLingNavigationState = openSettingsPane(
        state = state,
        pane = XiaoLingSettingsPane.KNOWLEDGE_MANAGEMENT,
        requestedKnowledgeDocumentId = documentId,
    )

    fun openLocalNote(
        state: XiaoLingNavigationState,
        noteId: String,
    ): XiaoLingNavigationState = openSettingsPane(
        state = state,
        pane = XiaoLingSettingsPane.LOCAL_NOTE_MANAGEMENT,
        requestedLocalNoteId = noteId,
    )

    fun routeExternal(
        state: XiaoLingNavigationState,
        target: XiaoLingExternalNavigationTarget,
    ): XiaoLingNavigationState = when (target) {
        XiaoLingExternalNavigationTarget.MEMORY_RUN -> state.copy(
            tab = XiaoLingAppTab.SETTINGS,
            settingsPane = XiaoLingSettingsPane.AGENT_RUN_HISTORY,
        )

        XiaoLingExternalNavigationTarget.AGENT_RETRY,
        XiaoLingExternalNavigationTarget.WORKFLOW,
        XiaoLingExternalNavigationTarget.MEMORY_CONVERSATION,
        XiaoLingExternalNavigationTarget.SHARED_DRAFT,
        -> state.copy(
            tab = XiaoLingAppTab.CONVERSATION,
            settingsPane = XiaoLingSettingsPane.ROOT,
        )
    }

    fun back(
        state: XiaoLingNavigationState,
        providerEditorOpen: Boolean,
        nowMillis: Long,
    ): XiaoLingNavigationResult {
        if (providerEditorOpen) {
            return XiaoLingNavigationResult(
                state = state,
                effect = XiaoLingNavigationEffect.CLOSE_PROVIDER_EDITOR,
            )
        }
        if (state.isSettingsSubPage) {
            // long: 从任何设置子页返回时同时清除知识文档目标，避免下次进入知识库时复用旧跳转请求。
            return XiaoLingNavigationResult(
                state = state.copy(
                    settingsPane = XiaoLingSettingsPane.ROOT,
                    requestedKnowledgeDocumentId = null,
                    requestedWorkflowId = null,
                    requestedLocalNoteId = null,
                ),
            )
        }
        if (nowMillis - state.lastRootBackAtMillis < rootExitWindowMillis) {
            return XiaoLingNavigationResult(
                state = state,
                effect = XiaoLingNavigationEffect.FINISH_ACTIVITY,
            )
        }
        // long: 根页面第一次返回只记录时间并提示，第二次必须严格落在两秒窗口内才允许退出应用。
        return XiaoLingNavigationResult(
            state = state.copy(lastRootBackAtMillis = nowMillis),
            effect = XiaoLingNavigationEffect.SHOW_EXIT_NOTICE,
        )
    }
}
