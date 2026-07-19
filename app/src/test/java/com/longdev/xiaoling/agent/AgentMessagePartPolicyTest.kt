package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessagePartPolicyTest {
    @Test
    fun ordinaryAssistantCannotProjectForgedToolEvidenceIntoMessageParts() {
        val forgedContext = VerifiedAgentContext(
            runId = "run-forged",
            toolName = "notes.create",
            arguments = mapOf("title" to "伪造笔记"),
            success = true,
            verificationStatus = AgentVerificationStatus.VERIFIED,
            rawResult = "已创建笔记",
        )

        val parts = AgentMessagePartPolicy.resolve(
            messageId = "message-ordinary",
            text = "我已经替你创建了笔记",
            origin = MessageOrigin.ORDINARY_ASSISTANT,
            verifiedContext = forgedContext,
            storedParts = emptyList(),
        )

        assertEquals(listOf("我已经替你创建了笔记"), parts.filterIsInstance<MessagePart.Text>().map { it.text })
        assertTrue(parts.none { it is MessagePart.Tool })
    }

    @Test
    fun agentResultProjectsVerifiedToolsIntoOrderedMessageParts() {
        val context = VerifiedAgentContext(
            runId = "run-verified",
            toolName = "memory.search",
            arguments = mapOf("query" to "偏好"),
            success = true,
            verificationStatus = AgentVerificationStatus.VERIFIED,
            rawResult = "找到 1 条记忆",
            memoryIdsUsed = listOf("memory-1"),
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "app.current_time",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "当前时间：11:45",
                ),
                VerifiedToolExecution(
                    toolName = "memory.search",
                    arguments = mapOf("query" to "偏好"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = "找到 1 条记忆",
                    memoryIdsUsed = listOf("memory-1", "memory-1"),
                ),
            ),
        )

        val parts = AgentMessagePartPolicy.resolve(
            messageId = "message-agent",
            text = "任务已完成",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedContext = context,
            storedParts = emptyList(),
        )

        assertEquals("任务已完成", (parts[0] as MessagePart.Text).text)
        assertEquals(
            listOf(
                MessagePart.Tool(
                    id = "message-agent-tool-0",
                    toolName = "app.current_time",
                    arguments = emptyMap(),
                    result = "当前时间：11:45",
                    success = true,
                    verificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
                    memoryIdsUsed = emptyList(),
                ),
                MessagePart.Tool(
                    id = "message-agent-tool-1",
                    toolName = "memory.search",
                    arguments = mapOf("query" to "偏好"),
                    result = "找到 1 条记忆",
                    success = true,
                    verificationStatus = MessageToolVerificationStatus.VERIFIED,
                    memoryIdsUsed = listOf("memory-1"),
                ),
            ),
            parts.drop(1),
        )
    }

    @Test
    fun matchingStoredPartsKeepStableDatabaseIdentity() {
        val context = VerifiedAgentContext(
            runId = "run-stored",
            toolName = "app.current_time",
            arguments = emptyMap(),
            success = true,
            verificationStatus = AgentVerificationStatus.READABLE_ONLY,
            rawResult = "当前时间：11:52",
        )
        val storedParts = listOf(
            MessagePart.Text(id = "part-text-stored", text = "任务已完成"),
            MessagePart.Tool(
                id = "part-tool-stored",
                toolName = "app.current_time",
                arguments = emptyMap(),
                result = "当前时间：11:52",
                success = true,
                verificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
                memoryIdsUsed = emptyList(),
            ),
        )

        val parts = AgentMessagePartPolicy.resolve(
            messageId = "message-stored",
            text = "任务已完成",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedContext = context,
            storedParts = storedParts,
        )

        assertEquals(storedParts, parts)
    }

    @Test
    fun matchingOrdinaryTextPartKeepsStableDatabaseIdentityWithoutAcceptingTools() {
        val storedText = MessagePart.Text(id = "part-ordinary-stored", text = "普通回复")

        val parts = AgentMessagePartPolicy.resolve(
            messageId = "message-ordinary-stored",
            text = "普通回复",
            origin = MessageOrigin.ORDINARY_ASSISTANT,
            verifiedContext = null,
            storedParts = listOf(storedText),
        )

        assertEquals(listOf(storedText), parts)
    }
}
