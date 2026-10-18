package com.longdev.xiaoling.shared.mcp

import kotlin.test.*

class SharedMcpSchemaCodecTest {
    private val golden = """{"type":"object","required":["query"],"properties":{"query":{"type":"string","minLength":2}},"additionalProperties":false}"""
    private fun validate(child: String, value: String) = SharedMcpJsonSchemaValidator.validate(
        """{"type":"object","required":["value"],"properties":{"value":$child}}""", """{"value":$value}""",
    )
    private fun reject(child: String, value: String) = assertFailsWith<IllegalArgumentException> { validate(child, value) }

    @Test fun goldenRoundTripKeepsSchemaFieldsWithoutInventingProtocolVersion() {
        assertEquals(golden, SharedMcpSchemaCodec.encodeSchema(SharedMcpSchemaCodec.decodeSchema(" \n$golden\n")))
        SharedMcpJsonSchemaValidator.validate(golden, """{"query":"中文😀"}""")
        assertEquals(1, SharedMcpSchemaContract.VERSION)
        assertEquals(8, SharedMcpSchemaContract.MAX_DEPTH)
    }

    @Test fun descriptorEqualityStillBindsOriginalSchemaAndUntrustedHints() {
        val value = SharedMcpToolDescriptor("fixture.echo", "说明", golden)
        assertFalse(value.readOnlyHint)
        assertFalse(value.destructiveHint)
        assertNotEquals(value, value.copy(inputSchemaJson = " $golden"))
        assertNotEquals(value, value.copy(description = "另一说明"))
        assertNotEquals(value, value.copy(readOnlyHint = true))
    }

