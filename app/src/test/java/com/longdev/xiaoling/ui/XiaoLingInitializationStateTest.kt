package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistentSummary
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSampleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoLingInitializationStateTest {
    @Test
    fun startupMergePreservesLoadedAnswerabilityShadowState() {
        val persistentSummary = KnowledgeAnswerabilityShadowPersistentSummary(
            observationCount = 2,
            judgeIdentityCount = 1,
            completedCount = 2,
            acceptCount = 2,
        )
        val sampleSummary = KnowledgeAnswerabilityShadowSampleSummary(
            sampleCount = 1,
            completedCount = 1,
        )

        val merged = mergeAnswerabilityShadowInitializationState(
            initializedState = XiaoLingUiState(),
            runtimeState = XiaoLingUiState(
                answerabilityShadowEnabled = true,
                answerabilityShadowSampleSummary = sampleSummary,
                answerabilityShadowPersistentSummary = persistentSummary,
            ),
        )

        assertTrue(merged.answerabilityShadowEnabled)
        assertEquals(sampleSummary, merged.answerabilityShadowSampleSummary)
        assertEquals(persistentSummary, merged.answerabilityShadowPersistentSummary)
    }

    @Test
    fun startupMergePreservesConsumedNotificationNavigationUntilComposeRoutesIt() {
        val merged = mergeAnswerabilityShadowInitializationState(
            initializedState = XiaoLingUiState(),
            runtimeState = XiaoLingUiState(
                scheduledTaskResultWorkflowId = "workflow-stage233",
                scheduledTaskResultTaskId = "scheduled-task-stage233",
                scheduledTaskResultWorkflowRunId = "workflow-run-stage233",
                scheduledTaskResultNavigationVersion = 1L,
            ),
        )

        assertEquals("workflow-stage233", merged.scheduledTaskResultWorkflowId)
        assertEquals("scheduled-task-stage233", merged.scheduledTaskResultTaskId)
        assertEquals("workflow-run-stage233", merged.scheduledTaskResultWorkflowRunId)
        assertEquals(1L, merged.scheduledTaskResultNavigationVersion)
    }
}
