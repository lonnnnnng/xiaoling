package com.longdev.xiaoling.ui.aci

import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Test

class AciReadOnlyCapabilitiesProjectionTest {
    @Test
    fun projectionUsesSafeRegisteredAndProfileAllowedIntersection() {
        val result = projectAciReadOnlyCapabilities(
            registeredTools = listOf(
                ToolDefinition("app.get_info", "应用信息", ToolRisk.SAFE),
                ToolDefinition("app.current_time", "当前时间", ToolRisk.SAFE),
                ToolDefinition("app.get_battery", "电量", ToolRisk.REQUIRES_APPROVAL),
                ToolDefinition("terminal.execute", "终端", ToolRisk.SAFE),
            ),
            allowedToolNames = setOf("app.get_info", "terminal.execute"),
        )

        assertEquals(listOf("app.get_info"), result.map { it.id })
    }
}
