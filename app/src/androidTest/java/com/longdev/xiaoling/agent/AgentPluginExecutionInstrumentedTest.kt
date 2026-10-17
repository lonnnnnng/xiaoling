package com.longdev.xiaoling.agent

import org.junit.Before
import org.junit.Assert.assertEquals

/** long: 真机向量限定 Redmi，避免测试误操作其他已连接设备。 */
class AgentPluginExecutionInstrumentedTest : AgentPluginExecutionContractTests() {
    @Before fun requireTarget() { assertEquals("begonia", android.os.Build.DEVICE) }
}
