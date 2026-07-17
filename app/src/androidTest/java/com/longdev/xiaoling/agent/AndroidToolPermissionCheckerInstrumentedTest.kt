package com.longdev.xiaoling.agent

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidToolPermissionCheckerInstrumentedTest {
    @Test
    fun reportsOnlyPermissionsMissingFromCurrentAppGrantState() {
        val checker = AndroidToolPermissionChecker(ApplicationProvider.getApplicationContext())
        val missing = checker.missingPermissions(
            setOf(
                Manifest.permission.INTERNET,
                "com.longdev.xiaoling.permission.NOT_DECLARED",
            ),
        )

        assertEquals(setOf("com.longdev.xiaoling.permission.NOT_DECLARED"), missing)
    }
}
