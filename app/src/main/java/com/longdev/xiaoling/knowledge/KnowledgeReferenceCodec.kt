package com.longdev.xiaoling.knowledge

import org.json.JSONArray
import org.json.JSONObject

object KnowledgeReferenceCodec {
    fun encode(references: List<KnowledgeReference>): JSONArray {
        return JSONArray().apply {
            references.forEach { reference ->
                put(
                    JSONObject()
                        .put("retrievalId", reference.retrievalId)
                        .put("documentId", reference.documentId)
                        .put("documentName", reference.documentName)
                        .put("documentRevision", reference.documentRevision)
                        .put("chunkId", reference.chunkId)
                        .put("chunkSequence", reference.chunkSequence)
                        .put("startOffset", reference.startOffset)
                        .put("endOffset", reference.endOffset),
                )
            }
        }
    }

    fun encodeToString(references: List<KnowledgeReference>): String = encode(references).toString()

    fun decode(raw: String?): List<KnowledgeReference> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { decode(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun decode(array: JSONArray?): List<KnowledgeReference> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                // long: 引用是审计增强信息，单条历史脏数据不能阻断整条消息或 Run 加载；坏项丢弃后不再作为可信证据传播。
                runCatching {
                    val value = array.getJSONObject(index)
                    KnowledgeReference(
                        retrievalId = value.getString("retrievalId"),
                        documentId = value.getString("documentId"),
                        documentName = value.getString("documentName"),
                        documentRevision = value.getInt("documentRevision"),
                        chunkId = value.getString("chunkId"),
                        chunkSequence = value.getInt("chunkSequence"),
                        startOffset = value.getInt("startOffset"),
                        endOffset = value.getInt("endOffset"),
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }
}
