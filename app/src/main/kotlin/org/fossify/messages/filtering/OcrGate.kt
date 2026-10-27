package org.fossify.messages.filtering

/**
 * When image OCR runs at arrival (revision 9): the pure, unit-testable
 * gate. OCR exists to close the image-only MMS gap — a non-contact
 * sender whose message carries an image but little or no text, where
 * keyword rules would otherwise have nothing to match. Contacts skip
 * entirely (contacts are immune to filters, always); long bodies skip
 * because the text already speaks for itself; messages without images
 * have nothing to recognize. Everything else about the step — budget,
 * image count, decode size — lives here too, so the receiver stays a
 * thin caller and the numbers are tunable in one place.
 */
object OcrGate {

    /** Bodies at or below this trimmed length still get OCR (empty counts). */
    const val BODY_MAX_CHARS = 50

    /** Most image attachments OCR'd per message — the budget is total, not per image. */
    const val MAX_IMAGES = 2

    /** Total wall-clock budget for the whole OCR step, milliseconds. Past it, filing proceeds without OCR text: fail open, never block. */
    const val TOTAL_BUDGET_MS = 2000L

    /** Longest side, pixels, of a bitmap handed to the recognizer: accuracy holds, OOM risk and latency drop. */
    const val MAX_SIDE_PX = 1280

    fun shouldRunOcr(inContacts: Boolean, body: String, hasImage: Boolean): Boolean {
        if (inContacts || !hasImage) return false
        return body.trim().length <= BODY_MAX_CHARS
    }
}