    @Test fun rawSchemaRejectsDuplicateKeysNonObjectsAndJsonDialects() {
        for (raw in listOf("", "[]", "null", "true", "\"{}\"", "{type:'object'}", "{\"type\":\"object\",}",
            "{\"type\":\"string\",\"t\\u0079pe\":\"object\"}", golden + " {}",
            golden.replace("\"type\":\"string\"", "\"type\":\"object\",\"type\":\"string\""))) {
            val error = assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeSchema(raw) }
            assertEquals("MCP inputSchema 不是有效 JSON object", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun rawArgumentsRejectDuplicateKeysAndInvalidUnicodeWithoutEchoingBody() {
        for (raw in listOf("[]", "{\"private\":1,\"private\":2}", "{\"x\":\"\\ud800\"}", "{\"x\":\"\uDC00\"}")) {
            val error = assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeArguments(raw) }
            assertEquals("MCP arguments 不是有效 JSON object", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun requiredPresencePropertiesAndObjectBoundsKeepExistingSubset() {
        assertFailsWith<IllegalArgumentException> { SharedMcpJsonSchemaValidator.validate(golden, "{}") }
        assertFailsWith<IllegalArgumentException> { SharedMcpJsonSchemaValidator.validate(golden, """{"query":"ok","extra":1}""") }
        validate("""{"type":"object","required":["n"],"minProperties":1,"maxProperties":1}""", """{"n":null}""")
        reject("""{"type":"object","minProperties":1}""", "{}")
        reject("""{"type":"object","maxProperties":1}""", """{"a":1,"b":2}""")
    }

    @Test fun arraysEnforceItemsBoundsAndHundredItemLimit() {
        val schema = """{"type":"array","minItems":1,"maxItems":2,"items":{"type":"integer"}}"""
        validate(schema, "[1,2]")
        for (raw in listOf("[]", "[1,2,3]", "[1,1.5]", "\"x\"")) reject(schema, raw)
        validate("""{"type":"array"}""", List(100) { "0" }.joinToString(",", "[", "]"))
        reject("""{"type":"array"}""", List(101) { "0" }.joinToString(",", "[", "]"))
    }

    @Test fun primitiveTypesRemainDistinctAndUnknownTypeIsRejected() {
        validate("""{"type":"boolean"}""", "true")
        reject("""{"type":"boolean"}""", "\"true\"")
        validate("""{"type":"null"}""", "null")
        reject("""{"type":"null"}""", "false")
        reject("""{"type":"future"}""", "1")
        reject("""{"type":["string","null"]}""", "null")
    }

    @Test fun stringsKeepUtf16LengthAndContainsPatternSemantics() {
        validate("""{"type":"string","minLength":2,"maxLength":2}""", "\"😀\"")
        reject("""{"type":"string","maxLength":1}""", "\"😀\"")
        validate("""{"type":"string","pattern":"AB"}""", "\"xABy\"")
        reject("""{"type":"string","pattern":"^AB$"}""", "\"xABy\"")
        reject("""{"type":"string","pattern":"["}""", "\"x\"")
    }

    @Test fun numbersKeepFiniteIntegerBoundsAndDoubleEnumComparison() {
        val schema = """{"type":"integer","minimum":1,"maximum":3}"""
        validate(schema, "1.0")
        for (raw in listOf("0", "4", "1.5", "\"1\"", "1e400")) reject(schema, raw)
        validate("""{"type":"number","enum":[1]}""", "1.0")
        // long: 原子数字 enum 沿原 Double 比较，超出精确整数区间不能宣称任意精度 JSON Schema。
        validate("""{"type":"number","enum":[9007199254740993]}""", "9007199254740992")
    }

    @Test fun compositionsKeepAllOfAndLegacyAnyOfPriorityAndEarlyReturn() {
        validate("""{"allOf":[{"type":"number"},{"minimum":1}]}""", "2")
        reject("""{"allOf":[{"type":"integer"},{"type":"string"}]}""", "2")
        reject("""{"oneOf":[{"type":"number"},{"type":"integer"}]}""", "2")
        validate("""{"anyOf":[{}],"oneOf":[],"type":"string"}""", "2")
        reject("""{"anyOf":[]}""", "2")
        reject("""{"enum":false}""", "2")
    }

    @Test fun ignoredKeywordsAndMissingPropertiesDoNotBecomeNewSchemaSupport() {
        validate("""{"type":"object","additionalProperties":false}""", """{"legacy":1}""")
        validate("""{"type":"array","items":[{"type":"string"}]}""", "[1]")
        validate("""{"type":"string","format":"email","unknownKeyword":false}""", "\"plain\"")
    }

    @Test fun depthAndPropertyLimitsRemainBounded() {
        fun nested(count: Int): Pair<String,String> {
            var schema = """{"type":"string"}"""
            var value = "\"x\""
            repeat(count) { schema = """{"type":"object","properties":{"n":$schema}}"""; value = """{"n":$value}""" }
            return schema to value
        }
        nested(7).let { validate(it.first, it.second) }
        nested(8).let { reject(it.first, it.second) }
        val properties = List(101) { "\"p$it\":{}" }.joinToString(",", "{", "}")
        reject("""{"type":"object","properties":$properties}""", "{}")
    }

    @Test fun compositeEnumKeepsWireOrderAndLexicalNumericShape() {
        validate("""{"enum":[{"a":1,"b":2}]}""", """{"a":1,"b":2}""")
        reject("""{"enum":[{"a":1,"b":2}]}""", """{"b":2,"a":1}""")
        reject("""{"enum":[{"n":1.0}]}""", """{"n":1}""")
        reject("""{"enum":[[1,2]]}""", "[2,1]")
        reject("""{"enum":[[{"n":1e0}]]}""", "[{\"n\":1}]")
    }

    @Test fun rawCodecByteLimitsCountUtf8AndEncodedEscapesWithoutTruncation() {
        val args = "{\"v\":\"" + "x".repeat(SharedMcpSchemaContract.MAX_ARGUMENT_BYTES - 8) + "\"}"
        assertEquals(SharedMcpSchemaContract.MAX_ARGUMENT_BYTES, args.length)
        assertEquals(args, SharedMcpSchemaCodec.encodeArguments(SharedMcpSchemaCodec.decodeArguments(args)))
        assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeArguments(args + " ") }
        val utf8Args = "{\"v\":\"" + "中".repeat((SharedMcpSchemaContract.MAX_ARGUMENT_BYTES - 8) / 3) + "\"}"
        assertEquals(SharedMcpSchemaContract.MAX_ARGUMENT_BYTES, utf8Args.encodeToByteArray().size)
        assertEquals("中".repeat(10_920), SharedMcpSchemaCodec.decodeArguments(utf8Args).optString("v"))
        assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeArguments(utf8Args + " ") }
        val escapedArgs = "{\"v\":\"" + "\\u0061".repeat((SharedMcpSchemaContract.MAX_ARGUMENT_BYTES - 8) / 6) + "\"}"
        assertEquals(SharedMcpSchemaContract.MAX_ARGUMENT_BYTES, escapedArgs.length)
        assertEquals("a".repeat(5_460), SharedMcpSchemaCodec.decodeArguments(escapedArgs).optString("v"))
        assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeArguments(escapedArgs + " ") }
        val schema = "{\"description\":\"" + "x".repeat(SharedMcpSchemaContract.MAX_SCHEMA_BYTES - 18) + "\"}"
        assertEquals(SharedMcpSchemaContract.MAX_SCHEMA_BYTES, schema.length)
        assertEquals(schema, SharedMcpSchemaCodec.encodeSchema(SharedMcpSchemaCodec.decodeSchema(schema)))
        assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeSchema(schema + " ") }
        assertFailsWith<IllegalArgumentException> { SharedMcpSchemaCodec.decodeArguments("{\"v\":\"" + "中".repeat(12_000) + "\"}") }
    }
}
