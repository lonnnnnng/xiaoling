package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppDirectoryPolicyTest {
    @Test
    fun normalizesSortsDeduplicatesAndClassifiesWithoutGrantingActions() {
        val directory = InstalledAppDirectoryPolicy.normalize(
            listOf(
                InstalledAppCandidateInput("天气", "com.example.weather"),
                InstalledAppCandidateInput("天气", "com.example.weather"),
                InstalledAppCandidateInput("系统设置", "com.example.settings"),
                InstalledAppCandidateInput("计算器", "com.example.calculator"),
            ),
        )

        assertEquals(
            listOf("计算器", "天气", "系统设置"),
            directory.apps.map(InstalledAppRecord::appName),
        )
        assertEquals(
            listOf(
                InstalledAppCapability.CALCULATOR,
                InstalledAppCapability.WEATHER,
                InstalledAppCapability.SETTINGS,
            ),
            directory.apps.map(InstalledAppRecord::capability),
        )
        assertFalse(directory.truncated)
    }

    @Test
    fun outputOnlyContainsDirectoryFieldsAndMarksTruncation() {
        val inputs = (0..InstalledAppDirectoryPolicy.MAX_APPS).map { index ->
            InstalledAppCandidateInput("应用 $index", "com.example.app$index")
        }
        val directory = InstalledAppDirectoryPolicy.normalize(inputs)
        val encoded = InstalledAppDirectoryResultCodec.encode(directory)

        assertEquals(InstalledAppDirectoryPolicy.MAX_APPS, directory.apps.size)
        assertTrue(directory.truncated)
        assertTrue(encoded.contains("launcher_directory"))
        assertTrue(encoded.contains("capability"))
        assertFalse(encoded.contains("version"))
        assertFalse(encoded.contains("signature"))
        assertFalse(encoded.contains("permission"))
    }
}
