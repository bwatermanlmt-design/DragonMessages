package org.fossify.messages.models

import android.content.ContentValues
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.contentValuesOf
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

@Serializable
data class MmsPart(
    @SerializedName("cd")
    val contentDisposition: String?,
    @SerializedName("chset")
    val charset: String?,
    @SerializedName("cid")
    val contentId: String?,
    @SerializedName("cl")
    val contentLocation: String?,
    @SerializedName("ct")
    val contentType: String,
    @SerializedName("ctt_s")
    val ctStart: String?,
    @SerializedName("ctt_t")
    val ctType: String?,
    @SerializedName("fn")
    val filename: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("seq")
    val sequenceOrder: Int,
    @SerializedName("text")
    val text: String?,
    @SerializedName("data")
    val data: String?,
) {

    fun toContentValues(): ContentValues {
        return contentValuesOf(
            Telephony.Mms.Part.CONTENT_DISPOSITION to contentDisposition,
            Telephony.Mms.Part.CHARSET to charset,
            Telephony.Mms.Part.CONTENT_ID to contentId,
            Telephony.Mms.Part.CONTENT_LOCATION to contentLocation,
            Telephony.Mms.Part.CONTENT_TYPE to contentType,
            Telephony.Mms.Part.CT_START to ctStart,
            Telephony.Mms.Part.CT_TYPE to ctType,
            Telephony.Mms.Part.FILENAME to filename,
            Telephony.Mms.Part.NAME to name,
            Telephony.Mms.Part.SEQ to sequenceOrder,
            Telephony.Mms.Part.TEXT to text,
        )
    }

    fun isVCard(): Boolean {
        // Same match as String.isVCardMimeType: text/vcard and
        // text/x-vcard (and any future *-vcard subtype).
        val type = contentType.lowercase()
        return type.endsWith("x-vcard") || type.endsWith("vcard")
    }

    fun isNonText(): Boolean {
        // A vCard's payload is stream bytes, not the TEXT column —
        // it is a media part for display, restore, and export, even
        // though its content type starts with "text/". Before this,
        // a vCard rendered as raw text in the locked view and its
        // bytes were silently dropped on unlock (1.10.11-1.10.15).
        if (isVCard()) {
            return true
        }
        return !(text != null || contentType.lowercase().startsWith("text") || contentType.lowercase() == "application/smil")
    }

    /**
     * The raw bytes of this part's media payload, or null when there
     * are none. Media payloads arrive Base64-encoded in [data] —
     * except vCards, whose content type starts with "text/", so
     * MessagesReader stored them as a UTF-8 string in data, not
     * Base64. Decoding a vCard's raw text as Base64 yields binary
     * garbage: on the 1.10.16 emulator round trip a 71-byte vCard
     * came back from unlock as 40 garbage bytes, because
     * writeMmsPart decoded unconditionally while the display path
     * already knew the difference. Both the locked display and the
     * unlock restore must use this one seam — never Base64.decode
     * part data anywhere else.
     */
    fun mediaBytes(): ByteArray? {
        val raw = data ?: return null
        if (raw.isEmpty()) return null
        return if (isVCard()) {
            raw.toByteArray(Charsets.UTF_8)
        } else {
            try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (e: Exception) {
                null
            }
        }
    }
}
