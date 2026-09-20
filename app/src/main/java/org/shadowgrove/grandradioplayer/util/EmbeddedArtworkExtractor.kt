package org.shadowgrove.grandradioplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts embedded cover art (ID3 APIC frame / Vorbis comment picture / etc.) from an audio
 * file's own metadata using [MediaMetadataRetriever]. Decoding is done lazily and on-demand
 * (e.g. when a station's UI actually needs to show artwork) rather than for every scanned file.
 */
object EmbeddedArtworkExtractor {

    /**
     * Decodes already-extracted artwork bytes (e.g. [org.shadowgrove.grandradioplayer.model.AudioFile.artworkData]
     * captured once during the library scan, already square/white-padded). Preferred over
     * [extractEmbeddedArt] whenever the bytes are already available, since it avoids re-opening
     * the file via [MediaMetadataRetriever].
     */
    suspend fun decode(artworkData: ByteArray?): Bitmap? = withContext(Dispatchers.Default) {
        artworkData
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?.let { CoverArtProcessing.squareWithWhiteBackground(it) }
    }

    /**
     * Returns the decoded embedded artwork bitmap for [uri], or null if the file has none or
     * it could not be decoded. Falls back to re-reading the file via [MediaMetadataRetriever];
     * prefer [decode] when the bytes were already captured during scanning.
     */
    suspend fun extractEmbeddedArt(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val artworkBytes = retriever.embeddedPicture ?: return@withContext null
            BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size)
                ?.let { CoverArtProcessing.squareWithWhiteBackground(it) }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
