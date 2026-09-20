package org.shadowgrove.grandradioplayer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.absoluteValue

/**
 * Generates the same "colored square with initials" placeholder the phone UI shows
 * ([org.shadowgrove.grandradioplayer.ui.components.FolderInitialsImage]) as a plain raster
 * [Bitmap], for use wherever real cover art isn't available but a Compose-free byte array is
 * needed - namely the [androidx.media3.common.MediaMetadata] artwork baked in by
 * [AudioFileScanner].
 *
 * Without this, a station folder with no `cover.png`/`cover.jpg` would have no artwork at all,
 * which Android Auto renders as a blank white tile sitting above the folder's name in its
 * browse grid. Baking a real placeholder bitmap in at scan time - the same way real cover art is
 * baked in - means every station always has *something* to show, on the phone and in the car
 * alike.
 *
 * The color derivation intentionally mirrors
 * [org.shadowgrove.grandradioplayer.ui.components.colorForName] (same hash -> hue formula) so
 * the car's placeholder and the phone's Compose placeholder for the same station look the same,
 * even though this is a separate, non-Compose implementation.
 */
object PlaceholderArtwork {

    private const val SATURATION = 0.55f
    private const val VALUE = 0.55f

    fun generate(name: String, sizePx: Int = 512): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val hue = (name.hashCode().absoluteValue % 360).toFloat()
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, SATURATION, VALUE)))

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.4f
        }
        val initials = initialsFor(name)
        val textY = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, sizePx / 2f, textY, textPaint)

        return bitmap
    }

    private fun initialsFor(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].first().toString() + words[1].first()).uppercase()
        }
    }
}

