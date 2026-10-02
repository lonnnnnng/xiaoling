package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.longdev.xiaoling.shared.agent.SharedWorkspaceCommand
import com.longdev.xiaoling.shared.agent.SharedWorkspaceRuntime

class ExtendedAgentCapabilitiesTest {
    @Test
    fun htmlExtractorRemovesExecutableContentAndResolvesLinks() {
        val extracted = HtmlTextExtractor.extract(
            """
            <html><head><title>示例 &amp; 页面</title><script>alert(1)</script></head>
            <body><h1>Hello</h1><a href="/next">下一页</a><style>.x{}</style></body></html>
            """.trimIndent(),
            "https://example.com/start",
            200,
        )

        assertEquals("示例 & 页面", extracted.title)
        assertEquals("示例 & 页面 Hello 下一页", extracted.text)
        assertEquals(listOf("https://example.com/next"), extracted.links)
        assertTrue("alert" !in extracted.text)
    }

    @Test
    fun browserPolicyRejectsLoopbackAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) { BrowserUrlPolicy.validate("http://localhost/test") }
        assertThrows(IllegalArgumentException::class.java) { BrowserUrlPolicy.validate("https://user:pass@example.com/test") }
    }

    @Test
    fun browserSessionKeepsSnapshotAcrossReadsAndRotatesItOnNavigation() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<title>One</title><p>first page</p>"))
        server.enqueue(MockResponse().setBody("<title>Two</title><p>second page</p>"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    chain.proceed(original.newBuilder().url(server.url("/page")).build())
                }
                .build()
            val reader = OkHttpBrowserPageReader(client)

            val opened = reader.openSession("https://example.com/one")
            val reread = reader.readSession(opened.id)
            assertEquals(opened.id, reread.id)
            assertEquals(opened.snapshotId, reread.snapshotId)
            assertEquals("One", reread.page.title)
            val firstEvidence = reread.page.toReadableEvidence("tool-browser", reread.snapshotId)
            assertEquals(reread.snapshotId, firstEvidence.snapshotId)
            assertFalse(firstEvidence.sourceRef.contains("?"))
            assertFalse(firstEvidence.sourceRef.contains("#"))

            val navigated = reader.navigateSession(opened.id, "https://example.com/two")
            assertEquals(opened.id, navigated.id)
            assertNotEquals(opened.snapshotId, navigated.snapshotId)
            assertEquals("Two", navigated.page.title)
            val secondEvidence = navigated.page.toReadableEvidence("tool-browser", navigated.snapshotId)
            assertNotEquals(firstEvidence.contentHash, secondEvidence.contentHash)

            assertTrue(reader.closeSession(opened.id))
            var readAfterCloseFailed = false
            try {
                reader.readSession(opened.id)
            } catch (_: IllegalArgumentException) {
                readAfterCloseFailed = true
            }
            assertTrue(readAfterCloseFailed)
            assertFalse(reader.closeSession(opened.id))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun browserClickConsumesCurrentSnapshotRefAndRejectsStaleOrUnsafeRefs() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<title>One</title><a href=\"https://example.com/next\">Next</a>"))
        server.enqueue(MockResponse().setBody("<title>Two</title><p>second page</p>"))
        server.enqueue(MockResponse().setBody("<title>Private</title><a href=\"http://127.0.0.1/secret\">Private</a>"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build())
                }
                .build()
            val reader = OkHttpBrowserPageReader(client)

            val opened = reader.openSession("https://example.com/one")
            val ref = opened.page.linkRefs.single()
            assertTrue(ref.ref.startsWith("link-${opened.snapshotId}-"))
            val clicked = reader.clickLink(opened.id, opened.snapshotId, ref.ref)
            assertNotEquals(opened.snapshotId, clicked.snapshotId)
            assertEquals("Two", clicked.page.title)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.clickLink(opened.id, opened.snapshotId, ref.ref) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.clickLink(clicked.id, clicked.snapshotId, "link-${clicked.snapshotId}-forged") }
            }

            val privatePage = reader.navigateSession(clicked.id, "https://example.com/private")
            val privateRef = privatePage.page.linkRefs.single()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.clickLink(privatePage.id, privatePage.snapshotId, privateRef.ref) }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancelledNavigationDoesNotReplaceTheCurrentBrowserSnapshot() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<title>One</title><p>first page</p>"))
        server.enqueue(
            MockResponse()
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setBody("<title>Two</title><p>second page</p>"),
        )
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build())
                }
                .build()
            val reader = OkHttpBrowserPageReader(client)
            val opened = reader.openSession("https://example.com/one")

            val navigation = launch(Dispatchers.Default) {
                reader.navigateSession(opened.id, "https://example.com/two")
            }
            server.takeRequest(2, TimeUnit.SECONDS)
            navigation.cancelAndJoin()

            val current = reader.readSession(opened.id)
            assertEquals(opened.snapshotId, current.snapshotId)
            assertEquals("One", current.page.title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun clearingBrowserSessionsRevokesTheInMemorySession() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<title>One</title><p>first page</p>"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build())
                }
                .build()
            val reader = OkHttpBrowserPageReader(client)
            val opened = reader.openSession("https://example.com/one")
            reader.clearSessions()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.readSession(opened.id) }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun workspaceSandboxKeepsFilesInsideRoot() = runTest {
        val root = Files.createTempDirectory("xiaoling-workspace-test").toFile()
        val sandbox = AndroidWorkspaceSandbox(root)
        val entry = sandbox.write("notes/a.txt", "hello")

        assertEquals("notes/a.txt", entry.path)
        assertEquals("hello", sandbox.read("notes/a.txt"))
        assertTrue(sandbox.list("notes").single().path == "notes/a.txt")
        val traversalError = runCatching { sandbox.read("../outside.txt") }.exceptionOrNull()
        assertTrue(traversalError is IllegalArgumentException)
    }

    @Test
    fun workspaceCommandPolicyRejectsShellSyntaxAndUnknownCommands() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceCommandSpec("sh", listOf("-c", "id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceCommandSpec("printf", listOf("hello\nworld"))
        }
        assertEquals("printf hello", WorkspaceCommandSpec("printf", listOf("hello")).displayCommand())
    }

    @Test
    fun workspaceSandboxImplementsSharedRuntimePort() = runTest {
        val sandbox = AndroidWorkspaceSandbox(Files.createTempDirectory("xiaoling-shared-runtime-test").toFile())
        val runtime: SharedWorkspaceRuntime = sandbox

        val result = runtime.execute(SharedWorkspaceCommand("printf", listOf("shared-ok")), ".", 5_000)

        assertEquals("printf", result.commandId)
        assertEquals(listOf("shared-ok"), result.args)
        assertEquals(0, result.exitCode)
        assertEquals("shared-ok", result.stdout)
    }

    @Test
    fun mcpRpcParserSelectsMatchingSseEvent() {
        val client = StreamableHttpMcpClient()
        val json = client.parseRpcBody(
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":\"wanted\",\"result\":{\"ok\":true}}\n",
            expectedId = "wanted",
        )

        assertEquals("wanted", json.getString("id"))
        assertTrue(json.getJSONObject("result").getBoolean("ok"))
    }

    @Test
    fun mcpSchemaValidatorRejectsMissingAndUnknownArguments() {
        val schema = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("query"))
            .put("additionalProperties", false)
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("minLength", 3)))

        McpJsonSchemaValidator.validate(schema.toString(), JSONObject().put("query", "hello"))
        assertThrows(IllegalArgumentException::class.java) {
            McpJsonSchemaValidator.validate(schema.toString(), JSONObject())
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpJsonSchemaValidator.validate(schema.toString(), JSONObject().put("query", "ok"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpJsonSchemaValidator.validate(schema.toString(), JSONObject().put("query", "hello").put("extra", true))
        }
    }

    @Test
    fun mcpPolicyAcceptsIpv6LoopbackOnlyForPlainHttp() {
        McpServerPolicy.validate(McpServerConfig("local-mcp", "Local", "http://[::1]:8080/mcp"))
        assertThrows(IllegalArgumentException::class.java) {
            McpServerPolicy.validate(McpServerConfig("bad", "Bad", "http://192.168.1.2:8080/mcp"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpServerPolicy.validate(McpServerConfig("userinfo", "Userinfo", "https://user:pass@example.com/mcp"))
        }
    }

    @Test
    fun mcpTransportParserDefaultsLegacyAndRoundTripsKnownKinds() {
        val legacy = McpTransportConfigParser.parse(JSONObject())
        assertEquals(McpTransportKind.STREAMABLE_HTTP, legacy.kind)

        val stdio = McpTransportConfigParser.parse(JSONObject()
            .put("type", "stdio")
            .put("command", "uvx")
            .put("args", JSONArray().put("mcp-server")))
        assertEquals(McpTransportKind.STDIO, stdio.kind)
        assertEquals("uvx", stdio.command)
        assertEquals(listOf("mcp-server"), stdio.args)

        val encoded = JSONObject()
        McpTransportConfigParser.writeTo(encoded, stdio)
        assertEquals("stdio", encoded.getString("transport"))
        assertEquals(stdio, McpTransportConfigParser.parse(encoded))
    }

    @Test
    fun mcpTransportParserRejectsUnknownAndAmbiguousDeclarations() {
        assertThrows(IllegalArgumentException::class.java) {
            McpTransportConfigParser.parse(JSONObject().put("type", "websocket"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpTransportConfigParser.parse(JSONObject()
                .put("type", "sse")
                .put("transport", "streamable_http"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpTransportConfigParser.parse(JSONObject()
                .put("type", "stdio")
                .put("args", JSONArray().put("missing-command")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpTransportConfigParser.parse(JSONObject()
                .put("type", "sse")
                .put("command", "local-server"))
        }
    }

    @Test
    fun mcpPolicyValidatesSseAndStdioButClientFailsClosedBeforeExecution() = runTest {
        val sse = McpServerConfig(
            id = "sse-mcp",
            name = "SSE MCP",
            url = "https://example.com/mcp",
            transport = McpTransportKind.SSE,
        )
        McpServerPolicy.validate(sse)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { StreamableHttpMcpClient().listTools(sse) }
        }

        val stdio = McpServerConfig(
            id = "stdio-mcp",
            name = "stdio MCP",
            url = "",
            transport = McpTransportKind.STDIO,
            command = "uvx",
            args = listOf("mcp-server"),
        )
        McpServerPolicy.validate(stdio)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { StreamableHttpMcpClient().listTools(stdio) }
        }
    }

    @Test
    fun mcpToolAllowlistKeepsLegacyConfigsCompatibleAndSupportsExplicitDisable() {
        val legacy = McpServerConfig("legacy-mcp", "Legacy", "https://example.com/mcp")
        assertTrue(McpToolAccessPolicy.isEnabled(legacy, "demo.read"))

        val explicit = legacy.copy(enabledToolNames = setOf("demo.read", "demo.write"))
        val next = McpToolAccessPolicy.updatedEnabledToolNames(
            server = explicit,
            discoveredToolNames = setOf("demo.read", "demo.write"),
            toolName = "demo.write",
            enabled = false,
        )
        assertEquals(setOf("demo.read"), next)
        assertFalse(McpToolAccessPolicy.isEnabled(explicit.copy(enabledToolNames = next), "demo.write"))
        assertThrows(IllegalArgumentException::class.java) {
            McpServerPolicy.validateToolNames(setOf("bad name"))
        }
    }

    @Test
    fun githubDiscoveryWalksNonRecursiveTreesWhenRecursiveTreeIsTruncated() = runTest {
        val server = MockWebServer()
        val subtree = "abcdef0123456789abcdef0123456789abcdef01"
        server.enqueue(MockResponse().setBody("""{"truncated":true,"tree":[]}"""))
        server.enqueue(MockResponse().setBody("""{"truncated":false,"tree":[
            {"type":"tree","path":"nested","sha":"$subtree"},
            {"type":"blob","path":"SKILL.md"}
        ]}""".trimIndent()))
        server.enqueue(MockResponse().setBody("""{"truncated":false,"tree":[{"type":"blob","path":"SKILL.md"}]}"""))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val rewritten = server.url(original.url.encodedPath).newBuilder().apply {
                        original.url.queryParameterNames.forEach { name ->
                            original.url.queryParameterValues(name).forEach { value ->
                                addQueryParameter(name, value)
                            }
                        }
                    }.build()
                    chain.proceed(original.newBuilder().url(rewritten).build())
                }
                .build()
            val commit = "0123456789abcdef0123456789abcdef01234567"
            val candidates = GitHubSkillImporter(client).discover("https://github.com/acme/repo/tree/$commit")

            assertEquals(listOf("SKILL.md", "nested/SKILL.md"), candidates.map(GitHubSkillCandidate::path))
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.path!!.contains("/git/trees/$commit"))
            assertEquals("/repos/acme/repo/git/trees/$commit", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertEquals("/repos/acme/repo/git/trees/$subtree", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun githubPolicyNormalizesRepositorySubdirectory() {
        assertEquals(
            "https://raw.githubusercontent.com/acme/tools/main/skills/research/SKILL.json",
            GitHubSkillUrlPolicy.normalize("https://github.com/acme/tools/tree/main/skills/research"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/acme/tools/main/skills/research/SKILL.json",
            GitHubSkillUrlPolicy.normalize("https://github.com/acme/tools/blob/main/skills/research/SKILL.json"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GitHubSkillUrlPolicy.normalize("http://github.com/acme/tools")
        }
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/acme/tools/HEAD/SKILL.json",
                "https://raw.githubusercontent.com/acme/tools/HEAD/SKILL.md",
            ),
            GitHubSkillUrlPolicy.candidates("https://github.com/acme/tools"),
        )
        assertEquals(
            "main",
            GitHubSkillUrlPolicy.sourceRef("https://raw.githubusercontent.com/acme/tools/main/SKILL.md"),
        )
        assertEquals(
            GitHubSkillLocation("acme", "tools", "main", "skills/research/SKILL.md"),
            GitHubSkillUrlPolicy.location("https://raw.githubusercontent.com/acme/tools/main/skills/research/SKILL.md"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/acme/tools/0123456789abcdef0123456789abcdef01234567/skills/research/SKILL.md",
            GitHubSkillUrlPolicy.withRef(
                "https://raw.githubusercontent.com/acme/tools/main/skills/research/SKILL.md",
                "0123456789abcdef0123456789abcdef01234567",
            ),
        )
    }

    @Test
    fun githubRepositoryTargetSupportsRootAndSubdirectoryDiscovery() {
        assertEquals(
            GitHubSkillRepositoryTarget("acme", "tools", "HEAD", ""),
            GitHubSkillUrlPolicy.repositoryTarget("https://github.com/acme/tools"),
        )
        assertEquals(
            GitHubSkillRepositoryTarget("acme", "tools", "main", "skills/research"),
            GitHubSkillUrlPolicy.repositoryTarget("https://github.com/acme/tools/tree/main/skills/research"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/acme/tools/0123456789abcdef0123456789abcdef01234567/skills/research/SKILL.md",
            GitHubSkillUrlPolicy.rawUrl("acme", "tools", "0123456789abcdef0123456789abcdef01234567", "skills/research/SKILL.md"),
        )
    }

    @Test
    fun githubMarkdownSkillImportsInstructionsWithoutGrantingTools() {
        val skill = GitHubSkillMarkdownCodec.decode(
            """
            ---
            name: repo-review
            description: Review the current repository.
            ---
            Read the README and summarize the project.
            """.trimIndent(),
        )

        assertEquals("repo-review", skill.id)
        assertTrue(skill.toolNames.isEmpty())
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("Read the README"))
    }

    @Test
    fun builtInSkillsCoverTheNewAgentCapabilities() {
        val declaredTools = BuiltInAgentSkillRegistry.all().flatMap { it.toolNames }.toSet()

        assertTrue("browser.fetch" in declaredTools)
        assertTrue("browser.open" in declaredTools)
        assertTrue("browser.read" in declaredTools)
        assertTrue("browser.navigate" in declaredTools)
        assertTrue("browser.click" in declaredTools)
        assertTrue("browser.close" in declaredTools)
        val browserSkill = BuiltInAgentSkillRegistry.all().single { it.id == "browser-research" }
        assertEquals(ToolRisk.REQUIRES_APPROVAL, browserSkill.declaredRisk)
        assertTrue("browser.click" in browserSkill.toolNames)
        assertTrue("workspace.list" in declaredTools)
        assertTrue("workspace.read_file" in declaredTools)
        assertTrue("workspace.write_file" in declaredTools)
        assertTrue("terminal.execute" in declaredTools)
        assertTrue("terminal.open" in declaredTools)
        assertTrue("terminal.write" in declaredTools)
        assertTrue("terminal.read" in declaredTools)
        assertTrue("terminal.close" in declaredTools)
        assertTrue("mcp.list_tools" in declaredTools)
        assertTrue("mcp.call" in declaredTools)
        assertTrue("mcp.list_resources" in declaredTools)
        assertTrue("mcp.read_resource" in declaredTools)
        assertTrue("mcp.list_prompts" in declaredTools)
        assertTrue("mcp.get_prompt" in declaredTools)
    }

    @Test
    fun mcpClientPerformsHandshakeAndCarriesSessionHeaders() = runTest {
        val server = MockWebServer()
        val bodies = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                bodies += body
                val payload = JSONObject(body)
                return when (payload.optString("method")) {
                    "initialize" -> MockResponse()
                        .setHeader("Mcp-Session-Id", "session-1")
                        .setBody(JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", payload.getString("id"))
                            .put("result", JSONObject().put("protocolVersion", "2025-03-26"))
                            .toString())
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> MockResponse().setBody(JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", payload.getString("id"))
                        .put("result", JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "demo").put("description", "Demo"))))
                        .toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val tools = StreamableHttpMcpClient().listTools(
                McpServerConfig("local-mcp", "Local", server.url("/mcp").toString()),
            )

            assertEquals("demo", tools.single().name)
            val initialize = server.takeRequest()
            assertTrue(bodies[0].contains("\"method\":\"initialize\""))
            val initialized = server.takeRequest()
            assertTrue(bodies[1].contains("\"method\":\"notifications/initialized\""))
            val list = server.takeRequest()
            assertEquals("session-1", list.getHeader("Mcp-Session-Id"))
            assertEquals("2025-03-26", list.getHeader("Mcp-Protocol-Version"))
            assertTrue(bodies[2].contains("\"method\":\"tools/list\""))
        } finally {
            server.shutdown()
        }
    }
}
