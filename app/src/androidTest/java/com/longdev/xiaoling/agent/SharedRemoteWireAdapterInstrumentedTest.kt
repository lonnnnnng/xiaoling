package com.longdev.xiaoling.agent

import android.os.Build
import org.junit.Before
import org.junit.Assert.assertEquals

class SharedRemoteWireAdapterInstrumentedTest : SharedRemoteWireContractTests() {
    @Before fun requireDesignatedRedmi() { assertEquals("begonia", Build.DEVICE) }
}
