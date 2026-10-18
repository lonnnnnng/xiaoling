package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentContextSnapshotCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** long: 旧平台 JSON 与 Java 流式摘要作为独立兼容夹具，不能用新 codec 生成“旧证据”后再证明自身正确。 */
internal object ContextSnapshotCompatibilityFixtures {
    private const val LEGACY = """{"schemaVersion":1,"phase":"PLAN","model":"fixture","providerId":null,"apiMode":"CHAT_COMPLETIONS","projection":"EXACT_TEXT","imageCount":0,"documentCount":0,"verifiedToolCallIds":[],"messages":[{"role":"system","content":"系统\n</script>\u2028"},{"role":"user","content":"中文😀"}]}"""
    fun input() = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS",
        listOf(AgentContextMessage("system", "系统\n</script>\u2028"), AgentContextMessage("user", "中文😀")), emptyList())
    fun oldWire(): String = JSONObject(LEGACY).toString()
    fun oldHash(runId: String, stepId: String, sequence: Int, raw: String, version: Int? = null): String {
        val fields = if (version == null) listOf("xiaoling-agent-context-v1", runId, stepId, "$sequence", raw)
            else listOf("xiaoling-agent-context-compressed-v1", runId, stepId, "$sequence", "$version", raw)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output -> fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes)
        } }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

abstract class SharedContextSnapshotContractTests {
    @Test fun androidKeepsLegacyEscapingAndWireWhileSharedReadsSameInput() {
        val expected = ContextSnapshotCompatibilityFixtures.input()
        val raw = ContextSnapshotCompatibilityFixtures.oldWire()
        assertEquals(raw, AgentContextSnapshotCodec.encode(expected))
        assertEquals(expected, AgentContextSnapshotCodec.decode(raw))
        assertEquals(expected, SharedAgentContextSnapshotCodec.decode(raw))
        assertEquals(expected, AgentContextSnapshotCodec.decode(SharedAgentContextSnapshotCodec.encode(expected)))
    }

    @Test fun originalRawAndCompressedHashesMatchIndependentPythonGoldens() {
        val raw = "{\"raw\":\"中😀\"}\n"
        assertEquals("0b0fccba5f8244dfc35e412fbe9bbfc8c72ba040494732def8646af790fbba66", AgentContextSnapshotCodec.recordHash("run-中文", "step😀", 17, raw))
        assertEquals("6e251b026c7cb372e038431f396568c5a057a385e776dec6ba59782bf89acad4", AgentContextSnapshotCodec.recordHash("run-中文", "step😀", 17, raw, 3))
        for (version in listOf(null, 1, 3)) {
            assertEquals(ContextSnapshotCompatibilityFixtures.oldHash("r", "s", 1, raw, version), AgentContextSnapshotCodec.recordHash("r", "s", 1, raw, version))
        }
        assertNotEquals(AgentContextSnapshotCodec.recordHash("r", "s", 1, raw), AgentContextSnapshotCodec.recordHash("r", "s", 1, raw.trim()))
    }

    @Test fun exactAsciiByteLimitDoesNotInheritMillionCharacterScannerDefault() {
        val base = ContextSnapshotCompatibilityFixtures.input().copy(messages = listOf(AgentContextMessage("system", "s"), AgentContextMessage("user", "")))
        val size = AgentContextSnapshotCodec.utf8Size(AgentContextSnapshotCodec.encode(base))
        val expected = base.copy(messages = listOf(base.messages.first(), AgentContextMessage("user", "x".repeat(1_048_576 - size))))
        val raw = AgentContextSnapshotCodec.encode(expected)
        assertEquals(1_048_576, raw.length)
        assertEquals(expected, AgentContextSnapshotCodec.decode(raw))
        assertEquals(expected, SharedAgentContextSnapshotCodec.decode(raw))
        assertTrue(runCatching { AgentContextSnapshotCodec.decode(raw + " ") }.isFailure)
    }

    @Test fun legacyEscapingExpansionKeepsOriginalAndroidDecodeLimit() {
        val base = ContextSnapshotCompatibilityFixtures.input().copy(messages = listOf(AgentContextMessage("system", "s"), AgentContextMessage("user", "")))
        val available = 1_048_576 - SharedAgentContextSnapshotCodec.utf8Size(SharedAgentContextSnapshotCodec.encode(base))
        val body = "</".repeat(available / 2) + "x".repeat(available % 2)
        val input = base.copy(messages = listOf(base.messages.first(), AgentContextMessage("user", body)))
        val raw = SharedAgentContextSnapshotCodec.encode(input)
        assertEquals(1_048_576, SharedAgentContextSnapshotCodec.utf8Size(raw))
        assertEquals(input, SharedAgentContextSnapshotCodec.decode(raw))
        // long: 旧平台 JSON 的额外转义也计入写入容量，不能为接收另一种编码而放宽原 Room 帧边界。
        val error = runCatching { AgentContextSnapshotCodec.decode(raw) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("上下文快照超过单请求限额", error!!.message)
    }

    @Test fun malformedRawInputsAreFixedFormatFailuresForRoomClassification() {
        val raw = ContextSnapshotCompatibilityFixtures.oldWire()
        for (bad in listOf(raw + " {}", raw.replace('"', '\''),
            raw.replace("\"model\":", "\"model\":\"foreign\",\"model\":"),
            JSONObject(raw).put("imageCount", "0").toString(), JSONObject(raw).put("schemaVersion", 2).toString())) {
            val error = runCatching { AgentContextSnapshotCodec.decode(bad) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("上下文快照格式无效", error!!.message)
        }
    }

    @Test fun nullProviderAndProjectionsRemainExplicit() {
        for (input in listOf(ContextSnapshotCompatibilityFixtures.input().copy(projection = AgentContextProjection.DEVICE_INPUT_REDACTED),
            ContextSnapshotCompatibilityFixtures.input().copy(phase = AgentLlmPhase.SUMMARIZE, providerId = "fixture-provider"),
            ContextSnapshotCompatibilityFixtures.input().copy(apiMode = "RESPONSES", imageCount = 1))) {
            val raw = AgentContextSnapshotCodec.encode(input)
            assertEquals(input, SharedAgentContextSnapshotCodec.decode(raw))
            assertEquals(input, AgentContextSnapshotCodec.decode(raw))
        }
        assertTrue(JSONObject(AgentContextSnapshotCodec.encode(ContextSnapshotCompatibilityFixtures.input())).isNull("providerId"))
        assertTrue(runCatching { AgentContextSnapshotCodec.utf8Size("\uDC00") }.isFailure)
    }
}
