package com.longdev.xiaoling.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAgentHealthPolicyTest {
    @Test
    fun healthDistinguishesOptInAuthorizationConnectionAndReadyStates() {
        assertEquals(
            DeviceAgentHealthState.AGENT_DISABLED,
            DeviceAgentHealthPolicy.evaluate(agentEnabled = false, serviceAuthorized = true, serviceConnected = true),
        )
        assertEquals(
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED,
            DeviceAgentHealthPolicy.evaluate(agentEnabled = true, serviceAuthorized = false, serviceConnected = false),
        )
        assertEquals(
            DeviceAgentHealthState.SERVICE_DISCONNECTED,
            DeviceAgentHealthPolicy.evaluate(agentEnabled = true, serviceAuthorized = true, serviceConnected = false),
        )
        assertEquals(
            DeviceAgentHealthState.READY,
            DeviceAgentHealthPolicy.evaluate(agentEnabled = true, serviceAuthorized = true, serviceConnected = true),
        )
    }
}
