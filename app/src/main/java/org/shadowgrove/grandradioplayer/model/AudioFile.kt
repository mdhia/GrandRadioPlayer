package org.shadowgrove.grandradioplayer.model

import android.net.Uri

/**
 * A single playable audio file discovered inside a station folder.
 *
 * [randomStartOffsetMs] is generated once when the file is first scanned (seeded from the
 * file's URI so it stays stable across re-scans within the same install) and is the
 * "simulated live" starting point used by [org.shadowgrove.grandradioplayer.engine.RadioSimulationEngine].
 */
data class AudioFile(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val durationMs: Long,
    val randomStartOffsetMs: Long,
    val hasEmbeddedArtwork: Boolean,
    /**
     * Raw bytes of the embedded cover art (ID3 APIC / Vorbis picture / etc.), captured once
     * during the scan's single [android.media.MediaMetadataRetriever] pass. Used directly as
     * [androidx.media3.common.MediaMetadata] artwork data so the media notification, the
     * lock-screen "now playing" card and Android Auto's browse grid/now-playing screen all show
     * real cover art without needing a separately resolvable content URI (embedded art has none).
     * Capped to a small size while scanning to avoid bloating memory for large libraries.
     */
    val artworkData: ByteArray?
) {
    // ByteArray properties break the generated equals()/hashCode() (they'd use reference
    // identity), so both are overridden here to compare/hash the artwork by its actual content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFile) return false
        return uri == other.uri &&
            fileName == other.fileName &&
            title == other.title &&
            durationMs == other.durationMs &&
            randomStartOffsetMs == other.randomStartOffsetMs &&
            hasEmbeddedArtwork == other.hasEmbeddedArtwork &&
            (artworkData?.contentEquals(other.artworkData) ?: (other.artworkData == null))
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + randomStartOffsetMs.hashCode()
        result = 31 * result + hasEmbeddedArtwork.hashCode()
        result = 31 * result + (artworkData?.contentHashCode() ?: 0)
        return result
    }
}
