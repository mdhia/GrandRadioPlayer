package org.shadowgrove.grandradioplayer.playback

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.shadowgrove.grandradioplayer.R
import org.shadowgrove.grandradioplayer.data.SettingsRepository
import org.shadowgrove.grandradioplayer.engine.RadioSimulationEngine
import org.shadowgrove.grandradioplayer.model.StationFolder

/**
 * The single [MediaLibraryService] powering background playback, the system media
 * notification, and (in a later phase) Android Auto browsing.
 *
 * Two security/behaviour guarantees are enforced here, regardless of who the connected
 * controller is (our own app, the system, or an Android Auto host):
 *
 * 1. **Only URIs that come from our own scanned library are ever played.** Every incoming
 *    [MediaItem] is re-resolved strictly by its `mediaId` against [StationLibraryRepository];
 *    any caller-supplied `uri`/`localConfiguration` is discarded and rebuilt from our own data.
 *    Unknown mediaIds are dropped entirely (see [resolveKnownMediaItem]).
 * 2. **"Simulated live" seeking.** Whenever a specific file starts playing - whether requested
 *    explicitly by the app UI ([onSetMediaItems]) or reached via a skip-next/previous / seek
 *    ([Player.Listener.onMediaItemTransition]) - its current position is computed on-demand via
 *    [RadioSimulationEngine.getCurrentPlaybackPosition] and applied *before* playback continues.
 */
