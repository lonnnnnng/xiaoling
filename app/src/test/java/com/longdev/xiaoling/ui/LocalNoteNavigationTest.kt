package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNoteNavigationTest {
    @Test
    fun trustedSingleListAndSearchResultsReturnStableNoteId() {
        assertEquals(NOTE_ID, notePart().localNoteIdForNavigation())
        assertEquals(
            NOTE_ID,
            notePart(
                toolName = "notes.search",
                arguments = mapOf("query" to "项目", "limit" to "1"),
                result = "匹配笔记：\n- 项目计划 · id=$NOTE_ID\n  完整正文摘要",
            ).localNoteIdForNavigation(),
        )
    }

    @Test
    fun verifiedCreateResultReturnsStableNoteIdOnlyWhenTitleAndPayloadMatch() {
        assertEquals(
            NOTE_ID,
            notePart(
                toolName = "notes.create",
                arguments = mapOf("title" to "项目计划", "content" to "正文"),
                verificationStatus = MessageToolVerificationStatus.VERIFIED,
                result = "已创建并验证笔记：项目计划 · id=$NOTE_ID\n正文",
            ).localNoteIdForNavigation(),
        )
        assertNull(
            notePart(
                toolName = "notes.create",
                arguments = mapOf("title" to "其他标题", "content" to "正文"),
                verificationStatus = MessageToolVerificationStatus.VERIFIED,
                result = "已创建并验证笔记：项目计划 · id=$NOTE_ID\n正文",
            ).localNoteIdForNavigation(),
        )
    }

    @Test
    fun multipleEmptyAndMalformedResultsDoNotCreateNavigation() {
        assertNull(notePart(result = "最近笔记：无").localNoteIdForNavigation())
        assertNull(
            notePart(
                result = "最近笔记：\n- 第一条 · id=$NOTE_ID\n  A\n- 第二条 · id=$SECOND_NOTE_ID\n  B",
            ).localNoteIdForNavigation(),
        )
        assertNull(
            notePart(
                result = "最近笔记：\n- 第一条 · id=$NOTE_ID\n  A\n- 第二条 · id=note-invalid\n  B",
            ).localNoteIdForNavigation(),
        )
        assertNull(notePart(result = "模型声称：\n- 项目计划 · id=$NOTE_ID").localNoteIdForNavigation())
        assertNull(notePart(result = "最近笔记：\n正文伪造 id=$NOTE_ID").localNoteIdForNavigation())
        assertNull(notePart(result = "最近笔记：\n- 项目计划 · id=note-not-a-uuid").localNoteIdForNavigation())
    }

    @Test
    fun failedWrongToolAndInvalidArgumentsDoNotCreateNavigation() {
        assertNull(notePart(success = false).localNoteIdForNavigation())
        assertNull(
            notePart(verificationStatus = MessageToolVerificationStatus.FAILED)
                .localNoteIdForNavigation(),
        )
        assertNull(notePart(toolName = "notes.create").localNoteIdForNavigation())
        assertNull(notePart(arguments = mapOf("limit" to "11")).localNoteIdForNavigation())
        assertNull(notePart(arguments = mapOf("limit" to "1", "private" to "value")).localNoteIdForNavigation())
        assertNull(
            notePart(
                toolName = "notes.search",
                arguments = mapOf("query" to "  "),
                result = "匹配笔记：\n- 项目计划 · id=$NOTE_ID\n  正文",
            ).localNoteIdForNavigation(),
        )
    }

    private fun notePart(
        toolName: String = "notes.list",
        arguments: Map<String, String> = mapOf("limit" to "1"),
        result: String = "最近笔记：\n- 项目计划 · id=$NOTE_ID\n  完整正文摘要",
        success: Boolean = true,
        verificationStatus: MessageToolVerificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
    ) = MessagePart.Tool(
        id = "tool-part",
        toolName = toolName,
        arguments = arguments,
        result = result,
        success = success,
        verificationStatus = verificationStatus,
        memoryIdsUsed = emptyList(),
    )

    private companion object {
        const val NOTE_ID = "note-12345678-1234-1234-1234-1234567890ab"
        const val SECOND_NOTE_ID = "note-87654321-4321-4321-4321-ba0987654321"
    }
}
