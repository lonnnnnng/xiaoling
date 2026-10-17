package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** long: 默认生产传输在 Redmi 的隔离 loopback 验收；不读正式 MCP/Provider 配置，不产生模型请求。 */
@RunWith(AndroidJUnit4::class)
class McpSseSessionInstrumentedTest : McpSseContractTests()
