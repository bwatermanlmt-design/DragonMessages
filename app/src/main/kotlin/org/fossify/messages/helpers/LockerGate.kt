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

    /** The auto-lock default: re-lock 30s after leaving messages. */
    const val DEFAULT_AUTO_LOCK_SECONDS = 30

    /** The auto-lock choices the setting offers, in seconds. */
    val AUTO_LOCK_OPTIONS_SECONDS = intArrayOf(15, 30, 60, 300)

    /**
     * The gate session (revision 13, Ben's rule Nov 8 2026): the Locker
     * stays open while the user is in the messages UI, and re-locks
     * once they have been away for the auto-lock timeout. Process
     * memory only — a dead process means locked, which fails closed.
     * [lastUnlockedAtMillis] is 0 until the first successful gate pass
     * in this process; [lastLeftMessagesAtMillis] is 0 while the user
     * is looking at messages.
     */
    @Volatile
    private var lastUnlockedAtMillis: Long = 0L

    @Volatile
    private var lastLeftMessagesAtMillis: Long = 0L

    /**
     * A gate prompt is already on screen. Two entry points can ask at
     * once (MainActivity's load and its resume, back to back), and a
     * second prompt over the first would double-authenticate — so a
     * second authenticate() while one is up is a no-op.
     *
     * The flag must never stick: every way a prompt can go away
     * (OK, Cancel button, Back, tap-outside, activity destroyed)
     * resets it. A stuck flag silently turns the gate off for the
     * life of the process — that exact bug shipped in the first
     * 1.10.15 cut, when only the button callbacks reset it. The
     * [authGeneration] below guards the dismiss callback: a
     * wrong-code re-prompt starts a new prompt before the old
     * dialog's dismiss fires, and the stale dismiss must not clear
     * the new prompt's flag.
     */
    @Volatile
    private var authenticating = false

    @Volatile
    private var authGeneration = 0

    /** A gate pass just succeeded: the Locker is open from now. */
    fun markUnlocked(nowMillis: Long = System.currentTimeMillis()) {
        lastUnlockedAtMillis = nowMillis
        lastLeftMessagesAtMillis = 0L
    }

    /** Tests only: forget the session, as if the process just started. */
    fun resetSession() {
        lastUnlockedAtMillis = 0L
        lastLeftMessagesAtMillis = 0L
        authenticating = false
        authGeneration = 0
    }

    /**
     * The user left the messages UI (an activity paused). The away
     * clock starts on the FIRST departure and is NOT restarted by
     * later pauses: when the Locker activity finishes because the
     * user cancelled the gate, re-stamping here would measure the
     * timeout from the dismiss and grant a fresh window of free
     * access — the second hole found in 1.10.15 verification, where
     * a Back-dismissed gate let the Locker open unauthenticated for
     * another 15 seconds. Only coming back (markBackInMessages)
     * clears the stamp, so the next departure starts a new clock.
     */
    fun markLeftMessages(nowMillis: Long = System.currentTimeMillis()) {
        if (lastUnlockedAtMillis != 0L && lastLeftMessagesAtMillis == 0L) {
            lastLeftMessagesAtMillis = nowMillis
        }
    }

    /** The user is back in the messages UI. */
    fun markBackInMessages() {
        lastLeftMessagesAtMillis = 0L
    }

    /**
     * Does opening the Locker need the gate now? Yes when the gate
     * never passed in this process, or when the user has been away
     * from messages longer than [timeoutSeconds]. Pure in its inputs
     * (apart from the session it reads), so unit-tests run it on the JVM.
     */
    fun needsAuth(nowMillis: Long = System.currentTimeMillis(), timeoutSeconds: Int = DEFAULT_AUTO_LOCK_SECONDS): Boolean {
        if (lastUnlockedAtMillis == 0L) {
            return true
        }
        val leftAt = lastLeftMessagesAtMillis
        return leftAt != 0L && nowMillis - leftAt > timeoutSeconds * 1000L
    }

    /**
     * Run the gate only when [needsAuth] says so; otherwise run
     * [onUnlocked] straight away. [onCancelled] runs when the user
     * backs out of the gate (or it errors) — the caller closes the
     * Locker UI then, so a cancelled gate never leaves locked content
     * on screen.
     */
    fun authenticateIfNeeded(
        activity: BaseSimpleActivity,
        timeoutSeconds: Int,
        onUnlocked: () -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (needsAuth(timeoutSeconds = timeoutSeconds)) {
            authenticate(activity, onUnlocked, onCancelled)
        } else {
            onUnlocked()
        }
    }

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
    fun authenticate(activity: BaseSimpleActivity, onUnlocked: () -> Unit, onCancelled: () -> Unit = {}) {
        if (authenticating) {
            return
        }
        val config = activity.config
        if (config.lockerGateMethod == METHOD_CODE && hasCode(config)) {
            authenticating = true
            val generation = ++authGeneration
            // Dismissing the dialog any other way (Back, tap-outside,
            // activity destroyed) must also clear the flag — otherwise
            // the gate silently never prompts again. The generation
            // guard keeps a stale dismiss from clearing a re-prompt
            // started by a wrong code.
            val clearIfCurrent = {
                if (authGeneration == generation) {
                    authenticating = false
                }
            }
            showCodeEntryDialog(
                activity,
                onCancelled = {
                    authenticating = false
                    onCancelled()
                },
                onDismissed = clearIfCurrent
            ) { code ->
                authenticating = false
                if (verifyCode(config, code)) {
                    markUnlocked()
                    onUnlocked()
                } else {
                    activity.toast(R.string.locker_wrong_code)
                    authenticate(activity, onUnlocked, onCancelled)
                }
            }
        } else {
            authenticateWithDevice(activity, onUnlocked, onCancelled)
        }
    }

    /** The device gate: biometric, or the device credential as fallback. */
    fun authenticateWithDevice(activity: BaseSimpleActivity, onUnlocked: () -> Unit, onCancelled: () -> Unit = {}) {
        if (authenticating) {
            return
        }
        authenticating = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    authenticating = false
                    markUnlocked()
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticating = false
                    // Cancelling just returns to where the user was; real
                    // errors are worth a word.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        activity.toast(errString.toString())
                    }
                    // Either way the gate did not pass, so the caller
                    // closes the Locker UI instead of leaving it open.
                    onCancelled()
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

        // If starting the prompt itself throws, the flag must not
        // stick — every other exit already clears it.
        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            authenticating = false
            onCancelled()
        }
    }

    /**
     * Ask for the current locker code. [onCode] runs with what was typed.
     * [onCancelled] runs on the Cancel button and on Back / tap-outside
     * (the cancel listener); [onDismissed] runs on every dismiss, which
     * also covers the dialog going away with its activity.
     */
    fun showCodeEntryDialog(
        activity: BaseSimpleActivity,
        onCancelled: () -> Unit = {},
        onDismissed: () -> Unit = {},
        onCode: (String) -> Unit
    ) {
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
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onCancelled()
            }
            .setOnCancelListener { onCancelled() }
            .setOnDismissListener { onDismissed() }
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
