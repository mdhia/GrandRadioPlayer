package org.shadowgrove.grandradioplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.shadowgrove.grandradioplayer.model.AudioFile
import org.shadowgrove.grandradioplayer.model.StationFolder

/**
 * Builds the [MediaItem]s used both for the Media3 browse tree (Android Auto / any
 * [androidx.media3.session.MediaBrowser]) and for the player's own queue.
 *
 * MediaIds are intentionally simple and content-derived (never caller-supplied opaque data):
 * - Root: a fixed constant.
 * - Station folder: `"station:<folder name>"`.
 * - Audio file: the file's own content URI, as a string.
 *
 * This makes every mediaId trivially resolvable back to real, locally-scanned content via
 * [StationLibraryRepository], which is the basis of [RadioPlaybackService]'s restriction that it
 * will only ever play URIs that came from our own scan - never an arbitrary URI supplied by an
 * external caller.
 *
 * Every browsable item also carries the standard "content style" extras so that Android Auto
 * (and any other [androidx.media3.session.MediaBrowser] host, e.g. Android Automotive OS)
 * renders folders/tracks as a grid of large cover tiles instead of a plain text list - the
 * closest equivalent to the app's own cover-flow carousel that the platform's fixed templates
 * allow for.
 */
object MediaItemFactory {

    const val ROOT_MEDIA_ID = "root"

    /** android.media.browse.CONTENT_STYLE_SUPPORTED */
    private const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"

    /** android.media.browse.CONTENT_STYLE_BROWSABLE_HINT */
    private const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"

    /** android.media.browse.CONTENT_STYLE_PLAYABLE_HINT */
    private const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    /** android.media.browse.CONTENT_STYLE_GRID_ITEM_HINT_VALUE */
    private const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2

    /** android.media.browse.CONTENT_STYLE_LIST_ITEM_HINT_VALUE */
    private const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1

    /**
     * Builds the standard "content style" extras that tell Android Auto how to lay a level out.
     * Passing `null` for a hint leaves that item type at the host's default.
     */
    private fun contentStyleExtras(browsableHint: Int?, playableHint: Int?): Bundle = Bundle().apply {
        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        browsableHint?.let { putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, it) }
        playableHint?.let { putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, it) }
    }

    fun stationMediaId(folderName: String): String = "station:$folderName"

    fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_MEDIA_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Grand Radio Player")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                // Station folders are rendered as a plain name list (no cover tiles) - the
                // folder picker is just navigation, so names alone are faster to scan while
                // driving. The tracks *inside* a folder still use the cover grid, see below.
                .setExtras(contentStyleExtras(
                    browsableHint = CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    playableHint = null
                ))
                .build()
        )
        .build()

    /**
     * A station folder as shown in Android Auto's folder picker. Deliberately carries **no**
     * artwork: combined with the root's list content style, the car shows just the folder name,
     * with no icon next to it.
     */
    fun folderItem(folder: StationFolder): MediaItem = MediaItem.Builder()
        .setMediaId(stationMediaId(folder.name))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(folder.name)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                // Renders this folder's tracks (once browsed into) as a grid of cover tiles.
                .setExtras(contentStyleExtras(
                    browsableHint = null,
                    playableHint = CONTENT_STYLE_GRID_ITEM_HINT_VALUE
                ))
                .build()
        )
        .build()

    /**
     * Builds the fully-resolved, playable [MediaItem] for [file]. [setUri] here is always our
     * own repository's content URI - callers can never inject an arbitrary source through this
     * factory, since the only inputs are locally scanned [AudioFile]s.
     *
     * Artwork priority mirrors the in-app carousel: the file's own embedded cover art bytes
     * (already captured and square/white-padded while scanning, so no extra work here) first,
     * then the containing station folder's cover.png/jpg bytes as [fallbackArtworkData]. This is
     * what makes the media notification, lock-screen art and Android Auto's now-playing/browse
     * screens show real, un-cropped covers instead of a generic music-note placeholder.
     */
    fun audioItem(
        file: AudioFile,
        stationName: String,
        fallbackArtworkData: ByteArray? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(file.uri.toString())
        .setUri(file.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(file.title)
                .setArtist(stationName)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                // Android Auto only renders the progress slider on its now-playing screen when
                // the item reports a duration, so it is published explicitly here (the value is
                // already known from the scan - no extra I/O).
                .setDurationMs(file.durationMs)
                .apply {
                    val artwork = file.artworkData ?: fallbackArtworkData
                    if (artwork != null) {
                        setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                }
                .build()
        )
        .build()
}



