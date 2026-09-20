package org.shadowgrove.grandradioplayer.model

import android.net.Uri

/**
 * A "station" - any direct sub-folder of the user-selected root directory that contains
 * supported audio files - together with all of them and, if present, an explicit folder-level
 * cover image (cover.png / cover.jpg).
 */
data class StationFolder(
    val name: String,
    val uri: Uri,
    val coverArtUri: Uri?,
    /**
     * Pre-processed (square, white-background-padded, see
     * [org.shadowgrove.grandradioplayer.util.CoverArtProcessing]) bytes of [coverArtUri] if one
     * was found, otherwise a generated "initials" placeholder (see
     * [org.shadowgrove.grandradioplayer.util.PlaceholderArtwork]) - captured once while scanning,
     * so this is effectively never null in practice (only on an unexpected encoding failure).
     * Used directly as [androidx.media3.common.MediaMetadata] artwork so the media notification
     * and Android Auto's browse grid always show *something* for every station, never a blank
     * tile, and match the phone UI's own cover/placeholder chain.
     */
    val coverArtData: ByteArray?,
    val audioFiles: List<AudioFile>
) {
    // ByteArray properties break the generated equals()/hashCode() (reference identity), so
    // both are overridden to compare/hash the cover art by its actual content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StationFolder) return false
        return name == other.name &&
            uri == other.uri &&
            coverArtUri == other.coverArtUri &&
            (coverArtData?.contentEquals(other.coverArtData) ?: (other.coverArtData == null)) &&
            audioFiles == other.audioFiles
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + (coverArtUri?.hashCode() ?: 0)
        result = 31 * result + (coverArtData?.contentHashCode() ?: 0)
        result = 31 * result + audioFiles.hashCode()
        return result
    }
}
