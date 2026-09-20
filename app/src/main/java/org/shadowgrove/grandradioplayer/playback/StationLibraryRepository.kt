package org.shadowgrove.grandradioplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.shadowgrove.grandradioplayer.model.AudioFile
import org.shadowgrove.grandradioplayer.model.StationFolder

/**
 * In-memory, single-process source of truth for the scanned station library.
 *
 * The UI layer (ViewModels, after running [org.shadowgrove.grandradioplayer.util.AudioFileScanner])
 * pushes results in via [updateLibrary]. [RadioPlaybackService] observes [stationFolders] to keep
 * the player's queue in sync and uses the lookup helpers to strictly resolve any mediaId requested
 * by a controller (app UI, Android Auto, or any other connecting client) back to a real, locally
 * known [AudioFile] - this is what makes it possible to reject/ignore anything that isn't actually
 * part of our own scanned library.
 */
object StationLibraryRepository {

    private val _stationFolders = MutableStateFlow<List<StationFolder>>(emptyList())
    val stationFolders: StateFlow<List<StationFolder>> = _stationFolders.asStateFlow()

    fun updateLibrary(folders: List<StationFolder>) {
        _stationFolders.value = folders
    }

    /** Finds a known audio file by its mediaId (== its content URI as a string). */
    fun findAudioFile(mediaId: String): AudioFile? =
        _stationFolders.value.firstNotNullOfOrNull { folder ->
            folder.audioFiles.firstOrNull { it.uri.toString() == mediaId }
        }

    /** Finds the name of the station folder that contains the audio file with [mediaId]. */
    fun stationNameForAudioFile(mediaId: String): String? =
        folderForAudioFile(mediaId)?.name

    /** Finds the full [StationFolder] that contains the audio file with [mediaId], if any. */
    fun folderForAudioFile(mediaId: String): StationFolder? =
        _stationFolders.value.firstOrNull { folder ->
            folder.audioFiles.any { it.uri.toString() == mediaId }
        }


    fun findStationFolder(folderMediaId: String): StationFolder? =
        _stationFolders.value.firstOrNull { MediaItemFactory.stationMediaId(it.name) == folderMediaId }

    /** Every audio file across every station, in stable folder/name order - the player's queue. */
    fun flattenedAudioFiles(): List<Pair<AudioFile, String>> =
        _stationFolders.value.flatMap { folder -> folder.audioFiles.map { it to folder.name } }
}
