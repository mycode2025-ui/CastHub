package com.casthub.core

object DiagnosticRedactor {
    fun redact(text: String): String = text
        .replace(Regex("(?i)https?://[^\\s<>\"']+"), "[媒体地址已隐藏]")
        .replace(Regex("(?im)(authorization|cookie)\\s*[:=][^\\r\\n]*"), "$1=[已隐藏]")
        .replace(Regex("(?i)(token|password)\\s*[:=]\\s*[^\\s]+"), "$1=[已隐藏]")
}
