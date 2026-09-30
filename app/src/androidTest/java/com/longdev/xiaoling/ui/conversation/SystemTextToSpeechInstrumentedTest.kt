package com.longdev.xiaoling.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 真机只验证系统 TTS 的初始化、中文短句播放回调和资源释放，不把设备音量或发音质量当作自动化断言。
 */
@RunWith(AndroidJUnit4::class)
class SystemTextToSpeechInstrumentedTest {
    @Test
    fun initializesSpeaksChineseSentenceAndShutsDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val finished = CountDownLatch(1)
        val controller = SystemTextToSpeechController(context, finished::countDown)
        try {
            val ready = waitUntil(timeoutMillis = 5_000) { controller.isReady }
            assumeTrue("系统没有可用的 TTS 引擎", ready)
            assertTrue(controller.speak("小灵真机语音验收。"))
            assertTrue("TTS 没有在限定时间内回调完成", finished.await(10, TimeUnit.SECONDS))
        } finally {
            controller.shutdown()
        }
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}
