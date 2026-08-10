package com.longdev.xiaoling.share

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedIntentManifestInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun manifestExposesOnlySupportedSingleItemShareTypes() {
        val activityInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0,
        )
        assertTrue(activityInfo.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)

        val supportedMimeTypes = listOf("text/plain", "image/png", "image/jpeg", "image/jpg", "image/webp") +
            DocumentAttachmentPolicy.pickerMimeTypes()
        supportedMimeTypes.distinct().forEach { mimeType ->
            assertTrue("MainActivity should receive $mimeType", resolvesToMainActivity(mimeType))
        }
        assertFalse(resolvesToMainActivity("image/gif"))
        assertFalse(resolvesToMainActivity("application/zip"))
        assertFalse(resolvesToMainActivity("image/png", Intent.ACTION_SEND_MULTIPLE))
        assertFalse(resolvesToMainActivity("application/pdf", Intent.ACTION_SEND_MULTIPLE))
    }

    @Suppress("DEPRECATION")
    private fun resolvesToMainActivity(
        mimeType: String,
        action: String = Intent.ACTION_SEND,
    ): Boolean {
        val intent = Intent(action)
            .setType(mimeType)
            .setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == MainActivity::class.java.name }
    }
}
