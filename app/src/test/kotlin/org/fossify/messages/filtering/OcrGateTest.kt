package org.fossify.messages.filtering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The revision 9 OCR gate: who gets recognized, and who never does. */
class OcrGateTest {

    @Test
    fun `contacts skip entirely`() {
        assertFalse(OcrGate.shouldRunOcr(inContacts = true, body = "", hasImage = true))
    }

    @Test
    fun `no image means nothing to recognize`() {
        assertFalse(OcrGate.shouldRunOcr(inContacts = false, body = "", hasImage = false))
    }

    @Test
    fun `blank body with an image runs`() {
        assertTrue(OcrGate.shouldRunOcr(inContacts = false, body = "", hasImage = true))
        assertTrue(OcrGate.shouldRunOcr(inContacts = false, body = "   ", hasImage = true))
    }

    @Test
    fun `short body with an image runs`() {
        assertTrue(OcrGate.shouldRunOcr(inContacts = false, body = "Check this out!", hasImage = true))
    }

    @Test
    fun `body at the limit runs, one char over skips`() {
        val atLimit = "x".repeat(OcrGate.BODY_MAX_CHARS)
        val overLimit = "x".repeat(OcrGate.BODY_MAX_CHARS + 1)
        assertTrue(OcrGate.shouldRunOcr(inContacts = false, body = atLimit, hasImage = true))
        assertFalse(OcrGate.shouldRunOcr(inContacts = false, body = overLimit, hasImage = true))
    }

    @Test
    fun `long body skips even with an image`() {
        assertFalse(
            OcrGate.shouldRunOcr(
                inContacts = false,
                body = "This is a perfectly readable message that needs no help from OCR at all.",
                hasImage = true
            )
        )
    }
}
