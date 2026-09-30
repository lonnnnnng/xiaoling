package com.longdev.xiaoling.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** long: 纯文本分片规则在没有系统 TTS 引擎的开发机上也必须可回归验证。 */
class SystemTextToSpeechTest {
    @Test
    fun splitsAtSentenceBoundaryBeforeHardLimit() {
        val chunks = splitSpeechText("第一句。第二句很长", maxLength = 5)

        assertEquals(listOf("第一句。", "第二句很长"), chunks)
        assertTrue(chunks.all { it.length <= 5 })
    }

    @Test
    fun hardSplitsLongTokenWithoutDroppingText() {
        val text = "abcdefghij"

        assertEquals(listOf("abcde", "fghij"), splitSpeechText(text, maxLength = 5))
    }
}
