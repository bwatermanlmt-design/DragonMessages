package org.fossify.messages.filtering

import org.fossify.messages.helpers.LockedMediaCache
import org.fossify.messages.helpers.LockerManager
import org.fossify.messages.models.MmsPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The locked-thread display fixes (1.10.16): MMS text must come from
 * the TEXT column, not the part stream — and decrypted media files
 * get opaque, deterministic names under one directory per gate
 * session.
 */
class LockerDisplayTest {

    private fun part(
        contentType: String,
        text: String? = null,
        data: String? = null,
        filename: String? = null,
        name: String? = null
    ): MmsPart {
        return MmsPart(
            contentDisposition = null,
            charset = null,
            contentId = null,
            contentLocation = null,
            contentType = contentType,
            ctStart = null,
            ctType = null,
            filename = filename,
            name = name,
            sequenceOrder = 0,
            text = text,
            data = data
        )
    }

    @Test
    fun mmsTextComesFromTheTextColumnNotTheStream() {
        // The 1.10.16 bug: the display path read MmsPart.data (the
        // part's input stream, empty for text parts) instead of
        // MmsPart.text (the TEXT column) — so locked MMS showed the
        // attachment label but no text.
        val parts = listOf(part("text/plain", text = "Big photo MMS body", data = ""))
        assertEquals("Big photo MMS body", LockerManager.mmsDisplayText(parts))
    }

    @Test
    fun mmsTextFallsBackToDataWhenTextIsNull() {
        val parts = listOf(part("text/plain", text = null, data = "stream text"))
        assertEquals("stream text", LockerManager.mmsDisplayText(parts))
    }

    @Test
    fun mmsTextJoinsMultipleTextParts() {
        val parts = listOf(
            part("text/plain", text = "first", data = ""),
            part("text/plain", text = "second", data = "")
        )
        assertEquals("first\nsecond", LockerManager.mmsDisplayText(parts))
    }

    @Test
    fun mmsTextIgnoresNonTextParts() {
        val parts = listOf(
            part("text/plain", text = "hello", data = ""),
            part("image/jpeg", text = null, data = "aGVsbG8=")
        )
        assertEquals("hello", LockerManager.mmsDisplayText(parts))
    }

    @Test
    fun mmsTextIsEmptyWhenPartsHaveNeither() {
        val parts = listOf(part("text/plain", text = null, data = null))
        assertEquals("", LockerManager.mmsDisplayText(parts))
    }

    @Test
    fun mediaExtensionComesFromContentTypeOnly() {
        assertEquals(".jpg", LockedMediaCache.extensionFor("image/jpeg"))
        assertEquals(".png", LockedMediaCache.extensionFor("image/png"))
        assertEquals(".gif", LockedMediaCache.extensionFor("image/gif"))
        assertEquals(".webp", LockedMediaCache.extensionFor("image/webp"))
        assertEquals(".mp4", LockedMediaCache.extensionFor("video/mp4"))
        assertEquals(".mp3", LockedMediaCache.extensionFor("audio/mpeg"))
        assertEquals(".vcf", LockedMediaCache.extensionFor("text/vcard"))
        assertEquals("", LockedMediaCache.extensionFor("application/octet-stream"))
        assertEquals("", LockedMediaCache.extensionFor("application/smil"))
    }

    @Test
    fun mediaFileNameIsOpaqueAndDeterministic() {
        // No original filename anywhere in it — a filename can leak
        // content ("passport-scan.jpg"). Same inputs, same name, so
        // a reload overwrites instead of accumulating copies.
        val name = LockedMediaCache.fileName(12345L, 0, "image/jpeg")
        assertEquals("12345-0.jpg", name)
        assertEquals(name, LockedMediaCache.fileName(12345L, 0, "image/jpeg"))
        assertTrue(!name.contains("passport"))
    }

    @Test
    fun mediaFileNameDiffersPerPart() {
        assertEquals("12345-0.jpg", LockedMediaCache.fileName(12345L, 0, "image/jpeg"))
        assertEquals("12345-1.jpg", LockedMediaCache.fileName(12345L, 1, "image/jpeg"))
        assertEquals("12345-0.png", LockedMediaCache.fileName(12345L, 0, "image/png"))
    }
}
