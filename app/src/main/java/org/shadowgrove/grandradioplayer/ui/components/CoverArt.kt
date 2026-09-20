package org.shadowgrove.grandradioplayer.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.shadowgrove.grandradioplayer.ui.theme.NightBase
import org.shadowgrove.grandradioplayer.ui.theme.NightSurfaceHigh
import org.shadowgrove.grandradioplayer.util.EmbeddedArtworkExtractor
import org.shadowgrove.grandradioplayer.util.FolderCoverArtUtils

/**
 * Generates a square placeholder image showing a station/folder's initials on a color that is
 * deterministically derived from its name, used whenever no real cover art is available.
 */
@Composable
fun FolderInitialsImage(
    folderName: String,
    modifier: Modifier = Modifier
) {
    val initials = remember(folderName) { computeInitials(folderName) }
    val accent = remember(folderName) { colorForName(folderName) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.85f),
                        NightSurfaceHigh,
                        NightBase
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun computeInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].first().toString() + words[1].first()).uppercase()
    }
}

/**
 * Cover art priority chain for a station folder:
 * 1. Explicit embedded artwork of the given audio file ([embeddedArtworkData], falling back to
 *    re-reading [embeddedArtworkFileUri] if the bytes weren't captured during scanning).
 * 2. The folder's own cover.png / cover.jpg ([folderCoverData], falling back to [folderCoverUri]).
 * 3. Generated initials placeholder based on [folderName].
 *
 * Every real bitmap is already (or defensively re-)normalized to a square, white-padded image by
 * [org.shadowgrove.grandradioplayer.util.CoverArtProcessing] before it gets here, so non-square
 * source images are centered rather than cropped/zoomed - the white [Box] background behind the
 * [Image] covers any transparency at the padding's edges.
 */
@Composable
fun StationCoverArt(
    folderName: String,
    folderCoverUri: Uri?,
    modifier: Modifier = Modifier,
    embeddedArtworkFileUri: Uri? = null,
    hasEmbeddedArtwork: Boolean = false,
    embeddedArtworkData: ByteArray? = null,
    folderCoverData: ByteArray? = null
) {
    val context = LocalContext.current

    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = embeddedArtworkFileUri,
        key2 = folderCoverUri,
        key3 = hasEmbeddedArtwork
    ) {
        value = when {
            hasEmbeddedArtwork && embeddedArtworkData != null ->
                // Bytes already captured during the library scan - no extra file I/O needed.
                EmbeddedArtworkExtractor.decode(embeddedArtworkData)
            hasEmbeddedArtwork && embeddedArtworkFileUri != null ->
                EmbeddedArtworkExtractor.extractEmbeddedArt(context, embeddedArtworkFileUri)
            folderCoverData != null ->
                EmbeddedArtworkExtractor.decode(folderCoverData)
            folderCoverUri != null ->
                FolderCoverArtUtils.loadCoverBitmap(context, folderCoverUri)
            else -> null
        }
    }

    val resolvedBitmap = bitmap
    if (resolvedBitmap != null) {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                // Backdrop for any transparent padding around a non-square (or alpha-channel)
                // source image - this is the "white background" the cover sits on.
                .background(Color.White)
        ) {
            Image(
                bitmap = resolvedBitmap.asImageBitmap(),
                contentDescription = folderName,
                modifier = Modifier.fillMaxSize(),
                // Fit (never Crop): the bitmap is already square, so this never actually scales
                // a mismatched aspect ratio - it just guarantees the full image is always shown,
                // never cropped/zoomed in, even if some edge case slips through un-squared.
                contentScale = ContentScale.Fit
            )
        }
    } else {
        FolderInitialsImage(folderName = folderName, modifier = modifier)
    }
}

