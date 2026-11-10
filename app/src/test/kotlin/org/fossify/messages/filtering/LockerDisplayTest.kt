package org.fossify.messages.filtering

import org.fossify.messages.helpers.LockedMediaCache
import org.fossify.messages.helpers.LockerManager
import org.fossify.messages.models.LockedMessage
import org.fossify.messages.models.MmsAddress
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.MmsPart
import org.fossify.messages.models.SmsBackup
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
    fun vCardIsAMediaPartNotText() {
        // A vCard's payload is stream bytes, not the TEXT column —
        // it must render as a vCard card, never as body text, and its
        // bytes must survive an unlock (writeMmsPart keys on this).
        val vcard = part("text/vcard", text = null, data = "BEGIN:VCARD\nEND:VCARD")
        assertTrue(vcard.isVCard())
        assertTrue(vcard.isNonText())
        assertTrue(part("text/x-vcard", text = null, data = "BEGIN:VCARD").isNonText())
        assertTrue(!part("text/plain", text = "hi", data = "").isNonText())
    }

    @Test
    fun mmsTextExcludesVCardParts() {
        val parts = listOf(
            part("text/plain", text = "hello", data = ""),
            part("text/vcard", text = null, data = "BEGIN:VCARD\nFN:Test\nEND:VCARD")
        )
        assertEquals("hello", LockerManager.mmsDisplayText(parts))
    }

    @Test
    fun vCardMediaBytesHandlesRawText() {
        // Rows locked by 1.10.11-1.10.15 stored a vCard's data as
        // raw UTF-8 text (the reader then keyed on the "text/"
        // prefix). mediaBytes must still read those correctly:
        // raw text starts with "BEGIN:", and ":" is not a Base64
        // character, so it can never be mistaken for Base64.
        // (The Base64 branch cannot run in JVM unit tests —
        // android.util.Base64 is a stub there — it is proven by
        // the emulator lock/unlock round trip instead.)
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Test Person\r\nTEL:+15557771016\r\nEND:VCARD\r\n"
        val vcard = part("text/vcard", text = null, data = text)
        val bytes = vcard.mediaBytes()
        assertTrue(bytes != null)
        assertEquals(text, String(bytes!!, Charsets.UTF_8))
        assertEquals(71, bytes.size)
    }

    @Test
    fun vCardMediaBytesNullOrEmptyWhenNoData() {
        assertEquals(null, part("text/vcard", text = null, data = null).mediaBytes())
        assertEquals(null, part("text/vcard", text = null, data = "").mediaBytes())
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

    // The 1.10.17 display envelope: deriveDisplay computes the plain
    // fields once, at lock time, so the thread view reads them
    // instead of re-deriving them from the backup's provider-shaped
    // fields.

    private fun smsBackup(address: String = "+15551234567", body: String? = "hello"): SmsBackup {
        return SmsBackup(
            subscriptionId = 1L,
            address = address,
            body = body,
            date = 1794000000000L,
            dateSent = 1793999999000L,
            locked = 0,
            protocol = null,
            read = 1,
            status = -1,
            type = 1,
            serviceCenter = null
        )
    }

    private fun mmsBackup(
        messageBox: Int = 1,
        addresses: List<MmsAddress> = listOf(MmsAddress("+15551234567", 137, 106)),
        parts: List<MmsPart> = listOf(
            part("text/plain", text = "photo and words", data = ""),
            part("image/jpeg", filename = "photo.jpg", data = "aGVsbG8=")
        )
    ): MmsBackup {
        return MmsBackup(
            creator = "com.messages.dragon",
            contentType = "application/vnd.wap.multipart.related",
            deliveryReport = 0,
            date = 1794000000L,
            dateSent = 1793999999L,
            locked = 0,
            messageType = 132,
            messageBox = messageBox,
            read = 1,
            readReport = 0,
            seen = 1,
            textOnly = 0,
            status = null,
            subject = null,
            subjectCharSet = null,
            subscriptionId = 1L,
            transactionId = "T123",
            addresses = addresses,
            parts = parts
        )
    }

    @Test
    fun smsEnvelopeCarriesSenderBodyAndParticipant() {
        val display = LockerManager.deriveDisplay(smsBackup())
        assertEquals("+15551234567", display.sender)
        assertEquals("hello", display.body)
        assertEquals(listOf("+15551234567"), display.participants)
        assertTrue(display.attachments.isEmpty())
    }

    @Test
    fun mmsEnvelopeCarriesFromSenderAndMediaDescriptor() {
        val display = LockerManager.deriveDisplay(mmsBackup())
        assertEquals("+15551234567", display.sender)
        assertEquals("photo and words", display.body)
        assertEquals(listOf("+15551234567"), display.participants)
        assertEquals(1, display.attachments.size)
        // The descriptor's index is into the full parts list, so the
        // display finds the photo part, not the text part.
        assertEquals(1, display.attachments[0].partIndex)
        assertEquals("image/jpeg", display.attachments[0].mimeType)
        assertEquals("photo.jpg", display.attachments[0].fileName)
    }

    @Test
    fun sentMmsEnvelopeHasEmptySenderButKeepsParticipants() {
        // messageBox 2 is SENT: the sender is the user, so it stays
        // empty, but the TO addresses are still the participants.
        val display = LockerManager.deriveDisplay(
            mmsBackup(
                messageBox = 2,
                addresses = listOf(MmsAddress("+15559876543", 151, 106))
            )
        )
        assertEquals("", display.sender)
        assertEquals(listOf("+15559876543"), display.participants)
    }

    @Test
    fun addressLessMmsEnvelopeFallsBackToConversationAddress() {
        // The empty-view shape: no addresses anywhere in the backup.
        // The thread's conversation address stands in for both the
        // sender and the participants.
        val display = LockerManager.deriveDisplay(
            mmsBackup(addresses = emptyList()),
            fallbackAddress = "+15550001111"
        )
        assertEquals("+15550001111", display.sender)
        assertEquals(listOf("+15550001111"), display.participants)
        assertEquals("photo and words", display.body)
    }

    @Test
    fun placeholderKeepsRowIdentityAndNeverVanishes() {
        val row = LockedMessage(
            id = 987L,
            threadId = 12L,
            isMMS = true,
            date = 1794000000000L,
            iv = "AA=="
        )
        val message = LockerManager.placeholderMessage(row, "+15550001111", "damaged copy")
        assertEquals(987L, message.id)
        assertEquals(12L, message.threadId)
        assertTrue(message.isMMS)
        assertEquals(1794000000, message.date)
        assertEquals("damaged copy", message.body)
        assertTrue(message.participants.isNotEmpty())
        assertTrue(message.read)
    }

    @Test
    fun smsEnvelopeCarriesShellFields() {
        // 1.10.18: the envelope carries the message shell, so the
        // thread view can build a Message from the envelope alone.
        val display = LockerManager.deriveDisplay(smsBackup())
        assertEquals(1, display.type)
        assertEquals(-1, display.status)
        assertTrue(display.read)
        assertEquals(1, display.subscriptionId)
    }

    @Test
    fun mmsEnvelopeCarriesShellFields() {
        val display = LockerManager.deriveDisplay(mmsBackup())
        assertEquals(1, display.type)
        assertEquals(0, display.status)
        assertTrue(display.read)
        assertEquals(1, display.subscriptionId)
    }

    @Test
    fun sentMmsEnvelopeShellKeepsMessageBox() {
        val display = LockerManager.deriveDisplay(mmsBackup(messageBox = 2))
        assertEquals(2, display.type)
        assertEquals("", display.sender)
    }

    @Test
    fun unreadSmsEnvelopeShellIsUnread() {
        val display = LockerManager.deriveDisplay(smsBackup().copy(read = 0))
        assertEquals(false, display.read)
    }

    @Test
    fun envelopeJsonWithoutShellFieldsDecodesWithDefaults() {
        // An envelope written before the shell fields existed still
        // decodes: the defaults are the received-message values.
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val display = json.decodeFromString<org.fossify.messages.models.LockedDisplay>(
            """{"sender":"+15551234567","body":"hello","participants":["+15551234567"]}"""
        )
        assertEquals(1, display.type)
        assertEquals(-1, display.status)
        assertTrue(display.read)
        assertEquals(0, display.subscriptionId)
        assertTrue(display.attachments.isEmpty())
    }

    @Test
    fun envelopeJsonRoundTripsWithShellFields() {
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val display = LockerManager.deriveDisplay(mmsBackup())
        val decoded = json.decodeFromString<org.fossify.messages.models.LockedDisplay>(
            json.encodeToString<org.fossify.messages.models.LockedDisplay>(display)
        )
        assertEquals(display, decoded)
    }
}
