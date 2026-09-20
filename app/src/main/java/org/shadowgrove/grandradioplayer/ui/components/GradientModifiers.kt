package org.shadowgrove.grandradioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Paints a smooth top-to-bottom gradient background. Pair this with two
 * `animateColorAsState`-driven [Color]s to get an animated, continuously-updating background
 * (see [org.shadowgrove.grandradioplayer.ui.components.Carousel]).
 */
fun Modifier.verticalGradientBackground(topColor: Color, bottomColor: Color): Modifier =
    this.background(Brush.verticalGradient(colors = listOf(topColor, bottomColor)))
