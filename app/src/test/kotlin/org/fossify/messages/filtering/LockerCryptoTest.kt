package org.fossify.messages.filtering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class LockerCryptoTest {

    private fun softwareKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun textRoundTrips() {
        val key = softwareKey()
        val blob = LockerCrypto.encryptText("meet at the usual place, bring the ledger", key)
        assertEquals("meet at the usual place, bring the ledger", LockerCrypto.decryptText(blob, key))
    }

    @Test
    fun bytesRoundTrip() {
        val key = softwareKey()
        val plain = ByteArray(1024) { (it % 251).toByte() }
        val blob = LockerCrypto.encrypt(plain, key)
        assertTrue(plain.contentEquals(LockerCrypto.decrypt(blob, key)))
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val key = softwareKey()
        val blob = LockerCrypto.encryptText("", key)
        assertEquals("", LockerCrypto.decryptText(blob, key))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val key = softwareKey()
        val first = LockerCrypto.encryptText("same message", key)
        val second = LockerCrypto.encryptText("same message", key)
        // Same plaintext, different IVs: the ciphertexts must differ.
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        // And both still decrypt to the same plaintext.
        assertEquals("same message", LockerCrypto.decryptText(first, key))
        assertEquals("same message", LockerCrypto.decryptText(second, key))
    }

    @Test
    fun wrongKeyFails() {
        val blob = LockerCrypto.encryptText("secret", softwareKey())
        assertThrows(AEADBadTagException::class.java) {
            LockerCrypto.decryptText(blob, softwareKey())
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val key = softwareKey()
        val blob = LockerCrypto.encryptText("secret", key)
        val tampered = blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            LockerCrypto.decrypt(LockerCrypto.EncryptedBlob(blob.iv, tampered), key)
        }
    }

    @Test
    fun tamperedIvFails() {
        val key = softwareKey()
        val blob = LockerCrypto.encryptText("secret", key)
        val tamperedIv = blob.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            LockerCrypto.decrypt(LockerCrypto.EncryptedBlob(tamperedIv, blob.ciphertext), key)
        }
    }

    @Test
    fun blobEqualityIsByContent() {
        val key = softwareKey()
        val blob = LockerCrypto.encryptText("x", key)
        val copy = LockerCrypto.EncryptedBlob(blob.iv.copyOf(), blob.ciphertext.copyOf())
        assertEquals(blob, copy)
        assertNotEquals(blob, LockerCrypto.encryptText("x", key))
    }
}
