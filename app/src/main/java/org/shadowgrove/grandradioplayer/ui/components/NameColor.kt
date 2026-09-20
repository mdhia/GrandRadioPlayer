package org.shadowgrove.grandradioplayer.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

/**
 * Deterministically derives a color from a name (folder or track title), so the same
 * station/file always gets the same placeholder color and the same gradient background
 * fallback when no real cover art / Palette result is available.
 */
fun colorForName(name: String): Color {
    val hue = (name.hashCode().absoluteValue % 360).toFloat()
    return Color.hsv(hue = hue, saturation = 0.55f, value = 0.55f)
}

/** A slightly darker companion shade of [colorForName], used as the bottom gradient stop. */
fun darkerColorForName(name: String): Color {
    val hue = (name.hashCode().absoluteValue % 360).toFloat()
    return Color.hsv(hue = hue, saturation = 0.65f, value = 0.30f)
}
