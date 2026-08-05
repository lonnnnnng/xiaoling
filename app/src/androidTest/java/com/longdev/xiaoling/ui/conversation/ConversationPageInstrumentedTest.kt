package com.longdev.xiaoling.ui.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.share.SharedDraftPayload
import com.longdev.xiaoling.ui.PersonalTaskCompletionUiState
import com.longdev.xiaoling.ui.PersonalTaskFailureUiState
import com.longdev.xiaoling.ui.PersonalTaskFailureAction
import com.longdev.xiaoling.ui.PersonalTaskOperationUiPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routesConversationCommandsAndAttachmentRequestThroughActions() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        prompt = "你好",
                        enabledModels = listOf("model"),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("新建会话").performClick()
        composeRule.onNodeWithContentDescription("删除当前会话").performClick()
        composeRule.onNodeWithContentDescription("添加附件").performClick()
        composeRule.onNodeWithText("图片").performClick()
        composeRule.onNodeWithContentDescription("添加附件").performClick()
        composeRule.onNodeWithText("文档").performClick()
        composeRule.onNodeWithContentDescription("发送").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.newConversationCount)
            assertEquals(1, actions.deleteConversationCount)
            assertEquals(1, actions.imageAttachmentRequestCount)
            assertEquals(1, actions.documentAttachmentRequestCount)
            assertEquals(1, actions.sendCount)
        }
    }

    @Test
    fun routesSharedDraftCommandsThroughActions() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        enabledModels = listOf("model"),
                        pendingSharedDraft = SharedDraftPayload(text = "分享内容", imageUri = null),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithText("打开分享").performClick()
        composeRule.onNodeWithContentDescription("忽略分享").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.openSharedDraftCount)
            assertEquals(1, actions.discardSharedDraftCount)
        }
    }

    @Test
    fun fillsPersonalTaskGoalFromTemplateWithoutSending() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        personalTaskMode = true,
                        enabledModels = listOf("model"),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithTag("personal-task-template-menu").performClick()
        composeRule.onNodeWithTag("personal-task-template-weather").performClick()

        composeRule.runOnIdle {
            assertEquals("打开天气并查看当前天气", actions.lastPrompt)
            assertEquals(0, actions.sendCount)
        }
    }

    @Test
    fun routesStopWhileGenerationIsActive() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        sendingMessage = true,
                        enabledModels = listOf("model"),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("停止生成").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.stopCount)
        }
    }

    @Test
    fun showsDedicatedPersonalTaskProgressAndRoutesStop() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        prompt = "整理今天的任务",
                        sendingMessage = true,
                        personalTaskMode = true,
                        personalTaskOperationPhase = PersonalTaskOperationUiPhase.GENERATING_PLAN,
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithTag("personal-task-progress").assertExists()
        composeRule.onNodeWithText("正在生成任务计划").assertExists()
        composeRule.onNodeWithContentDescription("停止生成任务计划").performClick()

        composeRule.runOnIdle { assertEquals(1, actions.stopCount) }
    }

    @Test
    fun retriesFailedPersonalTaskFromPreservedGoal() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        prompt = "已被其他状态覆盖的输入",
                        personalTaskMode = true,
                        personalTaskFailure = PersonalTaskFailureUiState(
                            goal = "整理今天的任务",
                            title = "响应格式错误",
                            message = "模型没有返回有效计划",
                        ),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithText("模型没有返回有效计划").assertExists()
        composeRule.onNodeWithTag("personal-task-retry").performClick()

        composeRule.runOnIdle {
            assertEquals("整理今天的任务", actions.lastPrompt)
            assertEquals(1, actions.sendCount)
        }
    }

    @Test
    fun opensWorkflowForCommittedPersonalTaskFailureWithoutRegenerating() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        personalTaskMode = true,
                        personalTaskFailure = PersonalTaskFailureUiState(
                            goal = "整理今天的任务",
                            title = "个人任务执行失败",
                            message = "任务记录已保留，可在工作流中查看",
                            action = PersonalTaskFailureAction.VIEW_WORKFLOW,
                        ),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithText("查看任务").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.openWorkflowCount)
            assertEquals(0, actions.sendCount)
        }
    }

    @Test
    fun opensWorkflowForCompletedPersonalTaskWithoutSendingAgain() {
        val actions = FakeConversationActions()
        composeRule.setContent {
            MaterialTheme {
                ConversationPage(
                    state = ConversationProjection.project(
                        personalTaskMode = true,
                        personalTaskCompletion = PersonalTaskCompletionUiState(
                            workflowId = "workflow-1",
                            title = "任务目标已验证完成",
                            message = "已验证步骤 2/2，可查看任务证据",
                        ),
                    ),
                    actions = actions,
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithText("任务目标已验证完成").assertExists()
        composeRule.onNodeWithText("已验证步骤 2/2，可查看任务证据").assertExists()
        composeRule.onNodeWithTag("personal-task-view-completed-workflow").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.openWorkflowCount)
            assertEquals("workflow-1", actions.lastOpenedWorkflowId)
            assertEquals(0, actions.sendCount)
        }
    }

    private class FakeConversationActions : ConversationActions {
        var newConversationCount = 0
        var deleteConversationCount = 0
        var imageAttachmentRequestCount = 0
        var documentAttachmentRequestCount = 0
        var openSharedDraftCount = 0
        var discardSharedDraftCount = 0
        var sendCount = 0
        var stopCount = 0
        var openWorkflowCount = 0
        var lastOpenedWorkflowId: String? = null
        var lastPrompt: String? = null

        override fun selectConversation(conversationId: String) = Unit

        override fun openNewConversation() {
            newConversationCount += 1
        }

        override fun deleteCurrentConversation() {
            deleteConversationCount += 1
        }

        override fun updateThemeMode(value: AppThemeMode) = Unit

        override fun selectProvider(profileId: String) = Unit

        override fun updateModel(value: String) = Unit

        override fun updateResponsesEnabled(value: Boolean) = Unit

        override fun updateStreamingEnabled(value: Boolean) = Unit

        override fun updateReasoningSummaryEnabled(value: Boolean) = Unit

        override fun updateAgentMemoryRecallEnabled(value: Boolean) = Unit

        override fun selectAgentProfile(profileId: String) = Unit

        override fun updatePrompt(value: String) {
            lastPrompt = value
        }

        override fun updatePersonalTaskMode(enabled: Boolean) = Unit

        override fun removePendingImage() = Unit

        override fun removePendingDocument() = Unit

        override fun openPendingSharedDraft() {
            openSharedDraftCount += 1
        }

        override fun discardPendingSharedDraft() {
            discardSharedDraftCount += 1
        }

        override fun sendMessage() {
            sendCount += 1
        }

        override fun stopGenerating() {
            stopCount += 1
        }

        override fun confirmPendingPersonalTaskPlan() = Unit

        override fun cancelPendingPersonalTaskPlan() = Unit

        override fun openWorkflowManagement(workflowId: String?) {
            openWorkflowCount += 1
            lastOpenedWorkflowId = workflowId
        }

        override fun approvePendingAgentTool() = Unit

        override fun rejectPendingAgentTool() = Unit

        override fun refreshKnowledgeReferenceStatuses(references: List<KnowledgeReference>) = Unit

        override fun requestImageAttachment() {
            imageAttachmentRequestCount += 1
        }

        override fun requestDocumentAttachment() {
            documentAttachmentRequestCount += 1
        }

        override fun openKnowledgeDocument(documentId: String) = Unit
    }
}
