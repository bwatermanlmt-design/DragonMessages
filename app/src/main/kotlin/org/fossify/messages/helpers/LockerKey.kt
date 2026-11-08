package org.fossify.messages.helpers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Locker's encryption key: one AES key, generated once and held by
 * the Android Keystore, never leaving it in extractable form. The gate
 * (device unlock or the locker code) is the access control in front of
 * the Locker UI; the key itself carries no user-auth requirement, so
 * background work — isolating an incoming message in a locked thread —
 * is never blocked waiting for a fingerprint.
 *
 * If the key is ever lost (keystore reset, app data cleared), locked
 * messages become unreadable: that is the point of the encryption, and
 * why the export opt-in exists. LockerCrypto (the cipher itself) is
 * pure JCA and unit-tests on the JVM; this object is the thin
 * Android-only key holder.
 */
object LockerKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dragon_locker_key"
    private const val KEY_SIZE_BITS = 256

    /** The Locker key, creating it on first use. */
    fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
