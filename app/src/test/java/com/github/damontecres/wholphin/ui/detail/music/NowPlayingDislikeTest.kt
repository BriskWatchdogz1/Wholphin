package com.github.damontecres.wholphin.ui.detail.music

import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.services.MusicServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class NowPlayingDislikeTest {
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
    fun `the answer for the current song sets heart and thumb`() {
        val a = audio()
        val s = state(a).withMarks(a.id, isFavorite = false, likes = false)
        assertEquals(false, s.isFavorite)
        assertEquals(true, s.isDisliked)
    }

    @Test
    fun `likes false means disliked, null or true means not`() {
        val a = audio()
        assertEquals(true, state(a).withMarks(a.id, false, false).isDisliked)
        assertEquals(false, state(a).withMarks(a.id, false, null).isDisliked)
        assertEquals(false, state(a).withMarks(a.id, true, true).isDisliked)
    }

    @Test
    fun `a favorite answer without a dislike clears the thumb`() {
        val a = audio()
        val disliked = state(a).withMarks(a.id, false, false)
        val s = disliked.withMarks(a.id, isFavorite = true, likes = null)
        assertEquals(true, s.isFavorite)
        assertEquals(false, s.isDisliked)
    }

    @Test
    fun `a late answer for a previous song is ignored`() {
        val a = audio()
        val s = state(audio())
        val result = s.withMarks(a.id, true, false)
        assertSame(s, result)
        assertNull(result.isDisliked)
    }

    @Test
    fun `a song change clears the thumb until it is known`() {
        val a = audio()
        val changed = state(a).withMarks(a.id, false, false).onSongChange(audio())
        assertNull(changed.isDisliked)
        assertNull(changed.isFavorite)
    }
}
