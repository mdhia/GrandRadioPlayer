package org.shadowgrove.grandradioplayer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.shadowgrove.grandradioplayer.ui.theme.DefaultGradientBottom
import org.shadowgrove.grandradioplayer.ui.theme.DefaultGradientTop
import org.shadowgrove.grandradioplayer.util.PaletteUtils
import kotlin.math.abs

/**
 * An iTunes/CoverFlow style snapping carousel.
 *
 * The pager is always horizontal so the neighbouring covers stay visible to the left and right of
 * the centered one; only the tile *size* adapts to the orientation (a big dominant cover in
 * portrait, smaller ones with more neighbours visible in landscape).
 *
 * Purely from the pager's live scroll offset each page gets a scale, a fade, a 3D `rotationY`
 * tilt and a slight inward `translationX`, so neighbours appear to recede into the background.
 *
 * In parallel the Palette colors of the centered cover (blended into the approaching neighbour's)
 * are pushed into the app-wide [LocalGradientBackground], so the *whole* window is tinted rather
 * than just this composable's bounds.
 */
@Composable
fun <T> Carousel(
    items: List<T>,
    isVertical: Boolean,
    key: (T) -> Any,
    coverBitmapLoader: suspend (T) -> Bitmap?,
    fallbackColors: (T) -> Pair<Color, Color>?,
    onItemConfirmed: (T) -> Unit,
    modifier: Modifier = Modifier,
    minScale: Float = 0.68f,
    minAlpha: Float = 0.35f,
    /** Which page to start centered on, e.g. to restore a previously left position. */
    initialPage: Int = 0,
    /**
     * Fired every time the pager comes to rest on a (possibly new) page, including the very
     * first composition - lets a caller persist "where the user left off" across navigation.
     */
    onSettledPageChanged: ((Int) -> Unit)? = null,
    /**
     * Fired whenever the *centered* item actually changes after the initial composition (i.e.
     * every settle except the first) - whether the user swiped to it or tapped a neighbour to
     * scroll it into place. Does *not* require the explicit "confirm" tap that [onItemConfirmed]
     * does; callers use this for "live radio" style behaviour where merely dialing to a new
     * item while already playing should start it immediately.
     */
    onCenteredItemChanged: ((T) -> Unit)? = null,
    itemContent: @Composable (item: T, isCentered: Boolean) -> Unit
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, items.lastIndex)) { items.size }
    val coroutineScope = rememberCoroutineScope()
    val gradientState = LocalGradientBackground.current

    // Reports every settled page (including the initial one) so a caller can remember it.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            onSettledPageChanged?.invoke(settledPage)
        }
    }

    // Same settle signal, but skips the very first (composition-time) emission so callers only
    // react to an actual user-driven change of the centered item.
    LaunchedEffect(pagerState) {
        var isFirstEmission = true
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            if (isFirstEmission) {
                isFirstEmission = false
                return@collect
            }
            onCenteredItemChanged?.invoke(items[settledPage.coerceIn(items.indices)])
        }
    }

    // Palette-derived (top, bottom) gradient colors per item key, filled in lazily as pages
    // scroll into view rather than for the whole library upfront.
    val paletteCache = remember(items) { mutableStateMapOf<Any, Pair<Color, Color>>() }
    val defaultColors = DefaultGradientTop to DefaultGradientBottom

    // Continuously drive the app-wide gradient from the live scroll position, so the background
    // morphs while swiping instead of only snapping at the end.
    LaunchedEffect(pagerState, items) {
        snapshotFlow {
            Triple(pagerState.currentPage, pagerState.currentPageOffsetFraction, paletteCache.size)
        }.collect { (page, offset, _) ->
            val currentIndex = page.coerceIn(items.indices)
            val neighborIndex = (currentIndex + if (offset >= 0f) 1 else -1).coerceIn(items.indices)
            val blend = abs(offset).coerceIn(0f, 1f)

            val current = paletteCache[key(items[currentIndex])]
                ?: fallbackColors(items[currentIndex]) ?: defaultColors
            val neighbor = paletteCache[key(items[neighborIndex])]
                ?: fallbackColors(items[neighborIndex]) ?: defaultColors

            gradientState.setColors(
                top = lerp(current.first, neighbor.first, blend),
                bottom = lerp(current.second, neighbor.second, blend)
            )
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Tile width: dominant in portrait, narrower in landscape (more covers on screen).
        val tileWidth = if (isVertical) maxWidth * 0.64f else maxWidth * 0.34f
        // Symmetric content padding keeps the current page perfectly centered while letting the
        // previous/next covers peek in on both sides.
        val sidePadding = ((maxWidth - tileWidth) / 2).coerceAtLeast(0.dp)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = sidePadding),
            pageSize = PageSize.Fixed(tileWidth),
            // Wider gap between tiles so neighbours read clearly as separate covers rather than
            // crowding into the centered one.
            pageSpacing = if (isVertical) 28.dp else 24.dp,
            verticalAlignment = Alignment.CenterVertically,
            key = { index -> key(items[index]) }
        ) { page ->
            val item = items[page]
            val itemKey = key(item)
            // derivedStateOf keeps the frequently-changing scroll offset out of the composition
            // scope: only the actual true/false flip recomposes this page.
            val isCentered by remember(page) {
                derivedStateOf {
                    page == pagerState.currentPage &&
                        abs(pagerState.currentPageOffsetFraction) < 0.15f
                }
            }
            val interactionSource = remember { MutableInteractionSource() }

            LaunchedEffect(itemKey) {
                if (paletteCache[itemKey] == null) {
                    val bitmap = coverBitmapLoader(item)
                    if (bitmap != null) {
                        paletteCache[itemKey] = PaletteUtils.extractGradientColors(bitmap)
                    }
                }
            }

            CoverFlowPage(
                pagerState = pagerState,
                page = page,
                minScale = minScale,
                minAlpha = minAlpha,
                modifier = Modifier
                    .width(tileWidth)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (isCentered) {
                            onItemConfirmed(item)
                        } else {
                            coroutineScope.launch { pagerState.animateScrollToPage(page) }
                        }
                    }
            ) {
                itemContent(item, isCentered)
            }
        }
    }
}

@Composable
private fun CoverFlowPage(
    pagerState: PagerState,
    page: Int,
    minScale: Float,
    minAlpha: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            // Ensures the centered tile is always painted *in front of* its neighbours, instead
            // of Compose's default declaration-order stacking (which could otherwise let a
            // translated-forward neighbour visually overlap on top of the current station).
            // Reading only the page index (not the continuously-changing offset fraction) keeps
            // this cheap - it only recomposes when the centered page actually changes.
            .zIndex(if (page == pagerState.currentPage) 1f else 0f)
            .graphicsLayer {
            // Distance from the centered page in fractional pages: 0f = perfectly centered.
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).coerceIn(-1.5f, 1.5f)
            val proximity = (1f - abs(pageOffset) / 1.5f).coerceIn(0f, 1f)

            val scale = lerpFloat(minScale, 1f, proximity)
            scaleX = scale
            scaleY = scale
            alpha = lerpFloat(minAlpha, 1f, proximity)

            // Cover-flow 3D tilt: neighbours rotate away from the viewer...
            cameraDistance = 14f * density
            rotationY = pageOffset * -26f
            // ...and slide slightly towards the center so they visually recede.
            translationX = pageOffset * size.width * 0.10f
            transformOrigin = TransformOrigin(
                pivotFractionX = if (pageOffset > 0f) 1f else 0f,
                pivotFractionY = 0.5f
            )
        },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
