package com.longdev.xiaoling.assistant

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 真机只确认系统可解析标准 ASSIST Activity 与受保护 VoiceInteractionService；不自动修改默认助手选择。
 */
@RunWith(AndroidJUnit4::class)
class XiaoLingAssistantEntryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun exposesStandardAssistActivityAndVoiceInteractionService() {
        val assistActivity = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_ASSIST).addCategory(Intent.CATEGORY_DEFAULT),
            0,
        )
        assertNotNull(assistActivity)
        assertTrue(assistActivity?.activityInfo?.name == MainActivity::class.java.name)

        val service = context.packageManager.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionService"),
            0,
        ).firstOrNull { it.serviceInfo.packageName == context.packageName }
        assertNotNull(service)
        assertTrue(service?.serviceInfo?.permission == "android.permission.BIND_VOICE_INTERACTION")
    }
}
