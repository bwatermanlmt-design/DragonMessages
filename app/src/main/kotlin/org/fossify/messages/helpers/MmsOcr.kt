package org.fossify.messages.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.fossify.messages.filtering.OcrGate
import java.util.concurrent.TimeUnit

/**
 * On-device image text recognition (revision 9, ML Kit bundled Latin
 * model): the seam between the receiver and ML Kit, kept narrow so the
 * recognizer can be faked in tests and swapped later. The bundled model
 * needs no network and no new permission — the app keeps its
 * no-INTERNET manifest. Every function here fails open (null on any
 * failure): OCR text is a bonus for matching, never a gate on filing.
 */
object MmsOcr {

    /** Recognize text in one bitmap within the remaining budget; null on any failure or timeout. */
    fun recognize(bitmap: Bitmap, budgetMs: Long): String? {
        if (budgetMs <= 0) return null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            val text = Tasks.await(task, budgetMs, TimeUnit.MILLISECONDS)
            text.text.ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /**
     * Decode one MMS part URI to a downscaled, correctly oriented bitmap
     * (longest side [OcrGate.MAX_SIDE_PX]); null on any failure. The
     * notification thumbnail is never reused here — it is center-cropped
     * to icon size, far too small for recognition. The stream is opened
     * twice (bounds, then decode) because MMS part streams do not
     * promise mark/reset support.
     */
    fun decodePart(context: Context, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (longestSide <= 0) return null
            var sampleSize = 1
            while (longestSide / sampleSize > OcrGate.MAX_SIDE_PX) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null
            val degrees = rotationDegrees(context, uri)
            if (degrees == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** EXIF orientation of a part, as clockwise degrees (0 when unknown). */
    private fun rotationDegrees(context: Context, uri: Uri): Int {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: return 0
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
