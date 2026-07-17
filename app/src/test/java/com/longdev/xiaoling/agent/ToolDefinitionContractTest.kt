package com.longdev.xiaoling.agent

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDefinitionContractTest {
    @Test
    fun typedSchemaRejectsUnknownAndOutOfRangeArguments() {
        val definition = ToolDefinition(
            name = "test.typed",
            description = "验证结构化参数",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数",
                    required = true,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
                ToolInputField(
                    name = "style",
                    description = "展示样式",
                    required = false,
                    type = ToolInputType.STRING,
                    enumValues = setOf("compact", "detailed"),
                ),
            ),
        )

        val result = definition.validateArguments(
            mapOf(
                "limit" to "11",
                "style" to "free-form",
                "unexpected" to "value",
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("limit") && it.contains("不大于 10") })
        assertTrue(result.errors.any { it.contains("style") && it.contains("compact、detailed") })
        assertTrue(result.errors.any { it.contains("unexpected") && it.contains("未在 Schema 中声明") })
    }

    @Test
    fun businessValidatorAddsDeterministicDomainErrors() {
        val definition = ToolDefinition(
            name = "memory.remember",
            description = "保存长期记忆",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField("note", "记忆内容", required = true),
                ToolInputField("tags", "逗号分隔标签", required = false),
            ),
            businessValidators = listOf(
                ToolBusinessValidator { arguments ->
                    val tags = arguments["tags"].orEmpty().split(',').filter { it.isNotBlank() }
                    if (tags.size > 3) listOf("标签数量不能超过 3 个") else emptyList()
                },
            ),
        )

        val result = definition.validateArguments(
            mapOf("note" to "偏好紧凑界面", "tags" to "ui,android,compact,preference"),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.contains("标签数量不能超过 3 个"))
    }

    @Test
    fun approvalAndVerificationPoliciesCannotDowngradeRisk() {
        val safe = ToolDefinition(
            name = "app.read",
            description = "读取本地数据",
            risk = ToolRisk.SAFE,
        )
        val write = ToolDefinition(
            name = "notes.create",
            description = "写入本地笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
        )

        assertEquals(ToolApprovalPolicy.NONE, safe.approvalPolicy)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, write.approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, write.verificationPolicy)
        assertThrows(IllegalArgumentException::class.java) {
            ToolDefinition(
                name = "device.dangerous",
                description = "高风险动作",
                risk = ToolRisk.DANGEROUS,
                approvalPolicy = ToolApprovalPolicy.NONE,
            )
        }
    }

    @Test
    fun modelSchemaContainsTypesConstraintsAndNoExtraProperties() {
        val definition = ToolDefinition(
            name = "app.search",
            description = "搜索本地数据",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "关键词",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
        )

        val schema = JSONObject(definition.toInputJsonSchema())
        val properties = schema.getJSONObject("properties")

        assertEquals("object", schema.getString("type"))
        assertEquals(false, schema.getBoolean("additionalProperties"))
        assertEquals(listOf("query"), schema.getJSONArray("required").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertEquals("string", properties.getJSONObject("query").getString("type"))
        assertEquals(1, properties.getJSONObject("query").getInt("minLength"))
        assertEquals(200, properties.getJSONObject("query").getInt("maxLength"))
        assertEquals("integer", properties.getJSONObject("limit").getString("type"))
        assertEquals(1, properties.getJSONObject("limit").getInt("minimum"))
        assertEquals(10, properties.getJSONObject("limit").getInt("maximum"))
    }

    @Test
    fun registryContractRejectsDuplicateToolNames() {
        val duplicateDefinitions = listOf(
            ToolDefinition("notes.search", "搜索笔记", ToolRisk.SAFE),
            ToolDefinition("notes.search", "重复搜索笔记", ToolRisk.SAFE),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ToolRegistryContract.requireValid(duplicateDefinitions)
        }

        assertTrue(error.message.orEmpty().contains("重复工具名称"))
        assertTrue(error.message.orEmpty().contains("notes.search"))
    }

    @Test
    fun numberAndBooleanTypesRejectNonLogicalValues() {
        val definition = ToolDefinition(
            name = "test.primitives",
            description = "验证基础类型",
            risk = ToolRisk.SAFE,
            inputSchema = listOf(
                ToolInputField("ratio", "比例", required = true, type = ToolInputType.NUMBER),
                ToolInputField("enabled", "是否启用", required = true, type = ToolInputType.BOOLEAN),
            ),
        )

        val result = definition.validateArguments(mapOf("ratio" to "NaN", "enabled" to "yes"))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("ratio") && it.contains("有限数值") })
        assertTrue(result.errors.any { it.contains("enabled") && it.contains("true 或 false") })
    }

    @Test
    fun nonStringFieldCannotDeclareStringEnumValues() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ToolInputField(
                name = "limit",
                description = "条数",
                required = false,
                type = ToolInputType.INTEGER,
                enumValues = setOf("1", "2"),
            )
        }

        assertTrue(error.message.orEmpty().contains("只有字符串参数可以声明枚举"))
    }
}
