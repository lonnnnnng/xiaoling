package com.longdev.xiaoling.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceInputDraftPolicyTest {
    @Test
    fun recognizedTextBecomesEditableDraftWithoutAgentPrefix() {
        val draft = VoiceInputDraftPolicy.merge(
            currentDraft = "",
            candidates = listOf("明天下午提醒我整理周报"),
        )

        assertEquals("明天下午提醒我整理周报", draft)
        assertFalse(draft.orEmpty().startsWith("/agent"))
    }

    @Test
    fun recognizedTextAppendsWithoutOverwritingExistingDraft() {
        val draft = VoiceInputDraftPolicy.merge(
            currentDraft = "保留这段手工输入",
            candidates = listOf("再补充语音内容"),
        )

        assertEquals("保留这段手工输入\n再补充语音内容", draft)
    }

    @Test
    fun recognizedTextReusesExistingWhitespaceSeparator() {
        val draft = VoiceInputDraftPolicy.merge(
            currentDraft = "第一段已经换行\n",
            candidates = listOf("第二段语音内容"),
        )

        assertEquals("第一段已经换行\n第二段语音内容", draft)
    }

    @Test
    fun blankRecognitionCandidatesLeaveDraftUntouched() {
        assertNull(
            VoiceInputDraftPolicy.merge(
                currentDraft = "不能被覆盖",
                candidates = listOf("  ", "\n"),
            ),
        )
        assertNull(VoiceInputDraftPolicy.merge(currentDraft = "不能被覆盖", candidates = null))
    }
}
