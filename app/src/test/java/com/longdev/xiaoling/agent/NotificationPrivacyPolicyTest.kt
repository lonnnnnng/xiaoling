package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPrivacyPolicyTest {
    @Test
    fun ordinaryTextIsBoundedAndNormalized() {
        assertEquals("项目评审 今天 14:00 开始", NotificationPrivacyPolicy.sanitize("项目评审\n今天 14:00 开始"))
        assertEquals(500, NotificationPrivacyPolicy.sanitize("a".repeat(600))?.length)
    }

    @Test
    fun credentialsAndVerificationCodesAreHidden() {
        assertNull(NotificationPrivacyPolicy.sanitize("登录验证码 123456，请勿泄露"))
        assertNull(NotificationPrivacyPolicy.sanitize("API Key 已更新"))
        assertNull(NotificationPrivacyPolicy.sanitize("Your one-time code is 842913"))
    }

    @Test
    fun sensitiveTitleHidesTheWholeNotification() {
        val (title, content) = NotificationPrivacyPolicy.sanitizeNotification(
            title = "登录验证码 123456",
            content = "请勿向任何人泄露验证码",
        )

        assertNull(title)
        assertNull(content)
    }
}
