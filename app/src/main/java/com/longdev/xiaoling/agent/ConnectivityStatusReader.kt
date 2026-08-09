package com.longdev.xiaoling.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConnectivityStatusRecord(
    val connected: Boolean,
    val transport: ConnectivityTransport,
    val internetValidated: Boolean,
)

enum class ConnectivityTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
    NONE,
}

sealed interface ConnectivityStatusReadResult {
    data class Success(val status: ConnectivityStatusRecord) : ConnectivityStatusReadResult
    data object Unavailable : ConnectivityStatusReadResult
    data object Failed : ConnectivityStatusReadResult
}

fun interface ConnectivityStatusReader {
    suspend fun read(): ConnectivityStatusReadResult
}

object UnavailableConnectivityStatusReader : ConnectivityStatusReader {
    override suspend fun read(): ConnectivityStatusReadResult = ConnectivityStatusReadResult.Unavailable
}

class AndroidConnectivityStatusReader(context: Context) : ConnectivityStatusReader {
    private val appContext = context.applicationContext

    override suspend fun read(): ConnectivityStatusReadResult = withContext(Dispatchers.IO) {
        try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@withContext ConnectivityStatusReadResult.Unavailable
            val network = manager.activeNetwork
                ?: return@withContext ConnectivityStatusReadResult.Success(
                    ConnectivityStatusRecord(
                        connected = false,
                        transport = ConnectivityTransport.NONE,
                        internetValidated = false,
                    ),
                )
            val capabilities = manager.getNetworkCapabilities(network)
                ?: return@withContext ConnectivityStatusReadResult.Failed
            ConnectivityStatusReadResult.Success(
                ConnectivityStatusRecord(
                    connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    transport = capabilities.toTransport(),
                    internetValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ),
            )
        } catch (_: RuntimeException) {
            // long: OEM 网络栈读取异常时不把活动网络或可达性猜测成可信 Agent 事实。
            ConnectivityStatusReadResult.Failed
        }
    }

    private fun NetworkCapabilities.toTransport(): ConnectivityTransport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectivityTransport.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectivityTransport.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectivityTransport.ETHERNET
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectivityTransport.VPN
        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> ConnectivityTransport.BLUETOOTH
        else -> ConnectivityTransport.OTHER
    }
}

internal object ConnectivityStatusResultCodec {
    fun encode(status: ConnectivityStatusRecord): String = buildString {
        appendLine("网络状态：${if (status.connected) "已连接" else "未连接"}")
        appendLine("网络类型：${status.transport.label()}")
        append("互联网可达：${if (status.internetValidated) "是" else "否"}")
    }

    private fun ConnectivityTransport.label(): String = when (this) {
        ConnectivityTransport.WIFI -> "Wi-Fi"
        ConnectivityTransport.CELLULAR -> "移动网络"
        ConnectivityTransport.ETHERNET -> "以太网"
        ConnectivityTransport.VPN -> "VPN"
        ConnectivityTransport.BLUETOOTH -> "蓝牙网络"
        ConnectivityTransport.OTHER -> "其他网络"
        ConnectivityTransport.NONE -> "无网络"
    }
}
