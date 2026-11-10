package org.fossify.messages.filtering

import kotlinx.serialization.json.Json
import org.fossify.messages.helpers.LockerManager
import org.fossify.messages.models.BackupType
import org.fossify.messages.models.LockedRecord
import org.fossify.messages.models.MessagesBackup
import org.fossify.messages.models.MmsAddress
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.MmsPart
import org.fossify.messages.models.SmsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * The Locker stores one serialized backup object (SmsBackup / MmsBackup)
 * per message, encrypted. This test proves the sealed-class JSON
 * round-trip — encode as MessagesBackup, decode back to the same object
 * — and the full encrypt-store-decrypt-restore path, on the JVM.
 */
class LockerSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun smsBackup() = SmsBackup(
        subscriptionId = 1L,
        address = "+15551234567",
        body = "meet at the usual place, bring the ledger",
        date = 1794000000000L,
        dateSent = 1793999999000L,
        locked = 0,
        protocol = null,
        read = 1,
        status = -1,
        type = 1,
        serviceCenter = null
    )

    private fun mmsBackup() = MmsBackup(
        creator = "com.messages.dragon",
        contentType = "application/vnd.wap.multipart.related",
        deliveryReport = 0,
        date = 1794000000L,
        dateSent = 1793999999L,
        locked = 0,
        messageType = 132,
        messageBox = 1,
        read = 1,
        readReport = 0,
        seen = 1,
        textOnly = 0,
        status = null,
        subject = null,
        subjectCharSet = null,
        subscriptionId = 1L,
        transactionId = "T123",
        addresses = listOf(MmsAddress("+15551234567", 137, 106)),
        parts = listOf(
            MmsPart(
                contentDisposition = null,
                charset = "106",
                contentId = "<text>",
                contentLocation = "text.txt",
                contentType = "text/plain",
                ctStart = null,
                ctType = null,
                filename = null,
                name = "text",
                sequenceOrder = 0,
                text = null,
                data = "the photo is in the envelope"
            ),
            MmsPart(
                contentDisposition = "attachment",
                charset = null,
                contentId = "<photo>",
                contentLocation = "photo.jpg",
                contentType = "image/jpeg",
                ctStart = null,
                ctType = null,
                filename = "photo.jpg",
                name = "photo.jpg",
                sequenceOrder = 1,
                text = null,
                // A few bytes of fake JPEG, Base64 — the shape the real
                // export path stores for non-text parts.
                data = "/9j/4AAQSkZJRgABAQAAAQABAAD"
            )
        )
    )

    @Test
    fun smsBackupRoundTripsAsMessagesBackup() {
        val original: MessagesBackup = smsBackup()
        val encoded = json.encodeToString<MessagesBackup>(original)
        val decoded = json.decodeFromString<MessagesBackup>(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded is SmsBackup)
        assertEquals(BackupType.SMS, decoded.backupType)
    }

    @Test
    fun mmsBackupRoundTripsAsMessagesBackup() {
        val original: MessagesBackup = mmsBackup()
        val encoded = json.encodeToString<MessagesBackup>(original)
        val decoded = json.decodeFromString<MessagesBackup>(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded is MmsBackup)
        val mms = decoded as MmsBackup
        assertEquals(2, mms.parts.size)
        assertEquals("the photo is in the envelope", mms.parts[0].data)
        assertEquals("/9j/4AAQSkZJRgABAQAAAQABAAD", mms.parts[1].data)
        assertEquals(BackupType.MMS, decoded.backupType)
    }

    @Test
    fun encryptedJsonRoundTrips() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val original: MessagesBackup = mmsBackup()
        val encoded = json.encodeToString<MessagesBackup>(original)
        val blob = LockerCrypto.encryptText(encoded, key)
        val decoded = json.decodeFromString<MessagesBackup>(LockerCrypto.decryptText(blob, key))
        assertEquals(original, decoded)
    }

    @Test
    fun smsWithNullBodyRoundTrips() {
        val original: MessagesBackup = smsBackup().copy(body = null)
        val encoded = json.encodeToString<MessagesBackup>(original)
        assertEquals(original, json.decodeFromString<MessagesBackup>(encoded))
    }

    // The 1.10.17 record: a LockedRecord is the backup (metadata
    // face) plus the display envelope, and old rows holding a bare
    // backup still decode through the same seam.

    @Test
    fun lockedRecordRoundTrips() {
        val backup: MessagesBackup = mmsBackup()
        val record = LockedRecord(backup, LockerManager.deriveDisplay(backup))
        val encoded = json.encodeToString<LockedRecord>(record)
        val decoded = json.decodeFromString<LockedRecord>(encoded)
        assertEquals(record, decoded)
        assertEquals(backup, decoded.backup)
        assertEquals("+15551234567", decoded.display.sender)
        assertEquals("the photo is in the envelope", decoded.display.body)
        assertEquals(listOf("+15551234567"), decoded.display.participants)
        assertEquals(1, decoded.display.attachments.size)
        assertEquals(1, decoded.display.attachments[0].partIndex)
        assertEquals("image/jpeg", decoded.display.attachments[0].mimeType)
        assertEquals("photo.jpg", decoded.display.attachments[0].fileName)
    }

    @Test
    fun oldBareBackupDecodesToRecordWithDerivedEnvelope() {
        val backup: MessagesBackup = smsBackup()
        val oldPlaintext = json.encodeToString<MessagesBackup>(backup)
        val record = LockerManager.decodeRecord(oldPlaintext)
        assertEquals(backup, record.backup)
        assertEquals("+15551234567", record.display.sender)
        assertEquals("meet at the usual place, bring the ledger", record.display.body)
        assertEquals(listOf("+15551234567"), record.display.participants)
        assertTrue(record.display.attachments.isEmpty())
    }

    @Test
    fun newRecordDecodesThroughTheSameSeam() {
        val backup: MessagesBackup = smsBackup()
        val record = LockedRecord(backup, LockerManager.deriveDisplay(backup))
        val encoded = json.encodeToString<LockedRecord>(record)
        assertEquals(record, LockerManager.decodeRecord(encoded))
    }

    @Test
    fun addressLessMmsDerivesFallbackEnvelope() {
        // The empty-view shape: an MMS with no addresses at all.
        // The envelope falls back to the thread's conversation
        // address, so the view never sees an empty participant list.
        val backup: MessagesBackup = mmsBackup().copy(addresses = emptyList())
        val record = LockerManager.decodeRecord(
            json.encodeToString<MessagesBackup>(backup),
            fallbackAddress = "+15550001111"
        )
        assertEquals("+15550001111", record.display.sender)
        assertEquals(listOf("+15550001111"), record.display.participants)
        assertEquals("the photo is in the envelope", record.display.body)
    }
}
