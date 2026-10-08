package com.casthub.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 启动一个「异常安全」的协程。
 *
 * 默认的 [CoroutineScope.launch] 一旦子协程抛异常，会取消整个父 scope；
 * 对于长期运行的协议模块（监听 socket、事件流）这是致命的 —— 一个瞬时异常
 * 就会让整个模块静默停止。这里统一捕获并记录，保证模块的其它协程不受影响。
 */
fun CoroutineScope.launchSafely(
    name: String,
    onError: (Throwable) -> Unit = { t ->
        CastLogger.e(TAG, "[$name] 协程未捕获异常", t)
    },
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(CoroutineName(name)) {
    try {
        block()
    } catch (ce: CancellationException) {
        // 协程被正常取消，不算错误
        throw ce
    } catch (t: Throwable) {
        onError(t)
    }
}

private const val TAG = "Coroutine"
