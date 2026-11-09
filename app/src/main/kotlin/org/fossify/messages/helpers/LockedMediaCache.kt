package org.fossify.messages.helpers

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Decrypted locked-media files (1.10.16): the locked thread view
 * renders images inline, but a locked message has no provider row,
 * so there is no content://mms/part/<id> to hand Glide. The
 * decrypted bytes are written here instead, under the app-private
 * cache, and served through the app's FileProvider.
 *
 * Security shape — the Locker's rules for these files:
 * - One directory per gate session ([LockerGate.sessionId], the
 *   millis of the last gate pass). A new gate pass means a new
 *   directory, so a URI from an earlier session can never resolve
 *   again once its directory is wiped.
 * - Opaque filenames: "<provider message id>-<part index>.<ext>".
 *   The original filename is never used — it can leak content
 *   ("passport-scan.jpg"). The extension comes from the content
 *   type only, so a viewer can tell a JPEG from a video.
 * - Wiped when the Locker re-locks ([clearAll] on the re-lock
 *   resume and when the thread view is destroyed), on unlock, and
 *   any stale session directory is swept at the start of
 *   [LockerManager.getLockedMessages] — a dead process leaves files
 *   behind, and the next locked view load removes them.
 * - The files are app-private cache: never shared storage, never
 *   MediaStore, never backed up. Only the gated thread view writes
 *   them, never any background work.
 */
object LockedMediaCache {

    /** The cache sub-directory holding every session directory. */
    const val DIR_NAME = "locked-media"

    /**
     * The file a decrypted part is written to, inside its session
     * directory. Deterministic per message + part, so a reload
     * overwrites the same file instead of accumulating copies.
     * Pure — unit-tests run it on the JVM.
     */
    fun fileName(messageId: Long, partIndex: Int, contentType: String): String {
        return "$messageId-$partIndex${extensionFor(contentType)}"
    }

    /**
     * The extension for a content type, from a fixed map — not
     * MimeTypeMap, which is an Android class and unusable in JVM
     * unit tests. Unknown types get no extension: the URI's MIME
     * type (carried separately) is what viewers use.
     * Pure — unit-tests run it on the JVM.
     */
    fun extensionFor(contentType: String): String {
        return when (contentType.lowercase()) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/bmp" -> ".bmp"
            "video/mp4" -> ".mp4"
            "video/3gpp" -> ".3gp"
            "audio/mpeg" -> ".mp3"
            "audio/mp4" -> ".m4a"
            "audio/ogg" -> ".ogg"
            "audio/amr" -> ".amr"
            "text/vcard", "text/x-vcard" -> ".vcf"
            else -> ""
        }
    }

    /** The current gate session's directory, or null before any gate pass (fail closed). */
    fun sessionDir(context: Context): File? {
        val sessionId = LockerGate.sessionId()
        if (sessionId == 0L) {
            return null
        }
        return File(File(context.cacheDir, DIR_NAME), sessionId.toString())
    }

    /**
     * Write a decrypted part into the current session directory and
     * return its FileProvider URI, or null when anything fails — the
     * caller falls back to the "[Locked attachment: ...]" label.
     */
    fun writePart(
        context: Context,
        messageId: Long,
        partIndex: Int,
        bytes: ByteArray,
        contentType: String
    ): Uri? {
        val dir = sessionDir(context) ?: return null
        return try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, fileName(messageId, partIndex, contentType))
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Delete every decrypted media file, whatever session it was from. */
    fun clearAll(context: Context) {
        try {
            File(context.cacheDir, DIR_NAME).deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Delete session directories from earlier gate sessions (or a
     * dead process) — everything except the current session's.
     * The current session's files stay put: the thread view is
     * still showing them.
     */
    fun clearStaleSessions(context: Context) {
        try {
            val root = File(context.cacheDir, DIR_NAME)
            val current = LockerGate.sessionId().toString()
            root.listFiles()?.forEach { sessionDir ->
                if (sessionDir.isDirectory && sessionDir.name != current) {
                    sessionDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
