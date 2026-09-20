package org.shadowgrove.grandradioplayer.engine

import android.os.SystemClock
import org.shadowgrove.grandradioplayer.model.AudioFile
import kotlin.random.Random

/**
 * Drives the "simulated live radio" behaviour.
 *
 * A single global timer ([appStartTime]) is captured once, the first time this singleton is
 * touched (i.e. effectively on app start). Every [AudioFile] already carries its own
 * [AudioFile.randomStartOffsetMs] (assigned once by [org.shadowgrove.grandradioplayer.util.AudioFileScanner]
 * when it was scanned, and stable across re-scans of the same file).
 *
 * On its own, [appStartTime] + a *stable* per-file offset would make every station resume at the
 * exact same spot every time the app is launched (elapsed time is always ~0 right at start).
 * [broadcastEpochOffsetMs] fixes that: it's a fresh random value rolled once per process
 * lifetime and folded into every position calculation, simulating that the "broadcast" has
 * already been running for a random amount of time before this app launch even happened - so
 * tuning into the same station right after two different app starts lands on two different
 * positions, exactly like a real live radio station would.
 *
 * When the user tunes into a station, [getCurrentPlaybackPosition] computes on-demand where in
 * the file playback "would currently be" had it been playing continuously since app start -
 * without ever actually playing it in the background. This keeps battery usage minimal while
 * still giving the illusion of a live broadcast that keeps moving even while you're not
 * listening to it.
 */
object RadioSimulationEngine {

    /**
     * The single global timer for the whole simulated broadcast. Captured once per process
     * lifetime via [SystemClock.elapsedRealtime], which is monotonic and unaffected by wall
     * clock changes (time zone, user adjusting the clock, etc.).
     */
    val appStartTime: Long = SystemClock.elapsedRealtime()

    /**
     * A random offset (0..24h in milliseconds), rolled fresh every time the process starts.
     * Combined with the monotonic [appStartTime] timer, this is what makes the "simulated live"
     * starting position differ between app launches instead of always being the same spot.
     */
    private val broadcastEpochOffsetMs: Long = Random.nextLong(0L, 24L * 60 * 60 * 1000)

    /**
     * Computes the current playback position (in milliseconds) for [file], as if it had been
     * looping continuously since some point before [appStartTime]:
     *
     * ```
     * elapsed = (SystemClock.elapsedRealtime() - appStartTime) + broadcastEpochOffsetMs
     * currentPosition = (randomStartOffset + elapsed) % file.duration
     * ```
     */
    fun getCurrentPlaybackPosition(file: AudioFile): Long {
        if (file.durationMs <= 0L) return 0L

        val elapsed = (SystemClock.elapsedRealtime() - appStartTime) + broadcastEpochOffsetMs
        return (file.randomStartOffsetMs + elapsed) % file.durationMs
    }
}


