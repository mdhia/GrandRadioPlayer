package org.shadowgrove.grandradioplayer.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a top/bottom gradient color pair from a cover art [Bitmap] using the Android
 * [Palette] API, for use as an animated, per-tile dynamic background.
 */
object PaletteUtils {

    /**
     * Returns (topColor, bottomColor) derived from [bitmap]'s dominant swatches.
     * Prefers vibrant tones for the top and darker/muted tones for the bottom, falling back
     * gracefully through Palette's other swatches, and finally to a darkened version of
     * whatever top color was found if no dark swatch exists at all.
     */
    suspend fun extractGradientColors(bitmap: Bitmap): Pair<Color, Color> = withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).generate()

        val topArgb = palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb

        val bottomArgb = palette.darkVibrantSwatch?.rgb
            ?: palette.darkMutedSwatch?.rgb

        val topColor = topArgb?.let(::Color) ?: Color(0xFF37474F)
        val bottomColor = bottomArgb?.let(::Color) ?: darken(topColor)

        topColor to bottomColor
    }

    private fun darken(color: Color, factor: Float = 0.45f): Color = Color(
        red = color.red * factor,
        green = color.green * factor,
        blue = color.blue * factor,
        alpha = color.alpha
    )
}
