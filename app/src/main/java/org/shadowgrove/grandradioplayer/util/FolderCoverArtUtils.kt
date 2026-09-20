package org.shadowgrove.grandradioplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a folder-level cover image (cover.png / cover.jpg, as located by
 * [AudioFileScanner]) via SAF. Used as a resilience fallback in the artwork priority chain when
 * [org.shadowgrove.grandradioplayer.model.StationFolder.coverArtData] (the pre-processed bytes
 * captured while scanning) isn't available for some reason.
 */
object FolderCoverArtUtils {

    suspend fun loadCoverBitmap(context: Context, coverUri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(coverUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { CoverArtProcessing.squareWithWhiteBackground(it) }
        } catch (e: Exception) {
            null
        }
    }
}
