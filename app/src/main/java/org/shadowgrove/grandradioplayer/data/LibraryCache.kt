package org.shadowgrove.grandradioplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.shadowgrove.grandradioplayer.model.AudioFile
import org.shadowgrove.grandradioplayer.model.StationFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persists the fully-scanned station library (folder names/covers, audio file titles,
 * durations, random "live" offsets and pre-processed artwork bytes) to a small binary file in
 * app-private storage.
 *
 * This is what makes the app's cold start fast: instead of re-running the expensive
 * [org.shadowgrove.grandradioplayer.util.AudioFileScanner] (SAF directory listings +
 * [android.media.MediaMetadataRetriever] + bitmap decoding per file) on every launch, the last
 * known-good library - covers included - is deserialized from disk in milliseconds and shown
 * immediately, while a much cheaper existence-only validation pass
 * ([org.shadowgrove.grandradioplayer.util.AudioFileScanner.validateAndPrune]) quietly removes
 * anything the user deleted in the meantime.
 */
object LibraryCache {

    private const val CACHE_FILE_NAME = "station_library_cache.bin"

    /**
     * Bumped whenever the on-disk layout changes, so old/incompatible caches are ignored.
     *
     * v2: station folders without a cover.png/jpg now carry a generated "initials" placeholder
     * in [StationFolder.coverArtData] instead of null. Bumping invalidates v1 caches that still
     * hold null there, so existing installs regenerate placeholders on the next launch rather
     * than keeping blank artwork tiles in Android Auto until a manual rescan.
     */
    private const val CACHE_FORMAT_VERSION = 2

    /**
     * Loads the cached library, but only if it was saved for the exact same [rootUri] - a cache
     * from a previously selected root directory is meaningless (and possibly misleading) for a
     * different one, so it's treated as a cache miss (null).
     */
    suspend fun load(context: Context, rootUri: Uri): List<StationFolder>? = withContext(Dispatchers.IO) {
        val file = cacheFile(context)
        if (!file.exists()) return@withContext null

        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != CACHE_FORMAT_VERSION) return@withContext null
                if (input.readUTF() != rootUri.toString()) return@withContext null

                val folderCount = input.readInt()
                (0 until folderCount).map { readFolder(input) }
            }
        } catch (e: Exception) {
            // Corrupt/truncated cache (e.g. app was killed mid-write) - just treat as a miss.
            null
        }
    }

    /** Overwrites the cache for [rootUri] with [folders]. Failures are silently ignored. */
    suspend fun save(context: Context, rootUri: Uri, folders: List<StationFolder>) = withContext(Dispatchers.IO) {
        val finalFile = cacheFile(context)
        val tempFile = File(context.filesDir, "$CACHE_FILE_NAME.tmp")

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { output ->
                output.writeInt(CACHE_FORMAT_VERSION)
                output.writeUTF(rootUri.toString())
                output.writeInt(folders.size)
                folders.forEach { writeFolder(output, it) }
            }
            // Write-then-rename so a process death mid-write never leaves a half-written file
            // behind as the "current" cache.
            if (!tempFile.renameTo(finalFile)) {
                finalFile.delete()
                tempFile.renameTo(finalFile)
            }
        } catch (e: Exception) {
            tempFile.delete()
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        cacheFile(context).delete()
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE_NAME)

    private fun writeFolder(output: DataOutputStream, folder: StationFolder) {
        output.writeUTF(folder.name)
        output.writeUTF(folder.uri.toString())
        writeNullableString(output, folder.coverArtUri?.toString())
        writeNullableBytes(output, folder.coverArtData)
        output.writeInt(folder.audioFiles.size)
        folder.audioFiles.forEach { writeAudioFile(output, it) }
    }

    private fun readFolder(input: DataInputStream): StationFolder {
        val name = input.readUTF()
        val uri = Uri.parse(input.readUTF())
        val coverArtUri = readNullableString(input)?.let { Uri.parse(it) }
        val coverArtData = readNullableBytes(input)
        val audioCount = input.readInt()
        val audioFiles = (0 until audioCount).map { readAudioFile(input) }
        return StationFolder(
            name = name,
            uri = uri,
            coverArtUri = coverArtUri,
            coverArtData = coverArtData,
            audioFiles = audioFiles
        )
    }

    private fun writeAudioFile(output: DataOutputStream, file: AudioFile) {
        output.writeUTF(file.uri.toString())
        output.writeUTF(file.fileName)
        output.writeUTF(file.title)
        output.writeLong(file.durationMs)
        output.writeLong(file.randomStartOffsetMs)
        output.writeBoolean(file.hasEmbeddedArtwork)
        writeNullableBytes(output, file.artworkData)
    }

    private fun readAudioFile(input: DataInputStream): AudioFile {
        val uri = Uri.parse(input.readUTF())
        val fileName = input.readUTF()
        val title = input.readUTF()
        val durationMs = input.readLong()
        val randomStartOffsetMs = input.readLong()
        val hasEmbeddedArtwork = input.readBoolean()
        val artworkData = readNullableBytes(input)
        return AudioFile(
            uri = uri,
            fileName = fileName,
            title = title,
            durationMs = durationMs,
            randomStartOffsetMs = randomStartOffsetMs,
            hasEmbeddedArtwork = hasEmbeddedArtwork,
            artworkData = artworkData
        )
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) output.writeUTF(value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) input.readUTF() else null

    private fun writeNullableBytes(output: DataOutputStream, value: ByteArray?) {
        output.writeBoolean(value != null)
        if (value != null) {
            output.writeInt(value.size)
            output.write(value)
        }
    }

    private fun readNullableBytes(input: DataInputStream): ByteArray? {
        if (!input.readBoolean()) return null
        val size = input.readInt()
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes
    }
}

