package com.longdev.xiaoling.genui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiPolicyTest {
    @Test
    fun parsesBoundedCardAndKeepsSurroundingText() {
        val projection = GenUiPolicy.projectAssistantText(
            "先看这个选项。\n\n```xiaoling-ui\n" +
                "{\"type\":\"card\",\"version\":1,\"title\":\"下一步\",\"items\":[" +
                "{\"type\":\"text\",\"id\":\"hint\",\"text\":\"请选择操作\"}," +
                "{\"type\":\"button\",\"id\":\"open\",\"label\":\"打开设置\",\"action\":\"打开设置\"}" +
                "]}\n```\n\n保留这句话。",
        )

        requireNotNull(projection)
        assertEquals("下一步", projection.document.title)
        assertEquals(2, projection.document.items.size)
        assertEquals("先看这个选项。\n\n保留这句话。", projection.textWithoutSpec)
        assertTrue(projection.document.items.last() is GenUiItem.Button)
    }

    @Test
    fun rejectsUnknownComponentAndUnsafeControlCharacters() {
        assertNull(
            GenUiPolicy.projectAssistantText(
                "```xiaoling-ui\n" +
                    "{\"type\":\"card\",\"version\":1,\"items\":[" +
                    "{\"type\":\"web\",\"id\":\"x\",\"url\":\"https://example.com\"}" +
                    "]}\n```",
            ),
        )
        assertNull(
            GenUiPolicy.projectAssistantText(
                "```xiaoling-ui\n" +
                    "{\"type\":\"card\",\"version\":1,\"items\":[" +
                    "{\"type\":\"button\",\"id\":\"x\",\"label\":\"执行\",\"action\":\"a\\u0000b\"}" +
                    "]}\n```",
            ),
        )
    }

    @Test
    fun leavesOrdinaryMarkdownAndIncompleteFenceUntouched() {
        assertNull(GenUiPolicy.projectAssistantText("普通回答\n```json\n{}\n```"))
        assertNull(GenUiPolicy.projectAssistantText("```xiaoling-ui\n{\"type\":\"card\"}"))
    }
}
