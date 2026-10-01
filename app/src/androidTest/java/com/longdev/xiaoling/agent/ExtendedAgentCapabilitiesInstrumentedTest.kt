package com.longdev.xiaoling.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import java.io.File
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommand
import com.longdev.xiaoling.shared.agent.SharedWorkspaceRuntime

/**
 * long: 在真实 Redmi 设备上直接调用生产能力实现，验证网络、私有工作区、Keystore 和 GitHub 导入链路。
 */
@RunWith(AndroidJUnit4::class)
class ExtendedAgentCapabilitiesInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workspaceRoot = File(context.cacheDir, "agent-capability-device-test").apply { mkdirs() }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
        val store = AndroidMcpServerStore(context)
        store.delete("device-mcp-test")
        store.delete("device-mcp-stdio")
        store.delete("device-mcp-legacy")
    }

    @Test
    fun browserReadsPublicPageAndRejectsPrivateTargets() = runBlocking {
        val reader = OkHttpBrowserPageReader()
        val page = reader.read("https://example.com/")

        assertTrue(page.text.contains("Example Domain", ignoreCase = true))
        assertTrue(page.links.isEmpty() || page.links.all { it.startsWith("https://") || it.startsWith("http://") })
        assertTrue(runCatching { BrowserUrlPolicy.validate("http://127.0.0.1/") }.isFailure)
        assertTrue(runCatching { BrowserUrlPolicy.validate("https://user:pass@example.com/") }.isFailure)
        println("DEVICE_BROWSER public_page=true private_target_rejected=true")
    }

    @Test
    fun browserSessionKeepsAndRotatesSnapshotReferences() = runBlocking {
        val reader = OkHttpBrowserPageReader()
        val opened = reader.openSession("https://example.com/")
        val reread = reader.readSession(opened.id)

        assertEquals(opened.id, reread.id)
        assertEquals(opened.snapshotId, reread.snapshotId)
        val openedEvidence = opened.page.toReadableEvidence("device-browser-open", opened.snapshotId)
        assertEquals(ToolReadableEvidenceKind.BROWSER_PAGE, openedEvidence.kind)
        assertEquals("device-browser-open", openedEvidence.toolCallId)
        assertEquals(opened.snapshotId, openedEvidence.snapshotId)
        assertTrue(openedEvidence.contentHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(openedEvidence.sourceRef.startsWith("https://example.com/"))
        assertFalse(openedEvidence.sourceRef.contains("?"))
        assertFalse(openedEvidence.sourceRef.contains("#"))

        val navigated = reader.navigateSession(opened.id, "https://example.com/")
        assertEquals(opened.id, navigated.id)
        assertNotEquals(opened.snapshotId, navigated.snapshotId)
        val navigatedEvidence = navigated.page.toReadableEvidence("device-browser-navigate", navigated.snapshotId)
        assertEquals(navigated.snapshotId, navigatedEvidence.snapshotId)
        assertTrue(navigatedEvidence.contentHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(reader.closeSession(opened.id))
        println("DEVICE_BROWSER_SESSION stable_snapshot=true navigation_rotates_snapshot=true close=true")
    }

    @Test
    fun remoteChannelUsesAndroidKeystoreWithoutExportingHmacKey() {
        val keyStore = AndroidKeystoreRemoteChannelKeyStore()
        val keyId = "device-current"
        try {
            val authenticator = keyStore.authenticator(setOf(keyId))
            val now = System.currentTimeMillis()
            val unsigned = RemoteChannelEnvelope(
                channelId = "loopback",
                messageId = "device-signed-1",
                senderId = "device-user",
                conversationKey = "device-chat",
                text = "设备签名验收",
                receivedAtMillis = now,
                nonce = "d".repeat(RemoteChannelPolicy.MIN_NONCE_CHARS),
                keyId = keyId,
            )
            val signed = unsigned.copy(signature = authenticator.sign(unsigned, keyId))

            assertTrue(keyStore.isNonExportable(keyId))
            assertEquals(null, authenticator.verify(signed, now))
            println("DEVICE_REMOTE_CHANNEL keystore_hmac=true non_exportable=true signature_verified=true")
        } finally {
            keyStore.delete(keyId)
        }
    }

    @Test
    fun workspaceExecutesAndMaintainsSessionInsidePrivateRoot() = runBlocking {
        val sandbox = AndroidWorkspaceSandbox(workspaceRoot)
        val entry = sandbox.write("device/hello.txt", "hello-device")
        assertEquals("device/hello.txt", entry.path)
        assertEquals("hello-device", sandbox.read("device/hello.txt"))
        assertTrue(runCatching { sandbox.read("../outside.txt") }.isFailure)

        val exec = sandbox.execute("printf", listOf("exec-ok"), ".", 5_000)
        assertEquals(0, exec.exitCode)
        assertEquals("exec-ok", exec.stdout)
        assertTrue(runCatching { sandbox.execute("sh", listOf("-c", "id"), ".", 5_000) }.isFailure)
        val sharedRuntime: SharedWorkspaceRuntime = sandbox
        val sharedExec = sharedRuntime.execute(SharedWorkspaceCommand("printf", listOf("shared-device-ok")), ".", 5_000)
        assertEquals("shared-device-ok", sharedExec.stdout)

        val session = sandbox.openTerminal(".")
        try {
            sandbox.writeTerminal(session.id, "printf", listOf("terminal-ok"), 5_000)
            var output = sandbox.readTerminal(session.id)
            repeat(10) {
                if (output.stdout.contains("terminal-ok")) return@repeat
                delay(100)
                output = sandbox.readTerminal(session.id)
            }
            assertTrue((output.stdout + output.stderr).contains("terminal-ok"))
        } finally {
            assertTrue(sandbox.closeTerminal(session.id))
        }
        println("DEVICE_WORKSPACE file_io=true argv_exec=true shell_syntax_rejected=true terminal_session=true traversal_rejected=true")
    }

    @Test
    fun mcpTokenRoundTripUsesKeystoreAndUiSafePersistence() {
        val store = AndroidMcpServerStore(context)
        val secret = "device-secret-123"
        val config = McpServerConfig(
            id = "device-mcp-test",
            name = "Device MCP Test",
            url = "http://127.0.0.1:9/mcp",
            bearerToken = secret,
        )
        store.upsert(config)

        assertEquals(secret, store.get(config.id)?.bearerToken)
        val raw = context.getSharedPreferences("xiaoling_mcp", Context.MODE_PRIVATE)
            .getString("servers", "")
            .orEmpty()
        assertFalse(raw.contains(secret))
        assertFalse(raw.contains("API Key"))
        assertTrue(store.delete(config.id))
        println("DEVICE_MCP keystore_round_trip=true plaintext_absent=true delete=true")
    }

    @Test
    fun mcpTransportPersistenceKeepsStdioDeclarationAndLegacyDefaults() {
        val store = AndroidMcpServerStore(context)
        val stdio = McpServerConfig(
            id = "device-mcp-stdio",
            name = "Device MCP stdio",
            url = "",
            transport = McpTransportKind.STDIO,
            command = "uvx",
            args = listOf("mcp-server", "--readonly"),
        )
        store.upsert(stdio)
        assertEquals(stdio, store.get(stdio.id))

        val remote = McpServerConfig(
            id = "device-mcp-legacy",
            name = "Device MCP legacy",
            url = "http://127.0.0.1:9/mcp",
        )
        store.upsert(remote)
        val preferences = context.getSharedPreferences("xiaoling_mcp", Context.MODE_PRIVATE)
        val raw = JSONArray(preferences.getString("servers", "[]").orEmpty())
        for (index in 0 until raw.length()) {
            val item = raw.getJSONObject(index)
            if (item.getString("id") == remote.id) item.remove("transport")
        }
        preferences.edit().putString("servers", raw.toString()).commit()
        assertEquals(McpTransportKind.STREAMABLE_HTTP, store.get(remote.id)?.transport)

        assertTrue(store.delete(stdio.id))
        assertTrue(store.delete(remote.id))
        println("DEVICE_MCP_TRANSPORT stdio_round_trip=true legacy_default=true")
    }

    @Test
    fun githubSkillDownloadPinsCommitAndHashesDocument() = runBlocking {
        val url = "https://raw.githubusercontent.com/Mangi-11/Eta/main/app/src/main/assets/builtin_skills/skill-installer/SKILL.md"
        val document = GitHubSkillImporter().downloadDocument(url)

        assertTrue(document.content.contains("name: skill-installer"))
        assertTrue(document.sourceRef.matches(Regex("[0-9a-fA-F]{40}")))
        assertTrue(document.sha256.matches(Regex("[0-9a-fA-F]{64}")))
        val decoded = GitHubSkillMarkdownCodec.decode(document.content)
        assertEquals("skill-installer", decoded.id)
        assertTrue(decoded.toolNames.isEmpty())
        println("DEVICE_GITHUB_SKILL downloaded=true commit_pinned=true sha256=true tools_granted=false")
    }

    @Test
    fun githubSkillDirectoryDiscoveryPinsCommit() = runBlocking {
        val url = "https://github.com/Mangi-11/Eta/tree/main/app/src/main/assets/builtin_skills/skill-installer"
        val candidates = GitHubSkillImporter().discover(url)

        assertEquals(listOf("app/src/main/assets/builtin_skills/skill-installer/SKILL.md"), candidates.map(GitHubSkillCandidate::path))
        assertTrue(candidates.single().sourceRef.matches(Regex("[0-9a-fA-F]{40}")))
        println("DEVICE_GITHUB_SKILL_DIRECTORY discovered=true commit_pinned=true count=${candidates.size}")
    }
}