@UnstableApi
class RadioPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepository by lazy { SettingsRepository(this) }

    /**
     * Station folder names ordered by recency of last playback (most recent first), mirrored
     * from [SettingsRepository.recentFolderOrder] for synchronous access from [onGetChildren]
     * (a plain, non-suspending callback). Drives Android Auto's "order the folder list by the
     * last listened folder" behaviour.
     */
    @Volatile
    private var recentFolderOrder: List<String> = emptyList()

    /**
     * True once a specific file has actually been tuned into (via the app UI, a car custom
     * command, or a resolved skip/seek) - as opposed to the player merely having a queue loaded
     * with its default first item. Lets [onPlayerCommandRequest] tell apart "the user genuinely
     * asked to play *this*" from "something (e.g. Android Auto auto-resuming, or a voice
     * assistant's bare 'play') sent a generic play command before anything was ever chosen".
     */
    @Volatile
    private var hasTunedIn: Boolean = false

    private companion object {
        /** Tunes to a random station, the car-friendly equivalent of browsing the carousel. */
        const val CUSTOM_COMMAND_SHUFFLE_STATION = "org.shadowgrove.grandradioplayer.SHUFFLE_STATION"
    }

    /**
     * The two extra buttons shown on Android Auto's now-playing screen, next to the standard
     * play/pause and skip controls that the template always provides.
     */
    private val customLayout: ImmutableList<CommandButton> by lazy {
        ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Shuffle station")
                .setIconResId(R.drawable.ic_car_shuffle_station)
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_SHUFFLE_STATION, Bundle.EMPTY))
                .build()
        )
    }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .build()
            .apply {
                // A single file should loop seamlessly when it reaches the end - requirement #5.
                // Native ExoPlayer looping never re-triggers onMediaItemTransition with reason
                // SEEK, so the "live" re-sync logic below leaves this loop completely untouched.
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(playerListener)
            }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, librarySessionCallback)
            .setCustomLayout(customLayout)
            .build()

        // Keep the player's queue in sync with whatever the app has scanned so far, without
        // disrupting anything that's already playing when nothing actually changed.
        serviceScope.launch {
            StationLibraryRepository.stationFolders.collect { rebuildQueueIfNeeded() }
        }

        // Cache the persisted "last listened folder" order for synchronous use in onGetChildren.
        serviceScope.launch {
            settingsRepository.recentFolderOrder.collect { recentFolderOrder = it }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaLibrarySession

    override fun onDestroy() {
        player.removeListener(playerListener)
        mediaLibrarySession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun rebuildQueueIfNeeded() {
        val folders = StationLibraryRepository.stationFolders.value
        val flattened = folders.flatMap { folder ->
            folder.audioFiles.map { file ->
                MediaItemFactory.audioItem(file, folder.name, folder.coverArtData)
            }
        }
        if (flattened.isEmpty()) return

        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (currentIds == flattened.map { it.mediaId }) return // Nothing changed - leave playback alone.

        player.setMediaItems(flattened, /* resetPosition = */ false)
        player.prepare()
    }

    /**
     * Rebuilds a [MediaItem] strictly from our own repository, keyed only by [requested]'s
     * mediaId. Any URI the caller may have attached is intentionally ignored. Returns null for
     * anything that isn't part of our scanned library - external/foreign content never plays.
     */
    private fun resolveKnownMediaItem(requested: MediaItem): MediaItem? {
        val mediaId = requested.mediaId
        val audioFile = StationLibraryRepository.findAudioFile(mediaId) ?: return null
        val folder = StationLibraryRepository.folderForAudioFile(mediaId)
        return MediaItemFactory.audioItem(audioFile, folder?.name.orEmpty(), folder?.coverArtData)
    }

    /**
     * Picks a random track from a random non-empty station and tunes into it at its current
     * live position - the driver-safe, one-tap equivalent of scrolling the phone's carousel.
     * Also what [onPlayerCommandRequest] falls back to when playback is requested (e.g. by
     * Android Auto or a voice assistant) before the user has ever picked a station themselves.
     */
    private fun tuneToRandomStation() {
        val folders = StationLibraryRepository.stationFolders.value
            .filter { it.audioFiles.isNotEmpty() }
        if (folders.isEmpty()) return

        val folder = folders.random()
        val file = folder.audioFiles.random()
        val targetMediaId = file.uri.toString()

        val queueIndex = (0 until player.mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaId == targetMediaId }
            ?: return

        hasTunedIn = true
        player.seekTo(queueIndex, RadioSimulationEngine.getCurrentPlaybackPosition(file))
        player.play()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val mediaId = mediaItem?.mediaId
            // Records which station this file belongs to as "last listened", regardless of the
            // transition reason - this is what drives Android Auto's recency-ordered folder
            // list. Safe to do unconditionally: because every file loops in place
            // (REPEAT_MODE_ONE), a transition to a *different* file only ever happens through an
            // explicit tune-in or skip, never as a side effect of the loop itself.
            if (mediaId != null) {
                StationLibraryRepository.folderForAudioFile(mediaId)?.name?.let { folderName ->
                    serviceScope.launch { settingsRepository.recordFolderPlayed(folderName) }
                }
            }

            // Only re-sync position on an explicit jump (initial tune-in that bypassed
            // onSetMediaItems, or a native skip-next/previous command). Ignore natural
            // REPEAT_MODE_ONE loop transitions so requirement #5's loop stays glitch-free.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
            val audioFile = mediaId?.let { StationLibraryRepository.findAudioFile(it) } ?: return

            hasTunedIn = true
            val livePosition = RadioSimulationEngine.getCurrentPlaybackPosition(audioFile)
            player.seekTo(livePosition)
        }
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        /**
         * Grants every controller - our own UI, the system, and the Android Auto head unit -
         * the two custom commands on top of the default session/library commands, so the extra
         * buttons in [customLayout] are actually usable in the car.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_SHUFFLE_STATION, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(customLayout)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_COMMAND_SHUFFLE_STATION -> tuneToRandomStation()
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Intercepts a play request from *any* connected controller (Android Auto resuming the
         * session, a voice assistant's bare "play", the system media button, ...). If nothing
         * has actually been tuned into yet, requirement #3: pick a random station instead of
         * silently starting whatever untouched item index 0 of the queue happens to be.
         */
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            @Player.Command playerCommand: Int
        ): Int {
            if (!hasTunedIn && playerCommand == Player.COMMAND_PLAY_PAUSE) {
                tuneToRandomStation()
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItemFactory.rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val folders = StationLibraryRepository.stationFolders.value

            val children: List<MediaItem> = if (parentId == MediaItemFactory.ROOT_MEDIA_ID) {
                // Mirrors the phone UI: empty station folders are hidden rather than shown as
                // dead entries the driver could tap into. The remaining folders are ordered by
                // recency of last playback (most recently listened first), falling back to
                // alphabetical for folders that haven't been played yet - see recentFolderOrder.
                folders
                    .filter { it.audioFiles.isNotEmpty() }
                    .sortedWith(
                        compareBy<StationFolder> { folder ->
                            recentFolderOrder.indexOf(folder.name).let { if (it == -1) Int.MAX_VALUE else it }
                        }.thenBy { it.name.lowercase() }
                    )
                    .map(MediaItemFactory::folderItem)
            } else {
                val folder = StationLibraryRepository.findStationFolder(parentId)
                folder?.audioFiles?.map { MediaItemFactory.audioItem(it, folder.name, folder.coverArtData) }
                    ?: emptyList()
            }

            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val resolved = resolveKnownMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(resolved, null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Restriction #2: silently drop anything that doesn't resolve to a known local file.
            val resolved = mediaItems.mapNotNull(::resolveKnownMediaItem).toMutableList()
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.mapNotNull(::resolveKnownMediaItem)
            if (resolved.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mutableListOf(), 0, C.TIME_UNSET)
                )
            }

            val safeStartIndex = startIndex.takeIf { it in resolved.indices } ?: 0
            val targetAudioFile = StationLibraryRepository.findAudioFile(resolved[safeStartIndex].mediaId)
            hasTunedIn = true

            // Requirement #4: compute the "simulated live" position for the requested file and
            // report it as the start position, so playback begins there instead of at zero.
            val computedStartPositionMs = targetAudioFile
                ?.let { RadioSimulationEngine.getCurrentPlaybackPosition(it) }
                ?: C.TIME_UNSET

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    resolved.toMutableList(),
                    safeStartIndex,
                    computedStartPositionMs
                )
            )
        }
    }
}
