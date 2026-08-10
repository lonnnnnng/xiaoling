package com.longdev.xiaoling.ui.conversation

internal object VoiceInputDraftPolicy {
    fun merge(currentDraft: String, candidates: List<String>?): String? {
        val transcript = candidates
            ?.asSequence()
            ?.map(String::trim)
            ?.firstOrNull(String::isNotEmpty)
            ?: return null

        // long: 语音识别只贡献普通编辑文本；是否进入 Agent、任务或普通聊天仍由用户继续编辑并显式发送决定。
        return when {
            currentDraft.isBlank() -> transcript
            currentDraft.last().isWhitespace() -> currentDraft + transcript
            else -> currentDraft + "\n" + transcript
        }
    }
}
