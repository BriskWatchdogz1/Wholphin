package com.github.damontecres.wholphin.services

import androidx.media3.common.Player
import org.jellyfin.sdk.model.api.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MusicReportSequencerTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val q = ReportedQueue("playlistItem1", emptyList())

    private fun song(
        id: UUID,
        pos: Long,
        paused: Boolean = false,
        queue: ReportedQueue = q,
    ) = CurrentSong(id, pos, paused, queue)

    @Test
    fun `first song sends only a start`() {
        assertEquals(
            listOf(MusicReport.Start(a, 0, false, q)),
            MusicReportSequencer().songChanged(song(a, 0)),
        )
    }

    @Test
    fun `a skip sends the stop with the position reached, then the start`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.positionBeforeChange(a, 12_000)
        assertEquals(
            listOf(MusicReport.Stop(a, 12_000, q), MusicReport.Start(b, 0, false, q)),
            s.songChanged(song(b, 0)),
        )
    }

    @Test
    fun `stop comes before start on an automatic transition`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.positionBeforeChange(a, 215_000)
        val result = s.songChanged(song(b, 0))
        assertEquals(MusicReport.Stop(a, 215_000, q), result[0])
        assertTrue(result[1] is MusicReport.Start)
    }

    @Test
    fun `repeat of the same song stops and starts it again`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.positionBeforeChange(a, 180_000)
        assertEquals(
            listOf(MusicReport.Stop(a, 180_000, q), MusicReport.Start(a, 0, false, q)),
            s.songChanged(song(a, 0)),
        )
    }

    @Test
    fun `a change without a known position stops at the last progress`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.progress(song(a, 95_000))
        assertEquals(MusicReport.Stop(a, 95_000, q), s.songChanged(song(b, 0)).first())
    }

    @Test
    fun `progress before any start sends the start first`() {
        assertEquals(
            listOf(MusicReport.Start(a, 3_000, false, q)),
            MusicReportSequencer().progress(song(a, 3_000)),
        )
    }

    @Test
    fun `progress of the open song is a progress report`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        assertEquals(
            listOf(MusicReport.Progress(a, 5_000, true, q)),
            s.progress(song(a, 5_000, paused = true)),
        )
    }

    @Test
    fun `a missed transition closes the old song before the new one starts`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.progress(song(a, 40_000))
        assertEquals(
            listOf(MusicReport.Stop(a, 40_000, q), MusicReport.Start(b, 1_000, false, q)),
            s.progress(song(b, 1_000)),
        )
    }

    @Test
    fun `the end of the queue sends one stop and release sends nothing more`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        assertEquals(listOf(MusicReport.Stop(a, 200_000, q)), s.ended(200_000))
        assertEquals(emptyList<MusicReport>(), s.release(0))
    }

    @Test
    fun `replacing the queue stops the current song`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.positionBeforeChange(a, 30_000)
        assertEquals(listOf(MusicReport.Stop(a, 30_000, q)), s.songChanged(null))
        assertEquals(listOf(MusicReport.Start(b, 0, false, q)), s.songChanged(song(b, 0)))
    }

    @Test
    fun `a stop after a queue replacement carries the new queue`() {
        val s = MusicReportSequencer()
        val q2 = ReportedQueue("playlistItem2", emptyList())
        s.songChanged(song(a, 0))
        s.positionBeforeChange(a, 30_000)
        assertEquals(
            listOf(MusicReport.Stop(a, 30_000, q2), MusicReport.Start(b, 0, false, q2)),
            s.songChanged(song(b, 0, queue = q2)),
        )
    }

    @Test
    fun `a queue change is reported once`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        val q2 = ReportedQueue("playlistItem1", listOf(QueueItem(id = b, playlistItemId = "playlistItem2")))
        assertEquals(
            listOf(MusicReport.Progress(a, 1_000, false, q2)),
            s.queueChanged(song(a, 1_000, queue = q2)),
        )
        assertEquals(emptyList<MusicReport>(), s.queueChanged(song(a, 1_000, queue = q2)))
    }

    @Test
    fun `nothing is reported without a song`() {
        val s = MusicReportSequencer()
        assertEquals(emptyList<MusicReport>(), s.progress(null))
        assertEquals(emptyList<MusicReport>(), s.queueChanged(null))
        assertEquals(emptyList<MusicReport>(), s.ended(0))
        assertEquals(emptyList<MusicReport>(), s.release(0))
    }

    @Test
    fun `only a ready or buffering player plays its current song`() {
        assertTrue(isPlayingSong(Player.STATE_READY))
        assertTrue(isPlayingSong(Player.STATE_BUFFERING))
        assertFalse(isPlayingSong(Player.STATE_ENDED))
        assertFalse(isPlayingSong(Player.STATE_IDLE))
    }

    @Test
    fun `after the end no new play starts until a song plays again`() {
        val s = MusicReportSequencer()
        s.songChanged(song(a, 0))
        s.ended(200_000)
        // the player is ended, so the service reports no current song
        assertEquals(emptyList<MusicReport>(), s.progress(null))
        assertEquals(listOf(MusicReport.Start(a, 0, false, q)), s.progress(song(a, 0)))
    }

    @Test
    fun `a queue change before the transition does not start the song twice`() {
        val s = MusicReportSequencer()
        // media3 order for a new queue: timeline change first, then the media item transition
        assertEquals(emptyList<MusicReport>(), s.queueChanged(song(b, 0)))
        assertEquals(listOf(MusicReport.Start(b, 0, false, q)), s.songChanged(song(b, 0)))
    }
}
