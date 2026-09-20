package org.shadowgrove.grandradioplayer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * Normalizes arbitrary cover art (folder cover.png/jpg, embedded ID3/Vorbis pictures, ...) into a
 * consistent square presentation: if the source isn't already square, it's centered - unscaled,
 * never cropped or zoomed - on a white square canvas sized to the image's longer side.
 *
 * This is applied once, right when artwork is first read (scanning), so the exact same
 * pre-processed bytes are reused everywhere the cover shows up: the phone's Compose carousel,
 * the media notification, and Android Auto's browse grid / now-playing screen (which renders
 * whatever [androidx.media3.common.MediaMetadata] artwork bytes it's given, with no way for us
 * to inject custom Compose layout there).
 */
object CoverArtProcessing {

    /** Extra breathing room added around a non-square image once it's padded to a square. */
    private const val NON_SQUARE_PADDING_PX = 5

    /**
     * Returns [source] unchanged if it's already square (the common case - most cover art is).
     * Otherwise returns a new square [Bitmap] with [source] centered on a white background,
     * with [NON_SQUARE_PADDING_PX] of extra white space left on every side.
     */
    fun squareWithWhiteBackground(source: Bitmap): Bitmap {
        if (source.width == source.height) return source

        val side = maxOf(source.width, source.height) + NON_SQUARE_PADDING_PX * 2
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val left = (side - source.width) / 2f
        val top = (side - source.height) / 2f
        canvas.drawBitmap(source, left, top, null)
        return output
    }

    /** Encodes [bitmap] as JPEG bytes - small and more than sufficient for opaque cover art. */
    fun encodeToJpegBytes(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

