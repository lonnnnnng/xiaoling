package com.longdev.xiaoling.json

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import org.json.JSONObject

/** long: Android 与 JVM 保留 JSONObject 接口，标准 JSON 和唯一键由 shared 校验，避免各入口放宽同一证据的边界。 */
object StrictJsonObject {
    fun parse(raw: String, maxChars: Int = 1_000_000): JSONObject {
        StrictJsonObjectSyntax.validate(raw, maxChars)
        return JSONObject(raw)
    }
}
