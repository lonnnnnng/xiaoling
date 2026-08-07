package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskRetryEvidencePresentationTest {
    @Test
    fun unknownCommitPresentationExplainsConfirmationBoundary() {
        val presentation = presentAgentTaskRetryEvidence(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN)

        assertEquals("提交状态未知", presentation.label)
        assertEquals("COMMIT_UNKNOWN", presentation.code.name)
        assertTrue(presentation.detail.contains("无法确认"))
        assertTrue(presentation.suggestedAction.contains("确认"))
        mapOf(
            AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT to "未发现高风险副作用",
            AgentTaskRetryEvidenceCode.COMMITTED_UNVERIFIED to "已提交但未验证",
            AgentTaskRetryEvidenceCode.COMMITTED_VERIFIED to "已提交且已验证",
            AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE to "执行证据不完整",
        ).forEach { (code, label) ->
            assertEquals(label, presentAgentTaskRetryEvidence(code).label)
        }
    }

    @Test
    fun notCommittedPresentationAllowsDirectRetryGuidance() {
        val presentation = presentAgentTaskRetryEvidence(AgentTaskRetryEvidenceCode.NOT_COMMITTED)

        assertEquals("明确未提交", presentation.label)
        assertTrue(presentation.suggestedAction.contains("直接"))
    }

    @Test
    fun restartRequiredPresentationOverridesDirectRetryGuidance() {
        val presentation = presentAgentTaskRetryEvidence(
            AgentTaskRetryEvidenceCode.NOT_COMMITTED,
            restartRequired = true,
        )

        assertTrue(presentation.suggestedAction.contains("确认后创建关联新 Run"))
        assertTrue(presentation.suggestedAction.contains("旧 Run 保持不变"))
    }

    @Test
    fun everyEvidenceCodeHasVisibleReasonAndNextAction() {
        AgentTaskRetryEvidenceCode.entries.forEach { code ->
            val presentation = presentAgentTaskRetryEvidence(code)

            assertTrue("detail for $code", presentation.detail.isNotBlank())
            assertTrue("suggested action for $code", presentation.suggestedAction.isNotBlank())
        }
    }
}
