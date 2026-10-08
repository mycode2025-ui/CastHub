package com.casthub.airplay

import com.casthub.airplay.plist.Plist
import org.junit.Assert.*
import org.junit.Test

class PlayRequestParserTest {
    private val url = "https://example.org/video.mp4?x=1&y=2"
    @Test fun independentSenderTextParametersWithBothNewlines() {
        listOf("\n", "\r\n").forEach { newline ->
            val body = "Content-Location: $url${newline}Start-Position: 0.5$newline$newline"
            assertEquals(url to 0.5, PlayRequestParser.parse(body.toByteArray(), emptyMap()))
        }
    }
    @Test fun binaryXmlJsonAndHeaderVariants() {
        val values = mapOf("Content-Location" to url, "Start-Position" to 0.25)
        assertEquals(url to 0.25, PlayRequestParser.parse(Plist.toBinary(values)!!, emptyMap()))
        assertEquals(url to 0.25, PlayRequestParser.parse(Plist.toXml(values).toByteArray(), emptyMap()))
        val json = "{\"Content-Location\":\"https:\\/\\/example.org/video.mp4?x=1&y=2\",\"Start-Position\":0.25}"
        assertEquals(url to 0.25, PlayRequestParser.parse(json.toByteArray(), emptyMap()))
        assertEquals(url to 0.75, PlayRequestParser.parse(json.toByteArray(), mapOf("Content-Location" to url, "Start-Position" to "0.75")))
    }
    @Test fun rejectsInvalidPositionAndLocalFiles() {
        listOf("NaN", "Infinity", "-1", "1.1").forEach {
            assertNull(PlayRequestParser.parse(byteArrayOf(), mapOf("content-location" to url, "start-position" to it)))
        }
        assertNull(PlayRequestParser.parse(byteArrayOf(), mapOf("content-location" to "file:///data/secret")))
        assertNull(PlayRequestParser.parse(byteArrayOf(), emptyMap()))
    }
}
