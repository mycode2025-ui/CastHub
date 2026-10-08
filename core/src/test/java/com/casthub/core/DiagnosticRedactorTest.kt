package com.casthub.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun signedUrlsAndCredentialsAreRemovedFromExport() {
        val raw = "play https://user:secret@host/path-secret/media.mp4?token=abc\nAuthorization: Bearer bearer-secret\nCookie=session-secret"
        val result = DiagnosticRedactor.redact(raw)
        listOf("user", "secret", "path-secret", "abc", "bearer-secret", "session-secret").forEach {
            assertFalse("must redact $it", result.contains(it))
        }
        assertTrue(result.contains("play"))
    }
}
