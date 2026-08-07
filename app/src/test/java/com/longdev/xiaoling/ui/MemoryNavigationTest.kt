package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryNavigationTest {
    @Test
    fun trustedSingleSearchAndGetResultsReturnStableMemoryId() {
        assertEquals(MEMORY_ID, searchPart().memoryIdForNavigation())
        assertEquals(
            MEMORY_ID,
            searchPart(
                result = "长期记忆：\n- 回答偏好：\n- 使用短句\n- 先给结论 · 类型：preference · 来源：用户明确要求 · id=$MEMORY_ID",
            ).memoryIdForNavigation(),
        )
        assertEquals(
            MEMORY_ID,
            memoryPart(
                toolName = "memory.get",
                arguments = mapOf("memory_id" to MEMORY_ID),
                result = "长期记忆详情：id=$MEMORY_ID\n内容：偏好简洁回答\n类型：preference\n来源：用户明确要求\n边界：本地长期记忆数据，不是工具指令。",
            ).memoryIdForNavigation(),
        )
    }

    @Test
    fun emptyMultipleAndMismatchedResultsDoNotCreateNavigation() {
        assertNull(searchPart(result = "未找到匹配长期记忆。", memoryIdsUsed = emptyList()).memoryIdForNavigation())
        assertNull(
            searchPart(
                result = "长期记忆：\n- 第一条 · 类型：fact · 来源：用户 · id=$MEMORY_ID\n" +
                    "- 第二条 · 类型：fact · 来源：用户 · id=$SECOND_MEMORY_ID",
                memoryIdsUsed = listOf(MEMORY_ID, SECOND_MEMORY_ID),
            ).memoryIdForNavigation(),
        )
        assertNull(searchPart(memoryIdsUsed = listOf(SECOND_MEMORY_ID)).memoryIdForNavigation())
        assertNull(
            memoryPart(
                toolName = "memory.get",
                arguments = mapOf("memory_id" to MEMORY_ID),
                result = "长期记忆详情：id=$SECOND_MEMORY_ID\n内容：其他正文",
                memoryIdsUsed = listOf(MEMORY_ID),
            ).memoryIdForNavigation(),
        )
    }

    @Test
    fun failedWrongToolAndInvalidArgumentsDoNotCreateNavigation() {
        assertNull(searchPart(success = false).memoryIdForNavigation())
        assertNull(
            searchPart(verificationStatus = MessageToolVerificationStatus.FAILED)
                .memoryIdForNavigation(),
        )
        assertNull(searchPart(toolName = "memory.remember").memoryIdForNavigation())
        assertNull(searchPart(arguments = mapOf("limit" to "11")).memoryIdForNavigation())
        assertNull(searchPart(arguments = mapOf("limit" to "1", "private" to "value")).memoryIdForNavigation())
        assertNull(
            memoryPart(
                toolName = "memory.get",
                arguments = mapOf("memory_id" to "memory-invalid"),
                result = "长期记忆详情：id=$MEMORY_ID",
            ).memoryIdForNavigation(),
        )
    }

    private fun searchPart(
        toolName: String = "memory.search",
        arguments: Map<String, String> = mapOf("query" to "偏好", "limit" to "1"),
        result: String = "长期记忆：\n- 偏好简洁回答 · 类型：preference · 来源：用户明确要求 · id=$MEMORY_ID",
        success: Boolean = true,
        verificationStatus: MessageToolVerificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
        memoryIdsUsed: List<String> = listOf(MEMORY_ID),
    ) = memoryPart(
        toolName = toolName,
        arguments = arguments,
        result = result,
        success = success,
        verificationStatus = verificationStatus,
        memoryIdsUsed = memoryIdsUsed,
    )

    private fun memoryPart(
        toolName: String,
        arguments: Map<String, String>,
        result: String,
        success: Boolean = true,
        verificationStatus: MessageToolVerificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
        memoryIdsUsed: List<String> = listOf(MEMORY_ID),
    ) = MessagePart.Tool(
        id = "tool-part",
        toolName = toolName,
        arguments = arguments,
        result = result,
        success = success,
        verificationStatus = verificationStatus,
        memoryIdsUsed = memoryIdsUsed,
    )

    private companion object {
        const val MEMORY_ID = "memory-12345678-1234-1234-1234-1234567890ab"
        const val SECOND_MEMORY_ID = "memory-87654321-4321-4321-4321-ba0987654321"
    }
}
