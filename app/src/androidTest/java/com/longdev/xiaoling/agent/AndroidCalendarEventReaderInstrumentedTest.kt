package com.longdev.xiaoling.agent

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AndroidCalendarEventReaderInstrumentedTest {
    @Test
    fun grantedCalendarProviderReturnsBoundedChronologicalResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权只读日历",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val startAtMillis = System.currentTimeMillis()
        val result = AndroidCalendarEventReader(context.contentResolver).listEvents(
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + TimeUnit.DAYS.toMillis(7),
            limit = 10,
        )

        // long: 真机验收只证明系统 Provider 可被只读查询并遵守数量/时间排序边界，不为测试创建或修改用户日程。
        assertTrue(result is CalendarEventReadResult.Success)
        val events = (result as CalendarEventReadResult.Success).events
        assertTrue(events.size <= 10)
        assertTrue(events.zipWithNext().all { (left, right) -> left.startAtMillis <= right.startAtMillis })
    }
}
