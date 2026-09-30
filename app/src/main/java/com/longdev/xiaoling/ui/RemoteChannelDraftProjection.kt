package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.RemoteChannelDraft
import com.longdev.xiaoling.share.SharedDraftImport

internal fun RemoteChannelDraft.toSharedDraftImport(): SharedDraftImport {
    // long: 远程消息沿用系统分享的前台草稿管线，避免 Channel 自己复制会话替换、附件清理和用户确认规则。
    return SharedDraftImport.Accepted(payload)
}
