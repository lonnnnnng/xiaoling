package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.RemoteChannelDraft
import com.longdev.xiaoling.share.SharedDraftImport

internal fun RemoteChannelDraft.toSharedDraftImport(): SharedDraftImport {
    // long: 远程消息即使遇到空编辑器也必须先经过前台确认；不能沿用普通系统分享的自动打开分支。
    return SharedDraftImport.Accepted(payload, requiresForegroundConfirmation = true)
}
