package com.github.damontecres.wholphin.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RemoteWindowRemovalTest {
    private fun song(i: Int) = UUID(0, i.toLong())

    private val full = List(600) { song(it) }

    // The remote loaded the window 400..599 of a queue of 600 entries
    private val remote = full.subList(400, 600)

    @Test
    fun `a click after a removal inside the window still jumps`() {
        val queue = full - song(495)
        assertEquals(WindowMatch.Jump(400), matchQueueWindow(queue, remote, 500))
    }

    @Test
    fun `a click after several removals maps the start index to the present entries`() {
        val queue = full - song(405) - song(410) - song(420)
        assertEquals(WindowMatch.Jump(400), matchQueueWindow(queue, remote, 500))
        // the remote clicked its entry 30 (song 430); 3 entries in front of it are gone
        val index = 400 + presentStartIndex(queue, remote, 30)
        assertEquals(song(430), queue[index])
    }

    @Test
    fun `a removed start entry maps to the next present one`() {
        val queue = full - song(410)
        val index = 400 + presentStartIndex(queue, remote, 10)
        assertEquals(song(411), queue[index])
    }

    @Test
    fun `a drag after a removal still reports a move`() {
        val queue = full - song(405)
        val dragged = remote.toMutableList().apply { add(20, removeAt(10)) }
        // song 410 moved from window index 10 to 20; the queue index of both ends is one less (song 405 is gone)
        assertEquals(WindowMatch.Move(400, 9, 19), matchQueueWindow(queue, dragged, 500))
    }

    @Test
    fun `a small queue with removals matches when the window is all that is left`() {
        val small = List(30) { song(it) }
        val queue = small - song(3) - song(4)
        assertEquals(WindowMatch.Jump(0), matchQueueWindow(queue, small, 10))
    }

    @Test
    fun `a list that only overlaps the queue is not matched`() {
        assertNull(matchQueueWindow(full, full.subList(10, 20), 0))
        // a missing id does not make a short list a window
        assertNull(matchQueueWindow(full, full.subList(10, 20) + song(9999), 0))
    }

    @Test
    fun `without removals the start index maps to itself`() {
        assertEquals(30, presentStartIndex(full, remote, 30))
    }
}
