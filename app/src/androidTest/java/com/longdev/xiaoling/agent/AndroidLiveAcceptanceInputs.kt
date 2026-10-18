package com.longdev.xiaoling.agent

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ProviderRequestConfig
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket

/** long: 真实验收配置只经单次 USB socket 进入测试内存，避免正式偏好、Intent 和 APK 留存凭据。 */
internal fun androidLiveProvider(stage: Int = 292): ProviderRequestConfig {
    requireAndroidLiveScope(stage)
    val args = InstrumentationRegistry.getArguments()
    val port = requireNotNull(args.getString("stage${stage}ConfigPort")).toInt().also { require(it in 1024..65535) }
    val json = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
        listener.soTimeout = 30_000
        androidLiveStatus("STAGE${stage}_CONFIG_READY")
        listener.accept().use { socket ->
            socket.soTimeout = 10_000
            val stream = DataInputStream(socket.getInputStream())
            val size = stream.readInt().also { require(it in 1..16_384) }
            val parsed = JSONObject(decodeMcpUtf8(ByteArray(size).also(stream::readFully)))
            require(parsed.keys().asSequence().toSet() == setOf("baseUrl", "apiKey", "model"))
            require(parsed.getString("model") == "gpt-6-luna")
            require(parsed.getString("baseUrl").startsWith("https://") && parsed.getString("apiKey").isNotBlank())
            socket.getOutputStream().write(1)
            parsed
        }
    }
    return ProviderRequestConfig(json.getString("baseUrl"), json.getString("apiKey"), json.getString("model"),
        providerId = "stage${stage}-live", maxTokens = 4000, httpDebugLoggingEnabled = false)
}

internal fun requireAndroidLiveScope(stage: Int = 292) {
    require(stage in setOf(292, 293, 294, 295, 297, 298, 299))
    require(InstrumentationRegistry.getArguments().getString("stage${stage}RealRun") == "true") { "真实验收必须显式选择并提供内存配置" }
    check(android.os.Build.DEVICE == "begonia" && android.os.Build.VERSION.SDK_INT == 36) { "只允许指定 Redmi/SDK 真机" }
}

// long: 跨进程以键序、类型和长度计算值摘要，避免 XML 序列化顺序变化掩盖真正的偏好改动。
internal fun androidLivePreferenceDigest(values: Map<String, *>): String {
    val content = values.toSortedMap().entries.joinToString("\n") { (key, value) ->
        val text = if (value is Set<*>) value.filterIsInstance<String>().sorted().toString() else value.toString()
        "${key.length}:$key:${value?.javaClass?.name}:${text.length}:$text"
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun androidLiveStatus(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2,
    Bundle().apply { putString("stream", "$message\n") })
