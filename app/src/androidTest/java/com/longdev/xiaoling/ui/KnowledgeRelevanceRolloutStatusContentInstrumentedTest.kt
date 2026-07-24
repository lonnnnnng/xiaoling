package com.longdev.xiaoling.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceProductionIdentity
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceProductionIdentityBinding
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceProductionIdentityStatus
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceRolloutPreference
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class KnowledgeRelevanceRolloutStatusContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun candidateStatusShowsShadowBoundaryAndRollbackAction() {
        val rolledBack = AtomicBoolean(false)
        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeRelevanceRolloutStatusContent(
                    binding = KnowledgeRelevanceProductionIdentityBinding(
                        status = KnowledgeRelevanceProductionIdentityStatus.CANDIDATE,
                        identity = KnowledgeRelevanceProductionIdentity("provider-a", "embedding-a", "endpoint-a"),
                    ),
                    preference = KnowledgeRelevanceRolloutPreference(),
                    onRollback = { rolledBack.set(true) },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("身份：候选身份").assertExists()
        composeRule.onNodeWithText("有效模式：SHADOW（生产答案路径尚未接入）").assertExists()
        composeRule.onNodeWithText("Provider：provider-a").assertExists()
        composeRule.onNodeWithTag("knowledge-relevance-rollout-rollback").performClick()

        assertTrue(rolledBack.get())
    }

    @Test
    fun unboundStatusDisablesRollback() {
        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeRelevanceRolloutStatusContent(
                    binding = KnowledgeRelevanceProductionIdentityBinding(),
                    preference = KnowledgeRelevanceRolloutPreference(),
                    onRollback = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("身份：未绑定").assertExists()
        composeRule.onNodeWithTag("knowledge-relevance-rollout-rollback").assertIsNotEnabled()
    }
}
