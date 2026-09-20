package org.shadowgrove.grandradioplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.shadowgrove.grandradioplayer.model.AudioFile

/**
 * App-side connection to [RadioPlaybackService], wrapping a [MediaController] so Compose
 * screens/ViewModels never have to deal with [SessionToken] / [ListenableFuture] plumbing
 * directly.
 *
 * Tuning to a station only ever needs the target [AudioFile]'s mediaId - the service is the one
 * that resolves it against its own scanned library and computes the "simulated live" seek
 * position, so this class never needs to (and never should) supply a raw playback URI itself.
 */
@UnstableApi
class PlaybackConnection(private val context: Context) {

    private val _controller = MutableStateFlow<MediaController?>(null)
    val isConnected: StateFlow<Boolean> get() = _isConnected.asStateFlow()
    private val _isConnected = MutableStateFlow(false)

    /** The mediaId (== content URI string) of whatever the player currently has loaded. */
    val nowPlayingMediaId: StateFlow<String?> get() = _nowPlayingMediaId.asStateFlow()
    private val _nowPlayingMediaId = MutableStateFlow<String?>(null)

    /** Whether the player is actively playing (vs. paused/idle) right now. */
    val isPlaying: StateFlow<Boolean> get() = _isPlaying.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _nowPlayingMediaId.value = mediaItem?.mediaId
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }
    }

    private var controller: MediaController?
        get() = _controller.value
        set(value) {
            _controller.value = value
            _isConnected.value = value != null
            value?.let {
                it.addListener(playerListener)
                _nowPlayingMediaId.value = it.currentMediaItem?.mediaId
                _isPlaying.value = it.isPlaying
            }
        }

    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
    ).buildAsync()

    init {
        controllerFuture.addListener(
            { controller = controllerFuture.get() },
            MoreExecutors.directExecutor()
        )
    }

    /** Requests playback of [file] (which belongs to station [stationName]). */
    fun tuneToStation(file: AudioFile, stationName: String) {
        val mediaController = controller ?: return
        val mediaItem = MediaItemFactory.audioItem(file, stationName)
        mediaController.setMediaItem(mediaItem)
        mediaController.prepare()
        mediaController.play()
    }

    fun play() { controller?.play() }

    fun pause() { controller?.pause() }

    fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
    }

    /** Skips to the next file in the flattened, all-stations queue. */
    fun skipToNext() { controller?.seekToNext() }

    /** Skips to the previous file in the flattened, all-stations queue. */
    fun skipToPrevious() { controller?.seekToPrevious() }

    fun release() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        controller = null
    }
}




