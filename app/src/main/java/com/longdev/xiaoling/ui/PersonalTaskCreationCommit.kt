package com.longdev.xiaoling.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 在本地任务已经提交后捕获其身份，避免取消协程在返回值交接处丢失“已创建”事实。
 *
 * long: 调用方的提交函数只负责 Room 原子写入；身份回调也在不可取消区执行，外层随后
 * 才能根据取消状态决定是继续执行还是把同一 Run/调度收敛为取消，避免重试产生重复任务。
 */
internal suspend fun <T> capturePersonalTaskCommit(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    create: suspend () -> T,
    onCommitted: (T) -> Unit,
) {
    withContext(NonCancellable) {
        val committed = withContext(dispatcher) { create() }
        onCommitted(committed)
    }
}
