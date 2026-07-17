package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunEventPresentationTest {
    @Test
    fun toolResultJsonIsPresentedAsStructuredFields() {
        val presentation = presentAgentRunEvent(
            type = "tool.result",
            message = """{"tool":"fake.echo","success":true,"content":"done","durationMs":42}""",
        )

        assertEquals("工具执行成功", presentation.summary)
        assertEquals("fake.echo", presentation.fields.single { it.label == "工具" }.value)
        assertEquals("done", presentation.fields.single { it.label == "结果" }.value)
        assertEquals("42ms", presentation.fields.single { it.label == "耗时" }.value)
        assertEquals("是", presentation.fields.single { it.label == "成功" }.value)
        assertNull(presentation.rawFallback)
    }

    @Test
    fun toolCallArgumentsRemainReadableWhenNestedJsonIsUsed() {
        val presentation = presentAgentRunEvent(
            type = "tool.call.proposed",
            message = """{"id":"fake-call-1","name":"fake.echo","risk":"REQUIRES_APPROVAL","arguments":{"goal":"hello","path":"/tmp/a"}}""",
        )

        assertEquals("模型提出工具调用", presentation.summary)
        assertEquals("fake-call-1", presentation.fields.single { it.label == "调用" }.value)
        assertEquals("fake.echo", presentation.fields.single { it.label == "工具" }.value)
        assertEquals("goal=hello · path=/tmp/a", presentation.fields.single { it.label == "参数" }.value)
    }

    @Test
    fun parsedJsonFallsBackToRawMessageWhenExpectedFieldsAreMissing() {
        val raw = """{"tool":"fake.echo","content":"missing success"}"""
        val presentation = presentAgentRunEvent(
            type = "tool.result",
            message = raw,
        )

        assertEquals("工具执行结果", presentation.summary)
        assertEquals(raw, presentation.rawFallback)
        assertEquals("fake.echo", presentation.fields.single { it.label == "工具" }.value)
    }

    @Test
    fun plainTextEventFallsBackToRawMessage() {
        val presentation = presentAgentRunEvent(
            type = "run.status",
            message = "THINKING",
        )

        assertEquals("Run 状态变化", presentation.summary)
        assertEquals("THINKING", presentation.rawFallback)
        assertEquals(emptyList<AgentRunEventField>(), presentation.fields)
    }
}
