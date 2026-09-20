package org.shadowgrove.grandradioplayer.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.shadowgrove.grandradioplayer.model.AudioFile
import org.shadowgrove.grandradioplayer.model.StationFolder
import org.shadowgrove.grandradioplayer.ui.components.Carousel
import org.shadowgrove.grandradioplayer.ui.components.StationCoverArt
import org.shadowgrove.grandradioplayer.util.EmbeddedArtworkExtractor
import java.util.Locale

/**
 * Level 2 of the navigation: pick a specific file/track within [folder]. Shows each file's
 * embedded cover art (Phase 2), falling back to the folder's own artwork chain, in the same
 * cover-flow carousel used on Level 1.
 */
@Composable
fun FileCarouselScreen(
    folder: StationFolder,
    onFileSelected: (AudioFile) -> Unit,
    nowPlayingMediaId: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        if (folder.audioFiles.isEmpty()) {
            EmptyLibraryHint(
                title = "Nothing in \"${folder.name}\"",
                subtitle = "Add .mp3, .wav, .ogg or .flac files to this folder.",
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        Carousel(
            items = folder.audioFiles,
            isVertical = isPortrait,
            key = { it.uri.toString() },
            coverBitmapLoader = { file ->
                if (file.hasEmbeddedArtwork) {
                    // Prefer the bytes already captured during scanning (no extra file I/O);
                    // only re-read the file directly for the rare oversized-artwork case where
                    // the scanner intentionally didn't keep the bytes around.
                    EmbeddedArtworkExtractor.decode(file.artworkData)
                        ?: EmbeddedArtworkExtractor.extractEmbeddedArt(context, file.uri)
                } else {
                    // Same reasoning as on the folder level: fall back to the folder's cover
                    // only if it's a *real* one, not the generated initials placeholder, so the
                    // app-wide gradient stays neutral instead of picking up an invented color.
                    folder.coverArtData
                        ?.takeIf { folder.coverArtUri != null }
                        ?.let { EmbeddedArtworkExtractor.decode(it) }
                }
            },
            fallbackColors = { null },
            onItemConfirmed = { file -> onFileSelected(file) },
            modifier = Modifier.fillMaxSize(),
            // Swiping (or tapping a neighbour) to a different track while something is already
            // playing tunes in immediately - like turning a real radio dial - instead of
            // requiring a separate confirm tap.
            onCenteredItemChanged = { file -> if (isPlaying) onFileSelected(file) }
        ) { file, isCentered ->
            CarouselTile(
                title = file.title,
                subtitle = formatDuration(file.durationMs),
                isCentered = isCentered,
                isNowPlaying = nowPlayingMediaId == file.uri.toString(),
                isPlaying = isPlaying
            ) { coverModifier ->
                StationCoverArt(
                    folderName = file.title,
                    folderCoverUri = folder.coverArtUri,
                    embeddedArtworkFileUri = file.uri,
                    hasEmbeddedArtwork = file.hasEmbeddedArtwork,
                    embeddedArtworkData = file.artworkData,
                    folderCoverData = folder.coverArtData,
                    modifier = coverModifier
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}


