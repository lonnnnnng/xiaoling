package com.longdev.xiaoling.agent

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPluginSigningTest : AgentPluginSigningContractTests() {
    @Test
    fun independentPythonOpenSslSignatureMatchesProductionCodecAndCanonicalBytes() {
        val directory = Files.createTempDirectory("xiaoling-plugin-interop-")
        try {
            val manifest = AgentPluginSigningFixture.manifest().copy(capabilities = setOf("app.current_time", "\uD800\uDC00", "\uE000"))
            val input = directory.resolve("manifest.json")
            val key = directory.resolve("private.pem")
            val output = directory.resolve("signed.json")
            val json = org.json.JSONObject(AgentPluginJsonCodec.encode(listOf(InstalledAgentPlugin(manifest))))
                .getJSONArray("plugins").getJSONObject(0).getJSONObject("manifest").toString()
            Files.write(input, json.toByteArray(Charsets.UTF_8))
            fun run(vararg args: String) {
                val process = ProcessBuilder(*args).redirectErrorStream(true).start()
                process.inputStream.readBytes()
                assertEquals(0, process.waitFor())
            }
            // long: 私钥仅位于系统临时目录，跨实现验签完成即删除；不写入报告、APK 或项目输出。
            run("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key.toString())
            run("python3", "../tools/plugin_manifest_signer.py", "--manifest", input.toString(),
                "--private-key", key.toString(), "--output", output.toString())
            val signed = AgentPluginJsonCodec.decodeSignedManifest(String(Files.readAllBytes(output), Charsets.UTF_8))
            assertEquals(manifest, signed.manifest)
            assertEquals(64, AgentPluginSigning.verify(signed).publicKeySha256.length)
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
