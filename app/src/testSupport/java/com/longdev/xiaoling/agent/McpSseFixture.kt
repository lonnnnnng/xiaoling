package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** long: 真实分块长连接夹具，POST 和 SSE 回复独立到达；只绑定 loopback，不写正式配置或调用模型。 */
class McpSseFixture : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "mcp-sse-fixture").apply { isDaemon = true } }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val streams = ConcurrentHashMap<Int, Socket>()
    private val stalled = CopyOnWriteArrayList<Pair<Int, JSONObject>>()
    val requests = CopyOnWriteArrayList<JSONObject>()
    val authorization = CopyOnWriteArrayList<String>()
    val getCount = AtomicInteger()
    @Volatile var stallMethod: String? = null
    @Volatile var schemaType = "string"
    @Volatile var protocolVersion = McpPolicy.LEGACY_PROTOCOL_VERSION
    @Volatile var endpointOverride: String? = null
    @Volatile var mimeType = "text/event-stream"
    @Volatile var paginate = false
    @Volatile var failToolPostWith503 = false
    val url get() = "http://127.0.0.1:${listener.localPort}/sse"

    init {
        executor.execute {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                executor.execute { runCatching { serve(socket) }.also { sockets.remove(socket); runCatching { socket.close() } } }
            }
        }
    }

    fun config() = McpServerConfig("fixture-mcp", "Socket SSE", url, "synthetic-token", transport = McpTransportKind.SSE)
    fun methodCount(method: String) = requests.count { it.optString("method") == method }
    fun closeStreams() { streams.values.forEach { runCatching { it.close() } }; streams.clear() }
    fun notifyCatalogChanged() { streams.keys.forEach { emit(it, "message", JSONObject().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed").toString()) } }
    fun releaseStalled() { stalled.toList().forEach { (stream, rpc) -> respond(stream, rpc) }; stalled.clear() }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val start = line(input).split(' ')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = line(input)
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        authorization += headers["authorization"].orEmpty()
        if (start[0] == "GET") {
            val id = getCount.incrementAndGet()
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: $mimeType\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            streams[id] = socket
            emit(id, "endpoint", endpointOverride ?: "/messages?stream=$id")
            try { while (input.read() != -1) { /* long: 客户端关闭 GET 后夹具释放对应长连接。 */ } }
            finally { streams.remove(id) }
        } else {
            val length = headers["content-length"]!!.toInt()
            require(length in 0..McpPolicy.MAX_ARGUMENT_BYTES * 2)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
            val rpc = JSONObject(String(bytes, Charsets.UTF_8))
            requests += rpc
            val stream = start[1].substringAfter("stream=").toInt()
            // long: 先发 SSE 再返回 HTTP ACK，专门覆盖 pending 登记过晚会丢回复的竞态。
            if (rpc.has("id")) {
                if (rpc.optString("method") == stallMethod) stalled += stream to rpc else respond(stream, rpc)
            }
            val status = if (failToolPostWith503 && rpc.optString("method") == "tools/call") "503 Service Unavailable\r\nRetry-After: 0" else "202 Accepted"
            socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
        }
    }

    private fun respond(stream: Int, rpc: JSONObject) {
        val params = rpc.optJSONObject("params") ?: JSONObject()
        val result = when (rpc.optString("method")) {
            "initialize" -> JSONObject().put("protocolVersion", protocolVersion).put("capabilities", JSONObject()
                .put("tools", JSONObject()).put("resources", JSONObject()).put("prompts", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "socket-fixture").put("version", "1"))
            "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject()
                .put("name", if (params.has("cursor")) "fixture.second" else "fixture.echo")
                .put("description", "合成回显工具")
                .put("inputSchema", JSONObject().put("type", "object").put("required", JSONArray().put("text"))
                    .put("properties", JSONObject().put("text", JSONObject().put("type", schemaType))))))
                .apply { if (paginate && !params.has("cursor")) put("nextCursor", "second") }
            "tools/call" -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "echo:${params.getJSONObject("arguments").get("text")}")))
            "resources/list" -> JSONObject().put("resources", JSONArray().put(JSONObject().put("uri", "memo://fixture").put("name", "fixture")))
            "resources/read" -> JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", "memo://fixture").put("text", "fixture-resource")))
            "prompts/list" -> JSONObject().put("prompts", JSONArray().put(JSONObject().put("name", "fixture-summary")
                .put("arguments", JSONArray().put(JSONObject().put("name", "topic").put("required", true)))))
            "prompts/get" -> JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONObject().put("type", "text").put("text", "fixture-prompt"))))
            else -> JSONObject()
        }
        emit(stream, "message", JSONObject().put("jsonrpc", "2.0").put("id", rpc.get("id")).put("result", result).toString())
    }

    private fun emit(stream: Int, type: String, data: String) {
        val socket = streams[stream] ?: return
        val bytes = ": heartbeat\r\nevent: $type\r\ndata: $data\r\n\r\n".toByteArray(Charsets.UTF_8)
        synchronized(socket) {
            runCatching {
                socket.getOutputStream().write("${bytes.size.toString(16)}\r\n".toByteArray())
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().write("\r\n".toByteArray())
                socket.getOutputStream().flush()
            }
        }
    }

    private fun line(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) { val byte = input.read(); check(byte >= 0); if (byte == 10) break; if (byte != 13) bytes.write(byte); require(bytes.size() < 8192) }
        return bytes.toString("UTF-8")
    }

    override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; executor.shutdownNow() }
}

class McpFixtureStore(config: McpServerConfig) : McpServerStore {
    private val configs = linkedMapOf(config.id to config)
    @Synchronized override fun list() = configs.values.toList()
    @Synchronized override fun get(id: String) = configs[id]
    @Synchronized override fun upsert(config: McpServerConfig) = config.also { configs[it.id] = it }
    @Synchronized override fun delete(id: String) = configs.remove(id) != null
}
