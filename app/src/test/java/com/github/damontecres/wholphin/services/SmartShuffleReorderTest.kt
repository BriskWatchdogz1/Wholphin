package com.github.damontecres.wholphin.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SmartShuffleReorderTest {
    private val ids = List(6) { UUID.randomUUID() }

    @Test
    fun `entries up to the current one are not part of the plan`() {
        val plan = planReorder(ids, 2, ids.drop(3).reversed())!!
        assertEquals(listOf(5, 4, 3), plan)
    }

    @Test
    fun `plan follows the order`() {
        val order = listOf(ids[4], ids[2], ids[5], ids[3])
        assertEquals(listOf(4, 2, 5, 3), planReorder(ids, 1, order))
    }

    @Test
    fun `entries missing from the order keep their order at the end`() {
        // ids[3] and ids[5] arrived after the order was computed
        val plan = planReorder(ids, 1, listOf(ids[4], ids[2]))
        assertEquals(listOf(4, 2, 3, 5), plan)
    }

    @Test
    fun `ids gone from the queue are skipped`() {
        val plan = planReorder(ids, 0, listOf(UUID.randomUUID(), ids[3], ids[1], ids[2], ids[4], ids[5]))
        assertEquals(listOf(3, 1, 2, 4, 5), plan)
    }

    @Test
    fun `the same song queued twice keeps both entries`() {
        val queue = listOf(ids[0], ids[1], ids[2], ids[1])
        val plan = planReorder(queue, 0, listOf(ids[2], ids[1], ids[1]))!!
        assertEquals(listOf(2, 1, 3), plan)
    }

    @Test
    fun `fewer than 2 upcoming gives null`() {
        assertNull(planReorder(ids, 5, emptyList()))
        assertNull(planReorder(ids, 4, listOf(ids[5])))
        assertNull(planReorder(emptyList(), -1, emptyList()))
    }
}
