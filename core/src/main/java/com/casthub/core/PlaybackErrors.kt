package com.casthub.core

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

object PlaybackErrors {
    fun message(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return when (cause.responseCode) {
                401, 403 -> "视频访问被拒绝或授权已失效（HTTP ${cause.responseCode}），请从手机重新投送。"
                404, 410 -> "视频地址不存在或已失效（HTTP ${cause.responseCode}），请从手机重新投送。"
                else -> "视频服务返回 HTTP ${cause.responseCode}。${if (cause.responseCode >= 500) "请稍后重试或重新投送。" else "请检查媒体地址。"}"
            }
            cause = cause.cause
        }
        return DiagnosticRedactor.redact(error.message ?: error.errorCodeName)
    }
}
