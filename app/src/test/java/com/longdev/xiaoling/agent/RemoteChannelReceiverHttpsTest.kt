package com.longdev.xiaoling.agent

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** long: 捕获生产 HTTPS 请求并交给独立发送队列；认证拒绝、进程恢复和配置中断都不能走裸请求旁路。 */
class RemoteChannelReceiverHttpsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val peer = RemoteChannelPairingPolicy.prepare("https-auth", "sender", "key-1", "认证收件测试", Base64.getEncoder().encodeToString(keys.public.encoded))
    private fun pairs(value: RemoteChannelPeerCandidate = peer) = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
        var raw: String? = null
        override fun loadState() = raw
        override fun saveState(state: String) { raw = state }
    }).apply { confirm(value, value.fingerprintSha256); setEnabled(value.channelId, value.senderId, value.keyId, true) }
    private class SourceStore : RemoteChannelPullSourceStateStore {
        var raw: String? = null
        var writes = 0
        var failAt = Int.MAX_VALUE
        override fun loadState() = raw
        override fun saveState(state: String) { writes++; check(writes != failAt); raw = state }
    }
    private class InboxStore : RemoteChannelInboxStateStore {
        var snapshot: RemoteChannelInboxSnapshot? = null
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
    }
    private class Identity : RemoteChannelReceiverIdentity {
        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var authorization: RemoteChannelReceiverAuthorization? = null
        var failAfterCreate = false
        var signs = 0
        override fun create(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState): RemoteChannelReceiverAuthorization {
            check(authorization == null)
            authorization = RemoteChannelReceiverAuthProtocol.authorization(peer, source, state, Base64.getEncoder().encodeToString(keys.public.encoded))
            check(!failAfterCreate)
            return authorization!!
        }
        override fun export(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource, state: RemoteChannelBatchState) =
            (authorization ?: error("缺钥")).also { assertEquals(it, RemoteChannelReceiverAuthProtocol.authorization(peer, source, state, it.publicKeySpkiBase64)) }
        override fun sign(authorization: RemoteChannelReceiverAuthorization, request: JSONObject, now: Long): String {
            check(this.authorization == authorization)
            signs++
            return RemoteChannelReceiverAuthProtocol.signedRequest(authorization, request, now, RemoteChannelReceiverSigner { content ->
                Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(content); sign() }
            })
        }
    }
    private fun transport(handler: (Request) -> Response) = RemoteChannelHttpsPuller(client =
        OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build())
    private fun response(request: Request, raw: String = "{}", status: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("fixture").header("Content-Type", "application/json")
        .body(raw.toResponseBody("application/json".toMediaType())).build()
    private fun body(request: Request) = okio.Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
    private fun reject(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun enrollmentSurvivesRemoveReaddAndNeverDowngradesOrRebinds() = runBlocking {
        val pairs = pairs(); val store = SourceStore(); val sources = RemoteChannelPullSourceRegistry(store, pairs)
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs); val identity = Identity()
        sources.save(peer, "https://auth.example/batch")
        val service = RemoteChannelPullService(pairs, sources, inbox, receiverIdentity = identity)
        val auth = service.createReceiver(peer)
        assertEquals(auth, service.exportReceiver(peer))
        val restored = RemoteChannelPullSourceRegistry(store, pairs)
        assertTrue(restored.sourceFor(peer)!!.receiverAuthRequired)
        assertEquals(auth, restored.sourceFor(peer)!!.receiverAuthorization)
        assertTrue(restored.remove(peer)); assertNull(restored.sourceFor(peer))
        reject { restored.save(peer, "https://other.example/batch") }
        val readded = restored.save(peer, "https://auth.example/batch")
        assertEquals(auth, readded.receiverAuthorization)
        assertTrue(readded.receiverAuthRequired)
        assertTrue(runCatching { service.createReceiver(peer) }.isFailure)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
    }

    @Test fun interruptedCreateRequiresExplicitExportOfOriginalKey() = runBlocking {
        val pairs = pairs(); val store = SourceStore(); val sources = RemoteChannelPullSourceRegistry(store, pairs)
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs); val identity = Identity().apply { failAfterCreate = true }
        sources.save(peer, "https://auth.example/batch")
        var calls = 0
        val puller = transport { calls++; response(it) }
        val service = RemoteChannelPullService(pairs, sources, inbox, puller, receiverIdentity = identity)
        assertTrue(runCatching { service.createReceiver(peer) }.isFailure)
        assertTrue(sources.sourceFor(peer)!!.receiverAuthRequired)
        assertNull(sources.sourceFor(peer)!!.receiverAuthorization)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        assertEquals(0, calls)
        assertEquals(identity.authorization, service.exportReceiver(peer))
        assertEquals(identity.authorization, sources.sourceFor(peer)!!.receiverAuthorization)
    }

    @Test fun interruptionBeforeKeyGenerationCanBeRetriedOnlyByExplicitCreate() = runBlocking {
        val pairs = pairs(); val sources = RemoteChannelPullSourceRegistry(SourceStore(), pairs)
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs)
        sources.save(peer, "https://auth.example/batch")
        sources.requireReceiverAuth(peer)
        val identity = Identity()
        val service = RemoteChannelPullService(pairs, sources, inbox, receiverIdentity = identity)
        assertTrue(runCatching { service.exportReceiver(peer) }.isFailure)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        assertNull(identity.authorization)
        val created = service.createReceiver(peer)
        assertEquals(created, service.exportReceiver(peer))
    }

    @Test fun ackCommitChecksPermitAfterWaitingForInboxStorage() = runBlocking {
        val pairs = pairs(); val source = RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256, "https://auth.example/batch")
        val receipt = RemoteChannelBatchReceipt("batch-1", "a".repeat(64), "cursor-1", emptyList())
        val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), RemoteChannelBatchProtocol.endpointHash(source),
            "b".repeat(32), "cursor-1", receipt)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val permitted = java.util.concurrent.atomic.AtomicBoolean(true)
        var retained = RemoteChannelInboxSnapshot(emptyList(), emptyList(), batchStates = listOf(state))
        val gateway = RemoteChannelInboxGateway(object : RemoteChannelInboxStateStore {
            override fun loadSnapshot(): RemoteChannelInboxSnapshot {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return retained
            }
            override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { retained = snapshot }
        }, pairs)
        val result = async(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { gateway.acknowledgeBatch(source, peer, receipt) { permitted.get() } }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        permitted.set(false); release.countDown()
        assertTrue(result.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(receipt, retained.batchStates.single().pendingAck)
    }

    @Test fun failedRegistrationCommitFreezesUntilRecreationAndDoesNotRecreateKey() = runBlocking {
        val pairs = pairs(); val store = SourceStore(); val sources = RemoteChannelPullSourceRegistry(store, pairs)
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs); val identity = Identity()
        sources.save(peer, "https://auth.example/batch")
        store.failAt = 3
        val service = RemoteChannelPullService(pairs, sources, inbox, receiverIdentity = identity)
        assertTrue(runCatching { service.createReceiver(peer) }.isFailure)
        reject { sources.list() }
        val restored = RemoteChannelPullSourceRegistry(store, pairs)
        assertTrue(restored.sourceFor(peer)!!.receiverAuthRequired)
        assertNull(restored.sourceFor(peer)!!.receiverAuthorization)
        assertEquals(identity.authorization, RemoteChannelPullService(pairs, restored, inbox, receiverIdentity = identity).exportReceiver(peer))
    }

    @Test fun missingOrChangedReceiverKeyAndMissingSignerMakeNoNetworkRequest() = runBlocking {
        val pairs = pairs(); val store = SourceStore(); val sources = RemoteChannelPullSourceRegistry(store, pairs)
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs); val identity = Identity()
        sources.save(peer, "https://auth.example/batch")
        var calls = 0
        val puller = transport { calls++; response(it) }
        val service = RemoteChannelPullService(pairs, sources, inbox, puller, receiverIdentity = identity)
        service.createReceiver(peer)
        identity.authorization = null
        assertTrue(runCatching { service.exportReceiver(peer) }.isFailure)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        identity.authorization = RemoteChannelReceiverAuthProtocol.authorization(peer, sources.sourceFor(peer)!!,
            inbox.batchState(sources.sourceFor(peer)!!, peer), peer.publicKeySpkiBase64)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        assertTrue(runCatching { RemoteChannelPullService(pairs, sources, inbox, puller).synchronize(peer) }.isFailure)
        assertEquals(0, calls); assertEquals(0, identity.signs)
    }

    @Test fun strictV2SourceCodecPreservesLegacyV1AndRejectsBrokenAuthentication() = runBlocking {
        val pairs = pairs(); val store = SourceStore(); val sources = RemoteChannelPullSourceRegistry(store, pairs)
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs); val identity = Identity()
        val source = sources.save(peer, "https://auth.example/batch")
        val legacy = JSONObject(store.raw!!).put("schemaVersion", 1).apply {
            getJSONArray("sources").getJSONObject(0).apply { remove("receiverAuthRequired"); remove("receiverAuthorization"); remove("registered") }
        }
        assertEquals(listOf(source), RemoteChannelPullPolicy.decode(legacy.toString()))
        RemoteChannelPullService(pairs, sources, inbox, receiverIdentity = identity).createReceiver(peer)
        val raw = store.raw!!
        for ((field, replacement) in listOf("receiverAuthRequired" to false, "registered" to "true", "receiverAuthorization" to "invalid")) {
            val bad = JSONObject(raw).apply { getJSONArray("sources").getJSONObject(0).put(field, replacement) }
            reject { RemoteChannelPullPolicy.decode(bad.toString()) }
        }
        val badManifest = JSONObject(raw).apply { getJSONArray("sources").getJSONObject(0).getJSONObject("receiverAuthorization").put("authVersion", true) }
        reject { RemoteChannelPullPolicy.decode(badManifest.toString()) }
        reject { RemoteChannelPullPolicy.encode(listOf(sources.sourceFor(peer)!!.copy(url = "https://other.example/batch"))) }
    }

    @Test fun oldBackgroundPermitCannotAuthorizeNewReceiverBindingAndV1StillRestores() = runBlocking {
        val pairs = pairs(); val sources = RemoteChannelPullSourceRegistry(SourceStore(), pairs)
        val source = sources.save(peer, "https://auth.example/batch")
        val inbox = RemoteChannelInboxGateway(InboxStore(), pairs)
        val background = RemoteChannelBackgroundRegistry(object : RemoteChannelBackgroundStateStore {
            var raw: String? = null
            override fun loadState() = raw
            override fun saveState(state: String) { raw = state }
        })
        val old = background.enable(peer, source)
        val legacy = JSONObject(RemoteChannelBackgroundRegistry.encode(listOf(old))).put("schemaVersion", 1).apply {
            getJSONArray("entries").getJSONObject(0).remove("receiverBindingSha256")
        }
        assertEquals(listOf(old), RemoteChannelBackgroundRegistry.decode(legacy.toString()))
        RemoteChannelPullService(pairs, sources, inbox, receiverIdentity = Identity()).createReceiver(peer)
        var calls = 0
        val notifier = object : RemoteChannelInboxNotifier { override fun show(identity: String) = false; override fun cancel(identity: String) {} }
        val sync = RemoteChannelBackgroundSynchronizer(background, pairs, sources, inbox, { _, _ -> calls++; error("旧许可不能同步") }, notifier)
        assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED, sync.run(old.sourceIdentity, old.generation))
        assertEquals(0, calls)
        val current = sources.sourceFor(peer)!!
        val next = background.enable(peer, current)
        assertNotNull(next.receiverBindingSha256)
        assertTrue(RemoteChannelBackgroundRegistry.matchesSource(next, current))
        assertFalse(RemoteChannelBackgroundRegistry.matchesSource(old, current))
        assertEquals(listOf(next), RemoteChannelBackgroundRegistry.decode(RemoteChannelBackgroundRegistry.encode(listOf(next))))
    }

    @Test fun productionAuthenticatedPostInteroperatesAndRecoveredAckResignsSameReceipt() = runBlocking {
        val tools = listOf(File("../tools"), File("tools")).first { File(it, "remote_channel_receiver_auth.py").isFile }.canonicalFile
        val stateDir = File(temporary.root, "queue")
        fun cli(script: String, vararg arguments: String, input: String = "", expected: Int = 0): String {
            val process = ProcessBuilder(listOf("python3", File(tools, script).absolutePath, "--state-dir", stateDir.absolutePath) + arguments).start()
            process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            assertEquals(expected, process.exitValue())
            assertTrue(stderr.isEmpty() || stderr.startsWith("receiver auth error:"))
            return stdout
        }
        fun sender(vararg args: String, input: String = "") = cli("remote_channel_sender.py", *args, input = input)
        fun auth(vararg args: String, input: String = "") = cli("remote_channel_receiver_auth.py", *args, input = input)
        val info = JSONObject(sender("init", "--channel-id", "https-auth", "--sender-id", "sender", "--key-id", "key-1"))
        val realPeer = RemoteChannelPairingPolicy.prepare("https-auth", "sender", "key-1", "独立发送方", info.getString("publicKeySpkiBase64"))
        val pairs = pairs(realPeer); val sourceStore = SourceStore(); var sources = RemoteChannelPullSourceRegistry(sourceStore, pairs)
        val source = sources.save(realPeer, "https://auth.example/batch")
        val inboxStore = InboxStore(); var inbox = RemoteChannelInboxGateway(inboxStore, pairs)
        val identity = Identity(); val requests = mutableListOf<JSONObject>(); var dropAck = true; var forbidden = false
        val puller = transport { request ->
            assertEquals("POST", request.method); assertEquals(source.url, request.url.toString())
            assertEquals("1", request.header("X-Xiaoling-Receiver-Auth")); assertNull(request.url.query)
            assertNull(request.header("X-Xiaoling-Cursor")); assertNull(request.header("X-Xiaoling-Receiver"))
            val envelope = JSONObject(body(request)); requests += envelope
            val raw = cli("remote_channel_receiver_auth.py", "execute", "--endpoint-sha256", RemoteChannelBatchProtocol.endpointHash(source),
                input = envelope.toString(), expected = if (forbidden) 2 else 0)
            if (forbidden) response(request, status = 403) else {
                if (envelope.getJSONObject("request").getString("operation") == "ack" && dropAck) {
                    assertNotNull(inbox.batchState(sources.sourceFor(realPeer)!!, realPeer).pendingAck)
                    dropAck = false
                    throw java.io.IOException("ACK response lost")
                }
                response(request, raw)
            }
        }
        var service = RemoteChannelPullService(pairs, sources, inbox, puller, receiverIdentity = identity)
        val registered = service.createReceiver(realPeer)
        auth("init"); auth("authorize", "--fingerprint", registered.receiverFingerprintSha256, input = RemoteChannelReceiverAuthProtocol.manifest(registered))
        sender("enqueue", "--receiver-id", registered.receiverId, "--message-id", "one", "--conversation-key", "conversation", input = "生产认证传输正文😀")
        assertEquals(RemoteChannelPullSyncResult.Batch(1, 0, true), service.synchronize(realPeer))
        val committed = inbox.batchState(sources.sourceFor(realPeer)!!, realPeer)
        assertEquals(2, requests.size)
        val firstAck = requests.last()
        sources = RemoteChannelPullSourceRegistry(sourceStore, pairs); inbox = RemoteChannelInboxGateway(inboxStore, pairs)
        service = RemoteChannelPullService(pairs, sources, inbox, puller, receiverIdentity = identity)
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), service.synchronize(realPeer))
        assertEquals(3, requests.size)
        assertEquals(firstAck.getJSONObject("request").toString(), requests.last().getJSONObject("request").toString())
        assertNotEquals(firstAck.getJSONObject("proof").getString("nonce"), requests.last().getJSONObject("proof").getString("nonce"))
        assertNull(inbox.batchState(sources.sourceFor(realPeer)!!, realPeer).pendingAck)
        assertEquals(committed.cursor, inbox.batchState(sources.sourceFor(realPeer)!!, realPeer).cursor)
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        auth("revoke", "--receiver-id", registered.receiverId); forbidden = true
        assertTrue(runCatching { service.synchronize(realPeer) }.exceptionOrNull() is RemoteChannelPullHttpException)
        assertEquals(4, requests.size)
        assertEquals("pull", requests.last().getJSONObject("request").getString("operation"))
        assertEquals(1, JSONObject(sender("stats")).getInt("acknowledgedMessages"))
    }
}
