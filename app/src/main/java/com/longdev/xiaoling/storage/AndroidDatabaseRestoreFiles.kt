package com.longdev.xiaoling.storage

import android.system.Os
import android.system.OsConstants
import java.io.File

internal fun androidDatabaseRestoreFiles(current: File): DatabaseRestoreFiles = DatabaseRestoreFiles(
    current,
    syncDirectory = { directory ->
        // long: rename 和标记创建/删除改变的是目录项，必须同步目录，才能让断电后按已落盘的安装标记回滚。
        require(directory.isDirectory) { "恢复目录不存在" }
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    },
)
