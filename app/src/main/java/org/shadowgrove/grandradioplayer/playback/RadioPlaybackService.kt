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
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.shadowgrove.grandradioplayer.R
import org.shadowgrove.grandradioplayer.data.LibraryCache
import org.shadowgrove.grandradioplayer.data.SettingsRepository
import org.shadowgrove.grandradioplayer.engine.RadioSimulationEngine
import org.shadowgrove.grandradioplayer.model.StationFolder
import org.shadowgrove.grandradioplayer.util.AudioFileScanner

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
 *
 * Android Auto (or any other [androidx.media3.session.MediaBrowser] host) can bind to this
 * service directly, without [org.shadowgrove.grandradioplayer.MainActivity] ever having run in
 * this process first (e.g. right after a reboot, or the app was killed in the background).
 * [ensureLibraryLoadedStandalone] is what makes that work: it loads the last cached library (or
 * scans from scratch if there's no cache yet) straight from here, so the browse tree/queue are
 * never left empty just because the phone app itself hasn't been opened.
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
        /** Re-aligns the current track with the global simulated-live timer. */
        const val CUSTOM_COMMAND_SYNC_LIVE = "org.shadowgrove.grandradioplayer.SYNC_LIVE"

        /** Tunes to a random station, the car-friendly equivalent of browsing the carousel. */
        const val CUSTOM_COMMAND_SHUFFLE_STATION = "org.shadowgrove.grandradioplayer.SHUFFLE_STATION"

        /**
         * How long a browse request may wait for the library to finish loading before answering
         * with whatever is available. Comfortably below the car host's own request timeout, so a
         * genuinely empty library still produces a normal (empty) response instead of an error.
         */
        const val LIBRARY_WAIT_TIMEOUT_MS = 5_000L

        /**
         * How many of the most recently played station folders get pinned to the top of Android
         * Auto's folder list. Everything beyond that stays alphabetical, so the list doesn't
         * keep reordering itself as the driver listens around.
         */
        const val MAX_PINNED_RECENT_FOLDERS = 3
    }

    /**
     * Suspends until [StationLibraryRepository] has content (or [LIBRARY_WAIT_TIMEOUT_MS] elapses).
     * Returns immediately once the library is populated, so only the very first browse request
     * after a cold start actually waits.
     */
    private suspend fun awaitLibraryLoaded() {
        if (StationLibraryRepository.stationFolders.value.isNotEmpty()) return
        withTimeoutOrNull(LIBRARY_WAIT_TIMEOUT_MS) {
            StationLibraryRepository.stationFolders.first { it.isNotEmpty() }
        }
    }

    /**
     * The two extra buttons shown on Android Auto's now-playing screen, next to the standard
     * play/pause and skip controls that the template always provides.
     */
    private val customLayout: ImmutableList<CommandButton> by lazy {
        ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Sync to live")
                .setIconResId(R.drawable.ic_car_sync_live)
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_SYNC_LIVE, Bundle.EMPTY))
                .build(),
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
        // disrupting anything that's already playing when nothing actually changed. Also tells
        // any subscribed browser (Android Auto) to re-fetch the browse tree whenever the library
        // changes, so an already-open screen refreshes itself instead of staying stale.
        serviceScope.launch {
            StationLibraryRepository.stationFolders.collect { folders ->
                rebuildQueueIfNeeded()

                val visibleFolders = folders.filter { it.audioFiles.isNotEmpty() }
                mediaLibrarySession.notifyChildrenChanged(
                    MediaItemFactory.ROOT_MEDIA_ID,
                    visibleFolders.size,
                    null
                )
                // Also refresh any folder the driver may already have drilled into.
                visibleFolders.forEach { folder ->
                    mediaLibrarySession.notifyChildrenChanged(
                        MediaItemFactory.stationMediaId(folder.name),
                        folder.audioFiles.size,
                        null
                    )
                }
            }
        }

        // Cache the persisted "last listened folder" order for synchronous use in onGetChildren.
        serviceScope.launch {
            settingsRepository.recentFolderOrder.collect { recentFolderOrder = it }
        }

        // Bugfix: if this service is the first thing to start in a fresh process (Android Auto
        // connecting before the phone app was ever opened), nothing would otherwise populate
        // StationLibraryRepository, and the car would show "No media" forever.
        ensureLibraryLoadedStandalone()
    }

    /**
     * Loads the station library from disk (cache first, falling back to a full scan) if nobody
     * has populated [StationLibraryRepository] yet. Safe to call even if the app's own Activity
     * ends up loading the library moments later too - both paths write the same data.
     */
    private fun ensureLibraryLoadedStandalone() {
        if (StationLibraryRepository.stationFolders.value.isNotEmpty()) return

        serviceScope.launch {
            val rootUri = settingsRepository.rootDirectoryUri.first() ?: return@launch

            val cached = LibraryCache.load(this@RadioPlaybackService, rootUri)
            if (cached != null) {
                StationLibraryRepository.updateLibrary(cached)

                val validated = AudioFileScanner.validateAndPrune(this@RadioPlaybackService, cached)
                if (validated != cached) {
                    StationLibraryRepository.updateLibrary(validated)
                    LibraryCache.save(this@RadioPlaybackService, rootUri, validated)
                }
            } else {
                val scanned = AudioFileScanner.scanRootDirectory(this@RadioPlaybackService, rootUri)
                StationLibraryRepository.updateLibrary(scanned)
                LibraryCache.save(this@RadioPlaybackService, rootUri, scanned)
            }
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
     * Re-aligns the currently loaded track with the global simulated-live timer. Useful in the
     * car after a long pause, where the "station" has kept broadcasting conceptually while the
     * player sat still.
     */
    private fun syncCurrentItemToLive() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val audioFile = StationLibraryRepository.findAudioFile(mediaId) ?: return
        hasTunedIn = true
        player.seekTo(RadioSimulationEngine.getCurrentPlaybackPosition(audioFile))
        player.play()
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
                .add(SessionCommand(CUSTOM_COMMAND_SYNC_LIVE, Bundle.EMPTY))
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
                CUSTOM_COMMAND_SYNC_LIVE -> syncCurrentItemToLive()
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

        /**
         * Bugfix for Android Auto showing a permanently empty library when the app wasn't already
         * running: the car connects and queries the browse tree immediately, typically *before*
         * [ensureLibraryLoadedStandalone] has finished reading the cache/scanning. Answering with
         * an empty list in that window makes the head unit render (and keep) a "No items" screen,
         * even though the library shows up moments later - which is exactly why playback still
         * worked (it's triggered after loading) while browsing stayed empty.
         *
         * So instead of replying instantly with nothing, the response is deferred until the
         * library is actually populated (bounded by [LIBRARY_WAIT_TIMEOUT_MS] so the car never
         * hangs waiting on us).
         */
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

            serviceScope.launch {
                awaitLibraryLoaded()
                val children = buildChildren(parentId)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }

            return future
        }

        private fun buildChildren(parentId: String): List<MediaItem> {
            val folders = StationLibraryRepository.stationFolders.value

            return if (parentId == MediaItemFactory.ROOT_MEDIA_ID) {
                // Mirrors the phone UI: empty station folders are hidden rather than shown as
                // dead entries the driver could tap into. Of the rest, only the few most
                // recently played folders are pinned to the top (so getting back to what you
                // were just listening to is a glance away); everything below that stays plain
                // alphabetical instead of constantly reshuffling as you listen around.
                val pinnedToTop = recentFolderOrder.take(MAX_PINNED_RECENT_FOLDERS)

                folders
                    .filter { it.audioFiles.isNotEmpty() }
                    .sortedWith(
                        compareBy<StationFolder> { folder ->
                            pinnedToTop.indexOf(folder.name).let { if (it == -1) Int.MAX_VALUE else it }
                        }.thenBy { it.name.lowercase() }
                    )
                    .map(MediaItemFactory::folderItem)
            } else {
                val folder = StationLibraryRepository.findStationFolder(parentId)
                folder?.audioFiles?.map { MediaItemFactory.audioItem(it, folder.name, folder.coverArtData) }
                    ?: emptyList()
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()

            // Same cold-start race as onGetChildren: wait for the library before declaring an id
            // unknown, otherwise a tap right after connecting could be rejected.
            serviceScope.launch {
                awaitLibraryLoaded()

                val folder = StationLibraryRepository.findStationFolder(mediaId)
                if (folder != null) {
                    future.set(LibraryResult.ofItem(MediaItemFactory.folderItem(folder), null))
                    return@launch
                }

                val resolved = resolveKnownMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
                future.set(
                    if (resolved != null) {
                        LibraryResult.ofItem(resolved, null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                )
            }

            return future
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
