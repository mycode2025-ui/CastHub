package com.casthub.core

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/** Owned by one player, called on Main. New media, stop and release invalidate retries. */
class PlaybackRecovery(private val enabled: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val budget = RetryBudget()
    private var pending: Runnable? = null
    private var generation = 0
    val isPending: Boolean get() = pending != null
    val attempts: Int get() = budget.attempts

    fun reset() { cancel(); budget.reset() }
    fun cancel() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
    }
    fun schedule(error: PlaybackException, retry: () -> Unit): Boolean {
        cancel()
        if (!enabled() || !transient(error)) return false
        val wait = budget.nextDelay() ?: return false
        val expected = generation
        pending = Runnable {
            pending = null
            if (generation == expected) retry()
        }.also { handler.postDelayed(it, wait) }
        CastLogger.i("PlaybackRecovery", "自动重试 ${budget.attempts}/3，等待 ${wait / 1000} 秒")
        return true
    }

    private fun transient(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException)
                return cause.responseCode == 408 || cause.responseCode == 429 || cause.responseCode in 500..599
            cause = cause.cause
        }
        return error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }
}
