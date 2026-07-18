package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSkillDocumentCodecTest {
    @Test
    fun validVersionOneDocumentBecomesValidatedLocalSkill() {
        val definition = AgentSkillDocumentCodec.decode(
            raw = """
                {
                  "schemaVersion": 1,
                  "id": "daily-review",
                  "version": 2,
                  "name": "每日回顾",
                  "description": "回顾最近会话并标注当前时间。",
                  "source": "local",
                  "trigger": {
                    "keywords": ["每日回顾", "daily review"],
                    "examples": ["帮我回顾今天的会话"]
                  },
                  "tools": ["app.list_conversations", "app.current_time"],
                  "requirements": {
                    "androidPermissions": [],
                    "risk": "SAFE"
                  },
                  "instructions": "先列出最近会话，再读取当前时间并生成回顾。",
                  "failureRecovery": "任一步失败时停止并报告失败步骤。",
                  "completionCriteria": "两个工具结果均已验证。"
                }
            """.trimIndent(),
            registeredTools = listOf(
                ToolDefinition("app.list_conversations", "列出会话", ToolRisk.SAFE),
                ToolDefinition("app.current_time", "读取时间", ToolRisk.SAFE),
            ),
        )

        assertEquals("daily-review", definition.id)
        assertEquals(2, definition.version)
        assertEquals(AgentSkillSource.LOCAL, definition.source)
        assertEquals(setOf("app.list_conversations", "app.current_time"), definition.toolNames)
        assertEquals(listOf("帮我回顾今天的会话"), definition.triggerExamples)
        assertEquals(ToolRisk.SAFE, definition.declaredRisk)
        assertEquals("两个工具结果均已验证。", definition.completionCriteria)
    }

    @Test
    fun unknownExecutableFieldIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentSkillDocumentCodec.decode(
                raw = validDocument().replace(
                    "\"completionCriteria\": \"两个工具结果均已验证。\"",
                    "\"completionCriteria\": \"两个工具结果均已验证。\", \"script\": \"rm -rf /\"",
                ),
                registeredTools = safeTools(),
            )
        }

        assertEquals("Skill 根对象包含未知字段：script", error.message)
    }

    @Test
    fun documentWithoutTriggerKeywordsIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentSkillDocumentCodec.decode(
                raw = validDocument().replace("\"keywords\": [\"每日回顾\"]", "\"keywords\": []"),
                registeredTools = safeTools(),
            )
        }

        assertEquals("Skill 至少声明一个触发关键词", error.message)
    }

    @Test
    fun documentLimitUsesUtf8Bytes() {
        val oversized = validDocument().replace(
            "\"instructions\": \"先列出最近会话，再读取当前时间并生成回顾。\"",
            "\"instructions\": \"${"回顾".repeat(20_000)}\"",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentSkillDocumentCodec.decode(oversized, safeTools())
        }

        assertEquals("Skill 文件不能超过 64 KiB", error.message)
    }

    private fun safeTools() = listOf(
        ToolDefinition("app.list_conversations", "列出会话", ToolRisk.SAFE),
        ToolDefinition("app.current_time", "读取时间", ToolRisk.SAFE),
    )

    private fun validDocument() = """
        {
          "schemaVersion": 1,
          "id": "daily-review",
          "version": 2,
          "name": "每日回顾",
          "description": "回顾最近会话并标注当前时间。",
          "source": "local",
          "trigger": {"keywords": ["每日回顾"], "examples": ["帮我回顾今天的会话"]},
          "tools": ["app.list_conversations", "app.current_time"],
          "requirements": {"androidPermissions": [], "risk": "SAFE"},
          "instructions": "先列出最近会话，再读取当前时间并生成回顾。",
          "failureRecovery": "任一步失败时停止并报告失败步骤。",
          "completionCriteria": "两个工具结果均已验证。"
        }
    """.trimIndent()
}
