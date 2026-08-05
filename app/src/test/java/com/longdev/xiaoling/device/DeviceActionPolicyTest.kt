package com.longdev.xiaoling.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionPolicyTest {
    private val policy = DeviceActionPolicy()

    @Test
    fun allowlistContainsOnlyExplicitlyValidatedApps() {
        assertTrue(policy.isAppAllowed("com.longdev.xiaoling"))
        assertTrue(policy.isAppAllowed("com.android.calculator2"))
        assertTrue(policy.isAppAllowed("com.google.android.calculator"))
        assertTrue(policy.isAppAllowed("com.android.deskclock"))
        assertTrue(policy.isAppAllowed("com.google.android.deskclock"))
        assertTrue(policy.isAppAllowed("com.android.settings"))
        assertTrue(policy.isAppAllowed("com.google.android.apps.weather"))
        assertFalse(policy.isAppAllowed("com.example.untrusted"))
    }

    @Test
    fun equivalentAppFamiliesKeepRequestedPackageFirstAndRejectOtherApps() {
        assertEquals(
            listOf("com.android.deskclock", "com.google.android.deskclock"),
            DeviceActionPolicy.launchPackageCandidates("com.android.deskclock"),
        )
        assertTrue(
            DeviceActionPolicy.areEquivalentAppPackages(
                "com.android.calculator2",
                "com.google.android.calculator",
            ),
        )
        assertFalse(
            DeviceActionPolicy.areEquivalentAppPackages(
                "com.android.deskclock",
                "com.android.settings",
            ),
        )
    }

    @Test
    fun safeTextIsAllowedButIdentityAndCredentialValuesAreRejected() {
        assertNull(policy.validateTextInput("hello stage3"))
        assertTrue(policy.validateTextInput("sk-abcdefghijklmnop123456") != null)
        assertTrue(policy.validateTextInput("138-0013-8000") != null)
        assertTrue(policy.validateTextInput("6222 0202 0000 1234") != null)
        assertTrue(policy.validateTextInput("user@example.com") != null)
        assertTrue(policy.validateTextInput("短信验证码 123456") != null)
    }
}
