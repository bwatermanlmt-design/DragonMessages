package org.fossify.messages.helpers

import android.app.AlertDialog
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.toast
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Locker's gate (revision 11, Ben's ruling Nov 7 2026): the user
 * chooses Android device unlock (biometric or PIN/pattern/password) or
 * a chosen app-only code. Changing the method or the code first
 * requires authenticating with the current method — so a hand on the
 * unlocked phone cannot quietly switch the gate to a code it knows.
 *
 * The code itself is never stored: only a salted SHA-256 hash. The
 * gate is the access control in front of the Locker UI; the encryption
 * key (LockerKey) carries no user-auth requirement, so background work
 * — isolating an incoming locker message — is never blocked waiting
 * for a fingerprint.
 */
object LockerGate {

    const val METHOD_DEVICE = "device"
    const val METHOD_CODE = "code"

    /** A code shorter than this is not worth setting. */
    const val MIN_CODE_LENGTH = 4

    fun method(config: Config): String {
        return config.lockerGateMethod
    }

    fun hasCode(config: Config): Boolean {
        return config.lockerCodeHash.isNotEmpty() && config.lockerCodeSalt.isNotEmpty()
    }

    /** Store a new code (fresh random salt, salted SHA-256). */
    fun setCode(config: Config, code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        config.lockerCodeSalt = salt.toHex()
        config.lockerCodeHash = hashCode(code, salt)
    }

    fun verifyCode(config: Config, code: String): Boolean {
        if (!hasCode(config)) {
            return false
        }
        val salt = config.lockerCodeSalt.fromHex() ?: return false
        return hashCode(code, salt) == config.lockerCodeHash
    }

    /** Salted SHA-256 of a code, as lowercase hex. Pure — unit-tests on the JVM. */
    fun hashCode(code: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(code.toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) {
            return null
        }
        return try {
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Run the current gate. On success [onUnlocked] runs; on failure or
     * cancellation nothing happens (a cancelled gate just returns to
     * where the user was). When the method is "code" but no code is set
     * yet, the device gate stands in — a code method with no code must
     * never open the Locker on its own.
     */
    fun authenticate(activity: BaseSimpleActivity, onUnlocked: () -> Unit) {
        val config = activity.config
        if (config.lockerGateMethod == METHOD_CODE && hasCode(config)) {
            showCodeEntryDialog(activity) { code ->
                if (verifyCode(config, code)) {
                    onUnlocked()
                } else {
                    activity.toast(R.string.locker_wrong_code)
                    authenticate(activity, onUnlocked)
                }
            }
        } else {
            authenticateWithDevice(activity, onUnlocked)
        }
    }

    /** The device gate: biometric, or the device credential as fallback. */
    fun authenticateWithDevice(activity: BaseSimpleActivity, onUnlocked: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Cancelling just returns to where the user was; real
                    // errors are worth a word.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        activity.toast(errString.toString())
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.unlock_locker))
            .setSubtitle(activity.getString(R.string.locker_subtitle))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    // Combining biometric and device credential in one call is
                    // an API 30+ ability; older versions take the legacy flag.
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                    setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                }
            }
            .build()

        prompt.authenticate(promptInfo)
    }

    /** Ask for the current locker code. [onCode] runs with what was typed. */
    fun showCodeEntryDialog(activity: BaseSimpleActivity, onCode: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = activity.getString(R.string.locker_code_hint)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.unlock_locker)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onCode(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    /** Set a new locker code (typed twice). [onSet] runs only on a valid match. */
    fun showSetCodeDialog(activity: BaseSimpleActivity, onSet: (String) -> Unit) {
        val first = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = activity.getString(R.string.locker_new_code_hint)
        }
        val second = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = activity.getString(R.string.locker_confirm_code_hint)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(first)
            addView(second)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.locker_set_code)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = first.text.toString()
                when {
                    code.length < MIN_CODE_LENGTH -> activity.toast(
                        activity.getString(R.string.locker_code_too_short, MIN_CODE_LENGTH)
                    )

                    code != second.text.toString() -> activity.toast(R.string.locker_code_mismatch)
                    else -> onSet(code)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        first.requestFocus()
    }
}
