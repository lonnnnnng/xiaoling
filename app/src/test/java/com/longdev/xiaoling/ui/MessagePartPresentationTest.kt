package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePartPresentationTest {
    @Test
    fun verifiedToolPartUsesStableReadableLabels() {
        val presentation = MessagePart.Tool(
            id = "part-tool",
            toolName = "memory.search",
            arguments = linkedMapOf("query" to "偏好", "limit" to "3"),
            result = "找到 1 条记忆",
            success = true,
            verificationStatus = MessageToolVerificationStatus.VERIFIED,
            memoryIdsUsed = listOf("memory-1"),
        ).toPresentation()

        assertEquals("memory.search", presentation.toolName)
        assertEquals("已验证", presentation.statusLabel)
        assertEquals("limit=3 · query=偏好", presentation.argumentsLabel)
        assertEquals("找到 1 条记忆", presentation.result)
        assertEquals("引用记忆 1 条", presentation.memoryLabel)
    }
}
