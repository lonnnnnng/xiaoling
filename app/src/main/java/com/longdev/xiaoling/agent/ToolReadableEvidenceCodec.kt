package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedToolReadableEvidenceCodec
import org.json.JSONObject

/** long: 原 RunEvent、可信上下文和 Room 接口保持 JSONObject，证据字段和严格原文校验统一交 shared codec。 */
internal object ToolReadableEvidenceCodec {
    fun encode(evidence: ToolReadableEvidence): JSONObject = JSONObject(SharedToolReadableEvidenceCodec.encode(evidence))

    // long: Android 继续用原 JSONObject 输出样式写账本，不对旧持久正文进行重编码或重算历史摘要。
    fun encodeToString(evidence: ToolReadableEvidence): String = encode(evidence).toString()

    fun decode(json: JSONObject?): ToolReadableEvidence? = decode(json?.toString())

    fun decode(raw: String?): ToolReadableEvidence? = SharedToolReadableEvidenceCodec.decode(raw)
}
