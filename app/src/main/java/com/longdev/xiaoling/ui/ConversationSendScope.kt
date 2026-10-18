package com.longdev.xiaoling.ui

/** long: A→B→A 仍属于另一轮选择；冻结发送代次，迟到完整正文也不能给新会话签发卡片。 */
internal class ConversationSendScope {
    data class Identity(val conversationId: String, val generation: Long)
    private var generation = 0L
    private var active: Identity? = null
    fun begin(conversationId: String) = Identity(conversationId, ++generation).also { active = it }
    fun accepts(identity: Identity, conversationId: String) = active == identity && identity.conversationId == conversationId && identity.generation == generation
    fun invalidate(): Boolean { val existed = active != null; active = null; generation++; return existed }
    fun finish(identity: Identity) { if (active == identity) active = null }
}
