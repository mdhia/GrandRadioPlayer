package org.shadowgrove.grandradioplayer.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.shadowgrove.grandradioplayer.model.AudioFile
import org.shadowgrove.grandradioplayer.model.StationFolder
import kotlin.random.Random

/**
 * Scans whatever sub-folders already exist directly under the user-selected root directory for
 * supported audio files and builds the [StationFolder] / [AudioFile] model tree used by the rest
 * of the app. Nothing is created or renamed on disk here - add or remove a folder outside the
 * app and the next scan simply picks up the difference.
 *
 * Only lightweight metadata (duration, title, whether embedded artwork exists) plus the
 * already-square-and-white-padded cover art bytes (see [CoverArtProcessing]) are read here.
 */
object AudioFileScanner {

    private val SUPPORTED_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac")
    private val COVER_FILE_NAMES = listOf("cover.png", "cover.jpg", "cover.jpeg")

    /**
     * Cover art bigger than this (after square/white-padding + JPEG re-encoding) is dropped to
     * avoid bloating memory for large libraries; playback UI/notifications simply fall back to
     * generated initials for those few oversized files.
     */
    private const val MAX_ARTWORK_BYTES = 400 * 1024

    /**
     * Scans every direct sub-folder of [rootUri] that contains at least a readable directory
     * listing. Folders without any supported audio files are still returned (with an empty
     * [StationFolder.audioFiles] list) - callers decide whether to hide those.
     */
    suspend fun scanRootDirectory(context: Context, rootUri: android.net.Uri): List<StationFolder> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext emptyList()

            root.listFiles()
                .filter { it.isDirectory }
                .sortedBy { (it.name ?: "").lowercase() }
                .map { folderDocument -> scanStationFolder(context, folderDocument) }
        }

    /**
     * Scans a single station folder for its cover image and supported audio files.
     */
    suspend fun scanStationFolder(context: Context, folderDocument: DocumentFile): StationFolder =
        withContext(Dispatchers.IO) {
            val children = folderDocument.listFiles()

            val coverArtUri = COVER_FILE_NAMES.firstNotNullOfOrNull { coverName ->
                children.firstOrNull { child ->
                    child.isFile && child.name?.equals(coverName, ignoreCase = true) == true
                }?.uri
            }
            val folderName = folderDocument.name ?: "Unknown"
            // Always end up with *some* artwork bytes - a real cover.png/jpg if there is one,
            // otherwise a generated "initials" placeholder. Without this fallback, a folder with
            // no cover file would have no artwork at all, which Android Auto renders as a blank
            // white tile above the folder's name in its browse grid instead of anything useful.
            val coverArtData = coverArtUri?.let { loadProcessedCoverBytes(context, it) }
                ?: generatePlaceholderCoverBytes(folderName)

            val audioFiles = children
                .asSequence()
                .filter { it.isFile && isSupportedAudioFile(it.name) }
                .mapNotNull { buildAudioFile(context, it) }
                .sortedBy { it.title.lowercase() }
                .toList()

            StationFolder(
                name = folderName,
                uri = folderDocument.uri,
                coverArtUri = coverArtUri,
                coverArtData = coverArtData,
                audioFiles = audioFiles
            )
        }

    /** Reads, squares (with white padding) and re-encodes a folder's cover.png/jpg. */
    private fun loadProcessedCoverBytes(context: Context, coverUri: android.net.Uri): ByteArray? = try {
        context.contentResolver.openInputStream(coverUri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }?.let { decoded ->
            val squared = CoverArtProcessing.squareWithWhiteBackground(decoded)
            CoverArtProcessing.encodeToJpegBytes(squared).takeIf { it.size <= MAX_ARTWORK_BYTES }
        }
    } catch (e: Exception) {
        null
    }

    /** Generates the "colored square with initials" fallback used when there's no real cover. */
    private fun generatePlaceholderCoverBytes(folderName: String): ByteArray? = try {
        CoverArtProcessing.encodeToJpegBytes(PlaceholderArtwork.generate(folderName))
    } catch (e: Exception) {
        null
    }

    /**
     * Cheaply confirms that every cached [StationFolder] / [AudioFile] still exists on disk,
     * pruning anything that doesn't - without touching [MediaMetadataRetriever] or decoding any
     * artwork. This is what lets the app show the cached library instantly on start while still
     * catching up with folders/files the user deleted since the last full scan, without paying
     * for a full re-scan every single launch.
     *
     * Returns [folders] unchanged (same instance) if nothing needed pruning, so callers can
     * cheaply check `result === folders` / `result == folders` to decide whether the cache needs
     * to be rewritten.
     */
    suspend fun validateAndPrune(context: Context, folders: List<StationFolder>): List<StationFolder> =
        withContext(Dispatchers.IO) {
            var changed = false

            val prunedFolders = folders.mapNotNull { folder ->
                if (!documentStillExists(context, folder.uri)) {
                    changed = true
                    return@mapNotNull null
                }

                val survivingFiles = folder.audioFiles.filter { file ->
                    documentStillExists(context, file.uri)
                }
                if (survivingFiles.size != folder.audioFiles.size) {
                    changed = true
                    folder.copy(audioFiles = survivingFiles)
                } else {
                    folder
                }
            }

            if (changed) prunedFolders else folders
        }

    /**
     * A single lightweight existence check for one already-known document URI - just enough to
     * detect deleted files/folders, no metadata or content is read.
     */
    private fun documentStillExists(context: Context, uri: android.net.Uri): Boolean = try {
        DocumentFile.fromSingleUri(context, uri)?.exists() == true
    } catch (e: Exception) {
        false
    }

    private fun isSupportedAudioFile(fileName: String?): Boolean {
        val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return extension != null && extension in SUPPORTED_EXTENSIONS
    }

    private fun buildAudioFile(context: Context, document: DocumentFile): AudioFile? {
        val uri = document.uri
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            // Files with no usable duration can't be simulated as "live" - skip them.
            if (durationMs <= 0L) return null

            val fileName = document.name ?: uri.toString()
            val fallbackTitle = fileName.substringBeforeLast('.')
            val embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
            val embeddedPicture = retriever.embeddedPicture
            val hasEmbeddedArtwork = embeddedPicture != null
            val artworkData = embeddedPicture?.let { raw ->
                try {
                    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@let null
                    val squared = CoverArtProcessing.squareWithWhiteBackground(decoded)
                    CoverArtProcessing.encodeToJpegBytes(squared).takeIf { it.size <= MAX_ARTWORK_BYTES }
                } catch (e: Exception) {
                    null
                }
            }

            // Seed the random offset from the URI so re-scanning the same file yields the
            // same "simulated live" starting point rather than jumping around each launch.
            val randomStartOffsetMs = Random(uri.toString().hashCode().toLong()).nextLong(durationMs)

            AudioFile(
                uri = uri,
                fileName = fileName,
                title = embeddedTitle ?: fallbackTitle,
                durationMs = durationMs,
                randomStartOffsetMs = randomStartOffsetMs,
                hasEmbeddedArtwork = hasEmbeddedArtwork,
                artworkData = artworkData
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
