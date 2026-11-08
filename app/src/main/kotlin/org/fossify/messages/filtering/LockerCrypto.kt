package org.fossify.messages.filtering

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Locker's encryption: AES/GCM, one random 12-byte IV per message,
 * 128-bit tag. Pure JCA — no Android — so it unit-tests on the JVM with
 * any [SecretKey]; on the device the key comes from the Android Keystore
 * (see LockerKey). The gate (device unlock or locker code) is the access
 * control; the key itself carries no user-auth requirement, so background
 * work (isolating an incoming locker message) is not blocked by it.
 *
 * The encrypted payload is one serialized backup object (SmsBackup or
 * MmsBackup, the same shape as export/import) — so unlocking is just
 * decrypt, deserialize, and write back through MessagesWriter.
 */
object LockerCrypto {

    /** GCM IV length in bytes: 96 bits, the recommended size. */
    const val IV_LENGTH = 12

    /** GCM authentication tag length in bits. */
    const val TAG_LENGTH_BITS = 128

    const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** One encrypted message: the IV and the ciphertext, both raw bytes. */
    data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedBlob) return false
            return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            return 31 * iv.contentHashCode() + ciphertext.contentHashCode()
        }
    }

    /** Encrypt [plaintext] under [key] with a fresh random IV. */
    fun encrypt(plaintext: ByteArray, key: SecretKey): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No caller IV: the provider generates one (12 bytes for GCM).
        // A Keystore-backed key refuses a caller-provided IV
        // (InvalidAlgorithmParameterException), so the IV must come from
        // the provider — and the same code stays valid for a software key
        // (the JVM tests use one). The generated IV is read back off the
        // cipher and stored with the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
            ?: throw IllegalStateException("LockerCrypto: no IV generated")
        return EncryptedBlob(iv, cipher.doFinal(plaintext))
    }

    /** Decrypt [blob] under [key]. Throws on a wrong key or tampered data. */
    fun decrypt(blob: EncryptedBlob, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    fun encryptText(text: String, key: SecretKey): EncryptedBlob {
        return encrypt(text.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptText(blob: EncryptedBlob, key: SecretKey): String {
        return decrypt(blob, key).toString(Charsets.UTF_8)
    }
}
