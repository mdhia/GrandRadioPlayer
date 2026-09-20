package org.shadowgrove.grandradioplayer.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.shadowgrove.grandradioplayer.model.AudioFile
import org.shadowgrove.grandradioplayer.model.StationFolder
import org.shadowgrove.grandradioplayer.playback.PlaybackConnection
import org.shadowgrove.grandradioplayer.playback.StationLibraryRepository

/**
 * Exposes the scanned station library to the Home UI and forwards "tune to this file" requests
 * to [RadioPlaybackService] via [PlaybackConnection].
 */
@UnstableApi
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Only folders that actually contain playable audio are surfaced - empty station folders
     * would otherwise show up as dead tiles in the carousel.
     */
    val stationFolders: StateFlow<List<StationFolder>> =
        StationLibraryRepository.stationFolders
            .map { folders -> folders.filter { it.audioFiles.isNotEmpty() } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    private val playbackConnection = PlaybackConnection(application)

    /** mediaId (content URI string) of the file currently loaded in the player, if any. */
    val nowPlayingMediaId: StateFlow<String?> = playbackConnection.nowPlayingMediaId

    /** Whether the player is actively playing right now (vs. paused). */
    val isPlaying: StateFlow<Boolean> = playbackConnection.isPlaying

    fun tuneToStation(file: AudioFile, stationName: String) {
        playbackConnection.tuneToStation(file, stationName)
    }

    fun togglePlayPause() {
        playbackConnection.togglePlayPause()
    }

    override fun onCleared() {
        playbackConnection.release()
    }
}
