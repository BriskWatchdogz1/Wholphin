package com.github.damontecres.wholphin.services

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.github.damontecres.wholphin.data.model.AudioItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reports every song of the music queue to the server as its own playback
 *
 * Replaces the shared activity listener for music: a stop for the previous song is always sent
 * before the start of the next one, by one sender that keeps the order.
 *
 * Every method runs on the main thread, as does [current].
 */
internal class MusicPlaybackReporter(
    private val api: ApiClient,
    private val player: Player,
    private val scope: CoroutineScope,
    private val current: () -> CurrentSong?,
) : Player.Listener {
    private val sequencer = MusicReportSequencer()
    private val sender = MusicReportSender(scope, ::sendReport)
    private var ticker: Job? = null

    fun start() {
        player.addListener(this)
        ticker =
            scope.launch(Dispatchers.Main) {
                while (true) {
                    delay(5.seconds)
                    sender.offer(sequencer.progress(current()))
                }
            }
        sender.offer(sequencer.progress(current()))
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        player.removeListener(this)
        sender.offer(sequencer.release(player.currentPosition))
        sender.close()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // media3 queues this event before onMediaItemTransition for the same update
        // (ExoPlayerImpl.updatePlaybackInfo, 1.11.1), so the old position is known when the song changes.
        val id = (oldPosition.mediaItem?.localConfiguration?.tag as? AudioItem)?.id ?: return
        sequencer.positionBeforeChange(id, oldPosition.positionMs)
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        sender.offer(sequencer.songChanged(current()))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        sender.offer(sequencer.progress(current()))
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            sender.offer(sequencer.ended(player.currentPosition))
        }
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            sender.offer(sequencer.queueChanged(current()))
        }
    }

    private suspend fun sendReport(report: MusicReport) {
        // Names spelled out: release builds shrink class names
        val kind =
            when (report) {
                is MusicReport.Start -> "Start"
                is MusicReport.Progress -> "Progress"
                is MusicReport.Stop -> "Stop"
            }
        Timber.i("MusicReports: %s %s at %d ms", kind, report.itemId, report.positionMs)
        val ticks =
            report.positionMs
                .coerceAtLeast(0)
                .milliseconds.inWholeTicks
        when (report) {
            is MusicReport.Start -> {
                api.sessionApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = report.itemId,
                        positionTicks = ticks,
                        canSeek = true,
                        isPaused = report.isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        playlistItemId = report.queue.playlistItemId,
                        nowPlayingQueue = report.queue.nowPlayingQueue,
                    ),
                )
            }

            is MusicReport.Progress -> {
                api.sessionApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = report.itemId,
                        positionTicks = ticks,
                        canSeek = true,
                        isPaused = report.isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        playlistItemId = report.queue.playlistItemId,
                        nowPlayingQueue = report.queue.nowPlayingQueue,
                    ),
                )
            }

            is MusicReport.Stop -> {
                api.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = report.itemId,
                        positionTicks = ticks,
                        failed = false,
                        playlistItemId = report.queue.playlistItemId,
                        nowPlayingQueue = report.queue.nowPlayingQueue,
                    ),
                )
            }
        }
    }
}
