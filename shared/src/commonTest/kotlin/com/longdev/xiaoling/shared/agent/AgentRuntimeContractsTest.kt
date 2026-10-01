package com.longdev.xiaoling.shared.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRuntimeContractsTest {
    @Test
    fun contractKeepsPlatformIndependentToolAndWorkspaceData() {
        val call = SharedToolCall("call-1", "workspace.list", mapOf("path" to "."))
        val command = SharedWorkspaceCommand("printf", listOf("hello"))

        assertEquals(1, AgentRuntimeContract.VERSION)
        assertEquals("workspace.list", call.name)
        assertTrue(command.args.single() == "hello")
    }
}
