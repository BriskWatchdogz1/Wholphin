package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.AudioItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class QueueReportTest {
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

    @Test
    fun `keeps the queue order and marks the current entry`() {
        val a = audio()
        val b = audio()
        val c = audio()
        val (queue, current) = queueReport(listOf(a, b, c), 1)
        assertEquals(listOf(a.id, b.id, c.id), queue.map { it.id })
        assertEquals("playlistItem${b.key}", current)
        assertEquals(current, queue[1].playlistItemId)
    }

    @Test
    fun `the same song twice gets distinct playlist item ids`() {
        val id = UUID.randomUUID()
        val first = audio(id)
        val second = audio(id)
        val (queue, current) = queueReport(listOf(first, second), 1)
        assertNotEquals(queue[0].playlistItemId, queue[1].playlistItemId)
        assertEquals(queue[1].playlistItemId, current)
    }

    @Test
    fun `an empty queue has no current entry`() {
        val (queue, current) = queueReport(emptyList(), 0)
        assertEquals(0, queue.size)
        assertNull(current)
    }

    @Test
    fun `an index out of range has no current entry`() {
        val items = listOf(audio(), audio())
        assertNull(queueReport(items, -1).second)
        assertNull(queueReport(items, 2).second)
        assertEquals(2, queueReport(items, 2).first.size)
    }
}
