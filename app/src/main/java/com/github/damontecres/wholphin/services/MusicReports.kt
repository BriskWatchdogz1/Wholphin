package com.github.damontecres.wholphin.services

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.QueueItem
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The part of the queue reported along with a song
 */
internal data class ReportedQueue(
    val playlistItemId: String?,
    val nowPlayingQueue: List<QueueItem>?,
)

/**
 * Whether the player's current song is being played in [playbackState]
 *
 * After the queue ended the player still points at the last song, and after an error or a stop it
 * is idle; reporting that song then would start a new play of it.
 */
internal fun isPlayingSong(playbackState: Int): Boolean = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING

/**
 * The song the player is on right now
 */
internal data class CurrentSong(
    val itemId: UUID,
    val positionMs: Long,
    val isPaused: Boolean,
    val queue: ReportedQueue,
)

/**
 * One playback report to the server
 */
internal sealed interface MusicReport {
    val itemId: UUID
    val positionMs: Long

    data class Start(
        override val itemId: UUID,
        override val positionMs: Long,
        val isPaused: Boolean,
        val queue: ReportedQueue,
    ) : MusicReport

    data class Progress(
        override val itemId: UUID,
        override val positionMs: Long,
        val isPaused: Boolean,
        val queue: ReportedQueue,
    ) : MusicReport

    data class Stop(
        override val itemId: UUID,
        override val positionMs: Long,
        val queue: ReportedQueue,
    ) : MusicReport
}

/**
 * Turns player events into ordered reports so that every song is its own playback:
 * a stop for the previous song always comes before the start of the next one.
 *
 * Not thread-safe, use on one thread only.
 */
internal class MusicReportSequencer {
    private var openId: UUID? = null
    private var openPositionMs: Long = 0L
    private var lastQueue: ReportedQueue? = null

    private fun stopOpen(positionMs: Long = openPositionMs): MusicReport.Stop? {
        val id = openId ?: return null
        val queue = lastQueue ?: ReportedQueue(null, null)
        openId = null
        return MusicReport.Stop(id, positionMs, queue)
    }

    private fun start(current: CurrentSong): MusicReport.Start {
        openId = current.itemId
        openPositionMs = current.positionMs
        lastQueue = current.queue
        return MusicReport.Start(current.itemId, current.positionMs, current.isPaused, current.queue)
    }

    private fun progressReport(current: CurrentSong): MusicReport.Progress {
        openPositionMs = current.positionMs
        lastQueue = current.queue
        return MusicReport.Progress(current.itemId, current.positionMs, current.isPaused, current.queue)
    }

    /**
     * The player moved to another entry, also the same song again, or the queue was emptied ([current] null)
     */
    fun songChanged(current: CurrentSong?): List<MusicReport> = listOfNotNull(stopOpen(), current?.let { start(it) })

    /**
     * The position the open song had reached when the player left it
     */
    fun positionBeforeChange(
        itemId: UUID,
        positionMs: Long,
    ) {
        if (itemId == openId) openPositionMs = positionMs
    }

    fun progress(current: CurrentSong?): List<MusicReport> {
        if (current == null) return emptyList()
        val open = openId
        return when {
            open == null -> listOf(start(current))
            open != current.itemId -> listOfNotNull(stopOpen(), start(current))
            else -> listOf(progressReport(current))
        }
    }

    /**
     * The queue changed. Only the open song's queue is reported: media3 delivers the timeline change
     * before the media item transition of the same update, so starting a song here would make the
     * transition that follows stop it at 0 ms and start it again.
     */
    fun queueChanged(current: CurrentSong?): List<MusicReport> {
        if (current == null || openId != current.itemId || current.queue == lastQueue) return emptyList()
        return listOf(progressReport(current))
    }

    fun ended(positionMs: Long): List<MusicReport> = listOfNotNull(stopOpen(positionMs))

    fun release(positionMs: Long): List<MusicReport> = ended(positionMs)
}

/**
 * Sends reports one after another from a single coroutine, so their order on the server
 * is the order they were offered
 */
internal class MusicReportSender(
    scope: CoroutineScope,
    private val send: suspend (MusicReport) -> Unit,
) {
    private val channel = Channel<MusicReport>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (report in channel) {
                try {
                    send(report)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "MusicReports: failed sending %s", report)
                }
            }
        }
    }

    fun offer(reports: List<MusicReport>) {
        reports.forEach { channel.trySend(it) }
    }

    /**
     * Takes no new reports. What was offered before is still sent.
     */
    fun close() {
        channel.close()
    }
}
