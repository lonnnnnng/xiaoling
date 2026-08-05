package com.longdev.xiaoling.device

class DeviceActionPolicy(
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
) {
    init {
        require(allowedPackages.isNotEmpty()) { "设备 Agent 至少需要一个允许打开的应用" }
        require(allowedPackages.none(String::isBlank)) { "设备 Agent 应用白名单不能包含空包名" }
    }

    fun isAppAllowed(packageName: String): Boolean = packageName in allowedPackages

    fun validateTextInput(text: String): String? {
        if (text.isBlank()) return "输入文本不能为空"
        if (text.length > MAX_TEXT_LENGTH) return "输入文本不能超过 $MAX_TEXT_LENGTH 个字符"
        if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) return "输入文本包含不支持的控制字符"
        val normalized = text.lowercase()
        val digitsOnly = text.filter(Char::isDigit)
        val identityOnly = text.filter { it.isDigit() || it == 'x' || it == 'X' }
        val sensitive = SENSITIVE_MARKERS.any(normalized::contains) ||
            API_KEY_PATTERN.containsMatchIn(text) ||
            BEARER_TOKEN_PATTERN.containsMatchIn(text) ||
            PHONE_PATTERN.containsMatchIn(digitsOnly) ||
            IDENTITY_PATTERN.containsMatchIn(identityOnly) ||
            PAYMENT_CARD_PATTERN.containsMatchIn(digitsOnly) ||
            EMAIL_PATTERN.containsMatchIn(text)
        return if (sensitive) "当前设备动作阶段不允许输入可能包含账号、密钥或身份信息的文本" else null
    }

    companion object {
        const val MAX_TEXT_LENGTH = 500

        private val EQUIVALENT_APP_FAMILIES = listOf(
            linkedSetOf(
                "com.android.calculator2",
                "com.google.android.calculator",
            ),
            linkedSetOf(
                "com.android.deskclock",
                "com.google.android.deskclock",
            ),
        )

        val DEFAULT_ALLOWED_PACKAGES = setOf(
            "com.longdev.xiaoling",
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.android.settings",
            "com.google.android.apps.weather",
        )

        fun launchPackageCandidates(requestedPackageName: String): List<String> {
            val family = EQUIVALENT_APP_FAMILIES.firstOrNull { requestedPackageName in it }
                ?: return listOf(requestedPackageName)
            // long: Workflow 冻结的是应用能力而非 ROM 的具体实现；仍优先请求包，只在它没有启动入口时尝试同族白名单包。
            return listOf(requestedPackageName) + family.filterNot { it == requestedPackageName }
        }

        fun areEquivalentAppPackages(expectedPackageName: String?, actualPackageName: String?): Boolean {
            if (expectedPackageName == null || actualPackageName == null) return false
            return actualPackageName in launchPackageCandidates(expectedPackageName)
        }

        private val SENSITIVE_MARKERS = setOf(
            "密码",
            "验证码",
            "password",
            "passcode",
            "one-time code",
            "verification code",
            "api key",
            "access token",
            "bearer token",
        )
        private val PHONE_PATTERN = Regex("1[3-9]\\d{9}")
        private val IDENTITY_PATTERN = Regex("\\d{17}[0-9xX]")
        private val PAYMENT_CARD_PATTERN = Regex("\\d{13,19}")
        private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val API_KEY_PATTERN = Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b")
        private val BEARER_TOKEN_PATTERN = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{12,}={0,2}")
    }
}
