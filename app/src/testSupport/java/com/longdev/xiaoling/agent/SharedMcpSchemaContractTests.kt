package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.mcp.SharedMcpJsonSchemaValidator
import com.longdev.xiaoling.shared.mcp.SharedMcpSchemaCodec
import com.longdev.xiaoling.shared.mcp.SharedMcpToolDescriptor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: JVM/Redmi 共用平台兼容和真实 socket 门禁；只用内存配置，不读取正式 MCP/Provider 或用户正文。 */
abstract class SharedMcpSchemaContractTests {
    private fun both(child: String, value: String, accepted: Boolean) {
        val schema = """{"type":"object","required":["value"],"properties":{"value":$child}}"""
        val args = """{"value":$value}"""
        assertEquals(accepted, runCatching { SharedMcpJsonSchemaValidator.validate(schema, args) }.isSuccess)
        assertEquals(accepted, runCatching { McpJsonSchemaValidator.validate(schema, JSONObject(args)) }.isSuccess)
    }

    @Test fun validSchemaSubsetAndRejectedArgumentsAgreeWithPortableEntry() {
        both("""{"type":"integer","minimum":1,"maximum":3}""", "2.0", true)
        both("""{"type":"integer"}""", "2.5", false)
        both("""{"type":"boolean"}""", "\"true\"", false)
        both("""{"type":"null"}""", "null", true)
        both("""{"type":"array","items":{"type":"string"}}""", "[1]", false)
        both("""{"type":"string","maxLength":2}""", "\"😀\"", true)
        both("""{"type":"string","pattern":"AB"}""", "\"xABy\"", true)
    }

    @Test fun androidKeepsOriginalJSONObjectLimitAndBooleanCoercion() {
        both("""{"type":"string","minLength":"2.9"}""", "\"ok\"", true)
        both("""{"type":"string","minLength":"2.9"}""", "\"x\"", false)
        both("""{"type":"object","properties":{},"additionalProperties":"false"}""", """{"extra":1}""", false)
        both("""{"type":"integer","minimum":"2"}""", "1", false)
    }

    @Test fun legacyCompositionAndOptionalKeywordBoundariesArePreserved() {
        both("""{"anyOf":[{}],"oneOf":[],"type":"string"}""", "1", true)
        both("""{"oneOf":[{"type":"number"},{"type":"integer"}]}""", "1", false)
        both("""{"type":"object","additionalProperties":false}""", """{"legacy":1}""", true)
        both("""{"type":"array","items":[{"type":"string"}]}""", "[1]", true)
    }

    @Test fun androidCompositeEnumUsesIndependentJSONObjectSerializationReference() {
        val left = JSONObject("""{"a":1.0,"b":2}""")
        for (right in listOf(JSONObject("""{"a":1,"b":2}"""), JSONObject("""{"b":2,"a":1}"""), JSONObject("""{"a":3,"b":2}"""))) {
            val schema = JSONObject().put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("enum", JSONArray().put(left))))
            val result = runCatching { McpJsonSchemaValidator.validate(schema.toString(), JSONObject().put("value", right)) }
            // long: 参考值来自原平台 serializer，不用新 shared wire 自证历史对象 enum 兼容。
            assertEquals(left.toString() == right.toString(), result.isSuccess)
        }
        assertTrue(runCatching { SharedMcpJsonSchemaValidator.validate("""{"type":"object","properties":{"value":{"enum":[{"n":1.0}]}}}""", """{"value":{"n":1}}""") }.isFailure)
        val array = JSONArray("""[{"n":1e0},[1.0,null]]""")
        for (right in listOf(JSONArray("""[{"n":1},[1,null]]"""), JSONArray("""[{"n":2},[1,null]]"""))) {
            val schema = JSONObject().put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("enum", JSONArray().put(array))))
            assertEquals(array.toString() == right.toString(), runCatching {
                McpJsonSchemaValidator.validate(schema.toString(), JSONObject().put("value", right))
            }.isSuccess)
        }
    }

    @Test fun platformGetStringCompatibilityIsDelegatedAndRawCodecStaysStrict() {
        val required = JSONArray().put(7)
        val schema = JSONObject().put("type", "object").put("required", required)
        val reference = runCatching { required.getString(0) }.isSuccess
        assertEquals(reference, runCatching { McpJsonSchemaValidator.validate(schema.toString(), JSONObject().put("7", true)) }.isSuccess)
        assertTrue(runCatching { SharedMcpJsonSchemaValidator.validate(schema.toString(), """{"7":true}""") }.isFailure)
        McpJsonSchemaValidator.validate("{type:'object'}", JSONObject())
        assertTrue(runCatching { SharedMcpSchemaCodec.decodeSchema("{type:'object'}") }.isFailure)
    }

    @Test fun invalidInputSchemaStillFailsBeforeArgumentsCanBeAccepted() {
        for (schema in listOf("[]", "true", "42", "broken")) {
            val error = runCatching { McpJsonSchemaValidator.validate(schema, JSONObject()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("MCP 工具 inputSchema 不是有效 JSON object", error!!.message)
        }
    }

    @Test fun sharedDescriptorAndInvalidArgumentsStayBehindSocketRegistryGate() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("schema-run")
                val catalog = client.listTools(config)
                val descriptor: SharedMcpToolDescriptor = catalog.single()
                assertEquals("fixture.echo", descriptor.name)
                for (args in listOf(JSONObject(), JSONObject().put("text", 7))) {
                    assertTrue(runCatching { client.callTool(config, descriptor.name, args, catalog) }.isFailure)
                }
                assertEquals(0, fixture.methodCount("tools/call"))
                assertTrue(client.callTool(config, descriptor.name, JSONObject().put("text", "合法"), catalog).contains("echo:合法"))
                assertEquals(1, fixture.methodCount("tools/call"))
            } finally { registry.close() }
        }
    }

    @Test fun originalSchemaTextHintsAndMetadataDriftAreRejectedBeforeDispatch() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("schema-run")
                val current = client.listTools(config).single()
                for (old in listOf(current.copy(inputSchemaJson = " " + current.inputSchemaJson),
                    current.copy(description = "旧说明"), current.copy(readOnlyHint = !current.readOnlyHint))) {
                    assertTrue(runCatching { client.callTool(config, current.name, JSONObject().put("text", "合法"), listOf(old)) }.isFailure)
                }
                assertEquals(0, fixture.methodCount("tools/call"))
            } finally { registry.close() }
        }
    }
}
