package com.github.damontecres.wholphin.ui.detail.music

import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.services.MusicServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class NowPlayingFavoriteTest {
    private fun audio(id: UUID = UUID.randomUUID()) =
        AudioItem(
            id = id,
            albumId = null,
            artistId = null,
            title = null,
            albumTitle = null,
            artistNames = null,
            runtime = null,
            imageUrl = null,
            hasLyrics = false,
        )

    private fun state(current: AudioItem?) = NowPlayingState(MusicServiceState.EMPTY, currentMediaItem = current)

    @Test
    fun `the answer for the current song sets the heart`() {
        val a = audio()
        assertEquals(true, state(a).withFavorite(a.id, true).isFavorite)
        assertEquals(false, state(a).withFavorite(a.id, false).isFavorite)
    }

    @Test
    fun `a late answer for a previous song is ignored`() {
        val a = audio()
        val b = audio()
        val s = state(b)
        val result = s.withFavorite(a.id, true)
        assertSame(s, result)
        assertNull(result.isFavorite)
    }

    @Test
    fun `a song change clears the heart until it is known`() {
        val a = audio()
        val b = audio()
        val s = state(a).withFavorite(a.id, true).copy(lyrics = null)
        val changed = s.onSongChange(b)
        assertEquals(b, changed.currentMediaItem)
        assertNull(changed.isFavorite)
        assertNull(changed.lyrics)
        assertNull(changed.currentLyricIndex)
    }
}
