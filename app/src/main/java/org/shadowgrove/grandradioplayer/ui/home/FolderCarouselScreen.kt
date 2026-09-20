package org.shadowgrove.grandradioplayer.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shadowgrove.grandradioplayer.model.StationFolder
import org.shadowgrove.grandradioplayer.ui.components.Carousel
import org.shadowgrove.grandradioplayer.ui.components.StationCoverArt
import org.shadowgrove.grandradioplayer.ui.theme.NeonViolet
import org.shadowgrove.grandradioplayer.ui.theme.TextPrimary
import org.shadowgrove.grandradioplayer.ui.theme.TextSecondary
import org.shadowgrove.grandradioplayer.util.EmbeddedArtworkExtractor

/**
 * Level 1 of the navigation: pick a station folder. Shows the Phase 2 folder cover art
 * (cover.png/jpg, falling back to generated initials) for every folder in a centered
 * cover-flow carousel whose neighbours stay visible left and right.
 */
@Composable
fun FolderCarouselScreen(
    folders: List<StationFolder>,
    onFolderSelected: (StationFolder) -> Unit,
    nowPlayingMediaId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Restores the carousel to wherever the user left it before navigating into a folder. */
    initialPage: Int = 0,
    /** Reports the currently centered page index so the caller can persist it. */
    onPageChanged: (Int) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    Box(modifier = modifier.fillMaxSize()) {
        if (folders.isEmpty()) {
            EmptyLibraryHint(
                title = "No stations yet",
                subtitle = "Drop your audio files into the created station folders, " +
                    "then rescan from the settings.",
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        Carousel(
            items = folders,
            isVertical = isPortrait,
            key = { it.name },
            coverBitmapLoader = { folder ->
                // Only *real* cover art drives the app-wide gradient. coverArtData is also
                // populated with a generated initials placeholder for folders without a
                // cover.png/jpg (so Android Auto never shows a blank tile), but letting Palette
                // read that back would tint the whole screen with an invented color - hence the
                // coverArtUri check, which is what actually tells the two apart.
                folder.coverArtData
                    ?.takeIf { folder.coverArtUri != null }
                    ?.let { EmbeddedArtworkExtractor.decode(it) }
            },
            // No real cover art -> keep the app's default black/dark-grey gradient instead of
            // inventing a random color for the whole screen.
            fallbackColors = { null },
            onItemConfirmed = { folder -> onFolderSelected(folder) },
            modifier = Modifier.fillMaxSize(),
            initialPage = initialPage.coerceIn(0, folders.lastIndex),
            onSettledPageChanged = onPageChanged
            // Deliberately no onCenteredItemChanged here: auto-play-on-swipe only applies
            // *within* a folder (between its stations), never to the folder selector itself -
            // browsing folders is just browsing until the user explicitly confirms one.
        ) { folder, isCentered ->
            val folderIsPlaying = nowPlayingMediaId != null &&
                folder.audioFiles.any { it.uri.toString() == nowPlayingMediaId }
            CarouselTile(
                title = folder.name,
                subtitle = "${folder.audioFiles.size} stations",
                isCentered = isCentered,
                isNowPlaying = folderIsPlaying,
                isPlaying = isPlaying
            ) { coverModifier ->
                StationCoverArt(
                    folderName = folder.name,
                    folderCoverUri = folder.coverArtUri,
                    folderCoverData = folder.coverArtData,
                    modifier = coverModifier
                )
            }
        }
    }
}

/**
 * Shared tile chrome for both carousel levels: a large rounded cover with a neon glow when it is
 * the centered item, the title below it and an optional "now playing" badge.
 */
@Composable
fun CarouselTile(
    title: String,
    subtitle: String,
    isCentered: Boolean,
    isNowPlaying: Boolean,
    isPlaying: Boolean,
    cover: @Composable (Modifier) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            cover(
                Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (isCentered) 28.dp else 10.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = NeonViolet,
                        spotColor = NeonViolet
                    )
                    .clip(RoundedCornerShape(24.dp))
            )
            if (isNowPlaying) {
                NowPlayingBadge(
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isCentered) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Neon underline that only lights up for the centered tile.
        Box(
            modifier = Modifier
                .width(if (isCentered) 48.dp else 16.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isCentered) NeonViolet else Color.White.copy(alpha = 0.15f))
        )
    }
}

/** Small pill badge shown on whichever tile currently corresponds to the playing file. */
@Composable
fun NowPlayingBadge(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = null,
            tint = NeonViolet,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = if (isPlaying) "LIVE" else "PAUSED",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun EmptyLibraryHint(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
