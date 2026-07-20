package com.longdev.xiaoling.device

import java.security.MessageDigest

object DeviceNodeFingerprint {
    fun compute(
        className: String,
        bounds: DeviceBounds,
        text: String?,
        contentDescription: String?,
        hintText: String?,
    ): String {
        val source = buildString {
            append(className)
            append('|').append(bounds.left).append(',').append(bounds.top).append(',')
            append(bounds.right).append(',').append(bounds.bottom)
            append('|').append(text.orEmpty().take(80))
            append('|').append(contentDescription.orEmpty().take(80))
            append('|').append(hintText.orEmpty().take(80))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
