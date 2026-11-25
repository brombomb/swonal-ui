package com.swonal.kiosk.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OfflineVoiceRecognizerTest {

    @Test
    fun extractsNonEmptyTextFromVoskJson() {
        assertEquals(
            "set weight to forty five pounds",
            OfflineVoiceRecognizer.extractUtteranceText("""{"text" : "  set weight to forty five pounds  "}""")
        )
    }

    @Test
    fun ignoresEmptyOrMalformedPayloads() {
        assertNull(OfflineVoiceRecognizer.extractUtteranceText(null))
        assertNull(OfflineVoiceRecognizer.extractUtteranceText(""))
        assertNull(OfflineVoiceRecognizer.extractUtteranceText("""{"text": ""}"""))
        assertNull(OfflineVoiceRecognizer.extractUtteranceText("""{"partial": "hello"}"""))
        assertNull(OfflineVoiceRecognizer.extractUtteranceText("not-json"))
    }
}
