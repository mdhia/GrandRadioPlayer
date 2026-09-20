package org.shadowgrove.grandradioplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.shadowgrove.grandradioplayer.ui.theme.DefaultGradientBottom
import org.shadowgrove.grandradioplayer.ui.theme.DefaultGradientTop

/**
 * App-wide, animated gradient background state.
 *
 * Any screen (currently the carousels) can push the Palette-derived colors of whatever cover is
 * centered right now, and the single root-level [AppGradientBackground] repaints the *entire*
 * window - behind the status bar, top bar and navigation bar alike - instead of just the content
 * area of one screen.
 */
class GradientBackgroundState {
    var topColor by mutableStateOf(DefaultGradientTop)
        private set
    var bottomColor by mutableStateOf(DefaultGradientBottom)
        private set

    fun setColors(top: Color, bottom: Color) {
        topColor = top
        bottomColor = bottom
    }

    /** Falls back to the default black -> dark grey/violet gradient. */
    fun reset() {
        topColor = DefaultGradientTop
        bottomColor = DefaultGradientBottom
    }
}

val LocalGradientBackground = staticCompositionLocalOf { GradientBackgroundState() }

@Composable
fun rememberGradientBackgroundState(): GradientBackgroundState =
    remember { GradientBackgroundState() }

/**
 * Root container that paints the full-screen gradient plus a soft neon glow, and provides
 * [state] down the tree via [LocalGradientBackground]. Everything else in the app should be
 * drawn on transparent surfaces so this stays visible.
 */
@Composable
fun AppGradientBackground(
    state: GradientBackgroundState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val top by animateColorAsState(state.topColor, tween(500), label = "bgTop")
    val bottom by animateColorAsState(state.bottomColor, tween(500), label = "bgBottom")

    Box(
        modifier = modifier
            .fillMaxSize()
            // Base vertical wash: accent color at the top, deepening into near-black.
            .background(
                Brush.verticalGradient(
                    0f to top,
                    0.55f to bottom,
                    1f to Color.Black.copy(alpha = 0.92f).compositeOver(bottom)
                )
            )
            // Soft radial glow behind the centered cover, like the reference artwork.
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        top.copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    center = Offset.Unspecified,
                    radius = 900f
                )
            )
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGradientBackground provides state) {
            content()
        }
    }
}



