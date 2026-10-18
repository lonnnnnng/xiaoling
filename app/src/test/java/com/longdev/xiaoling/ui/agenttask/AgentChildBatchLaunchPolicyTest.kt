package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.ui.XiaoLingUiState
import org.junit.Assert.*
import org.junit.Test

class AgentChildBatchLaunchPolicyTest {
    @Test fun localTextDraftIsPreservedAndBusyOrExternalEntryIsRejected() {
        val state = XiaoLingUiState(prompt = "待发送聊天草稿")
        assertNull(state.childBatchLaunchProblem())
        listOf(state.copy(sendingMessage = true), state.copy(loadingConversationMessages = true),
            state.copy(runningWorkflowId = "workflow"), state.copy(attachingImage = true),
            state.copy(attachingDocument = true), state.copy(sharedDraftImported = true),
            state.copy(sharedDraftImported = true, sharedDraftImportedIsRemote = true)).forEach {
            assertNotNull(it.childBatchLaunchProblem())
        }
        assertEquals("待发送聊天草稿", state.prompt)
    }
}
