package com.longdev.xiaoling.device

class DeviceSnapshotPolicy(
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    private val maxTextCharacters: Int = DEFAULT_MAX_TEXT_CHARACTERS,
    private val referenceTtlMillis: Long = DEFAULT_REFERENCE_TTL_MILLIS,
) {
    init {
        require(maxNodes > 0) { "设备快照节点上限必须大于 0" }
        require(maxTextCharacters >= 0) { "设备快照文本预算不能小于 0" }
        require(referenceTtlMillis > 0) { "设备节点引用有效期必须大于 0" }
    }

    fun build(
        window: RawDeviceWindow,
        snapshotId: String,
        nowMillis: Long,
    ): DeviceSnapshotAssessment {
        require(snapshotId.isNotBlank()) { "设备快照 ID 不能为空" }
        if (isPrivateApplication(window.packageName)) {
            return DeviceSnapshotAssessment.Blocked(
                reason = DeviceSnapshotBlockReason.PRIVATE_APPLICATION,
                message = "当前窗口属于受保护应用，未读取或保存节点内容",
            )
        }
        if (containsSensitiveWindowMarker(window.windowTitle) || containsSensitiveWindowMarker(window.root)) {
            return DeviceSnapshotAssessment.Blocked(
                reason = DeviceSnapshotBlockReason.SENSITIVE_WINDOW,
                message = "当前页面涉及支付或高敏身份验证，未读取或保存节点内容",
            )
        }

        val nodes = mutableListOf<DeviceSnapshotNode>()
        val references = mutableListOf<DeviceNodeReference>()
        var remainingTextCharacters = maxTextCharacters
        var redactedNodeCount = 0
        var truncated = window.truncated
        var nextReference = 1

        fun boundedText(raw: String?): String? {
            val normalized = raw
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return null
            if (remainingTextCharacters <= 0) {
                truncated = true
                return null
            }
            val value = normalized.takeWithoutSplittingSurrogatePair(remainingTextCharacters)
            remainingTextCharacters -= value.length
            if (value.length < normalized.length) truncated = true
            return value
        }

        fun visit(raw: RawDeviceNode, parentIndex: Int?, depth: Int) {
            if (!raw.visibleToUser) return
            val actions = buildSet {
                // long: Accessibility 可能仍为禁用控件保留动作标记；ref 只代表此刻确实可操作的节点，不能让模型对灰态控件发起动作。
                if (raw.enabled && raw.clickable) add(DeviceNodeAction.TAP)
                if (raw.enabled && raw.editable) add(DeviceNodeAction.TYPE_TEXT)
                if (raw.enabled && raw.scrollable) add(DeviceNodeAction.SWIPE)
            }
            val meaningful = actions.isNotEmpty() || raw.checkable ||
                !raw.text.isNullOrBlank() || !raw.contentDescription.isNullOrBlank() || !raw.hintText.isNullOrBlank()
            var childParentIndex = parentIndex
            if (meaningful) {
                if (nodes.size >= maxNodes) {
                    truncated = true
                    return
                }
                val redacted = isSensitiveNode(raw)
                if (redacted) redactedNodeCount += 1
                val ref = if (actions.isNotEmpty() && !redacted) "r${nextReference++}" else null
                val index = nodes.size
                val node = DeviceSnapshotNode(
                    index = index,
                    parentIndex = parentIndex,
                    depth = depth,
                    role = raw.className.toDeviceRole(),
                    text = if (redacted) null else boundedText(raw.text),
                    description = if (redacted) null else boundedText(raw.contentDescription),
                    hint = if (redacted) null else boundedText(raw.hintText),
                    bounds = raw.bounds,
                    enabled = raw.enabled,
                    checked = raw.checked.takeIf { raw.checkable },
                    selected = raw.selected,
                    redacted = redacted,
                    ref = ref,
                    actions = if (redacted) emptySet() else actions,
                )
                nodes += node
                childParentIndex = index
                if (ref != null) {
                    references += DeviceNodeReference(
                        ref = ref,
                        nodePath = raw.nodePath,
                        fingerprint = DeviceNodeFingerprint.compute(
                            className = raw.className,
                            bounds = raw.bounds,
                            text = raw.text,
                            contentDescription = raw.contentDescription,
                            hintText = raw.hintText,
                        ),
                        actions = actions,
                    )
                }
            }
            raw.children.forEach { child ->
                if (nodes.size >= maxNodes) {
                    truncated = true
                    return@forEach
                }
                visit(child, childParentIndex, depth + 1)
            }
        }

        visit(window.root, parentIndex = null, depth = 0)
        return DeviceSnapshotAssessment.Available(
            snapshot = DeviceSnapshot(
                snapshotId = snapshotId,
                packageName = window.packageName,
                windowTitle = boundedText(window.windowTitle),
                windowId = window.windowId,
                windowGeneration = window.generation,
                capturedAt = nowMillis,
                expiresAt = nowMillis + referenceTtlMillis,
                nodes = nodes,
                redactedNodeCount = redactedNodeCount,
                truncated = truncated,
            ),
            references = references,
        )
    }

    private fun containsSensitiveWindowMarker(root: RawDeviceNode): Boolean {
        fun visit(node: RawDeviceNode): Boolean {
            val values = listOf(node.text, node.contentDescription, node.hintText)
            if (values.any { value ->
                    val normalized = value.orEmpty().lowercase()
                    SENSITIVE_WINDOW_MARKERS.any(normalized::contains)
                }
            ) {
                return true
            }
            return node.children.any(::visit)
        }
        return visit(root)
    }

    private fun containsSensitiveWindowMarker(value: String?): Boolean {
        val normalized = value.orEmpty().lowercase()
        return SENSITIVE_WINDOW_MARKERS.any(normalized::contains)
    }

    private fun isSensitiveNode(node: RawDeviceNode): Boolean {
        if (node.password) return true
        val values = listOf(node.text, node.contentDescription, node.hintText)
            .filterNotNull()
        if (values.any { value ->
                val normalized = value.lowercase()
                SENSITIVE_FIELD_MARKERS.any(normalized::contains)
            }
        ) {
            return true
        }
        val joined = values.joinToString(" ")
        val digitsOnly = joined.filter(Char::isDigit)
        val identityOnly = joined.filter { it.isDigit() || it == 'x' || it == 'X' }
        return values.any { API_KEY_PATTERN.containsMatchIn(it) || BEARER_TOKEN_PATTERN.containsMatchIn(it) } ||
            PHONE_PATTERN.containsMatchIn(digitsOnly) ||
            IDENTITY_PATTERN.containsMatchIn(identityOnly) ||
            PAYMENT_CARD_PATTERN.containsMatchIn(digitsOnly) ||
            EMAIL_PATTERN.containsMatchIn(joined)
    }

    private fun isPrivateApplication(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return normalized in PRIVATE_APPLICATION_PACKAGES ||
            PRIVATE_PACKAGE_MARKERS.any(normalized::contains)
    }

    private fun String.toDeviceRole(): String {
        val simpleName = substringAfterLast('.').lowercase()
        return when {
            "edittext" in simpleName -> "text_field"
            "button" in simpleName -> "button"
            "checkbox" in simpleName -> "checkbox"
            "switch" in simpleName -> "switch"
            "radiobutton" in simpleName -> "radio_button"
            "image" in simpleName -> "image"
            "recycler" in simpleName || "list" in simpleName -> "list"
            "scroll" in simpleName -> "scroll_container"
            "text" in simpleName -> "text"
            else -> "view"
        }
    }

    private fun String.takeWithoutSplittingSurrogatePair(maxCharacters: Int): String {
        if (length <= maxCharacters) return this
        var end = maxCharacters.coerceAtLeast(0)
        if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
            end -= 1
        }
        return take(end)
    }

    companion object {
        const val DEFAULT_MAX_NODES = 200
        const val DEFAULT_MAX_TEXT_CHARACTERS = 4_000
        const val DEFAULT_REFERENCE_TTL_MILLIS = 30_000L

        private val PRIVATE_APPLICATION_PACKAGES = setOf(
            "com.x8bit.bitwarden",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "com.eg.android.alipaygphone",
        )
        private val PRIVATE_PACKAGE_MARKERS = setOf(
            "password",
            "keepass",
            "authenticator",
            ".bank",
            ".wallet",
        )
        private val SENSITIVE_WINDOW_MARKERS = setOf(
            "支付密码",
            "确认支付",
            "立即付款",
            "收银台",
            "payment password",
            "confirm payment",
            "checkout payment",
        )
        private val SENSITIVE_FIELD_MARKERS = setOf(
            "密码",
            "password",
            "passcode",
            "pin code",
            "验证码",
            "校验码",
            "动态码",
            "短信码",
            "otp",
            "one-time code",
            "verification code",
            "security code",
            "支付密码",
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
